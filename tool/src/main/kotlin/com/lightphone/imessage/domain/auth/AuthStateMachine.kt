package com.lightphone.imessage.domain.auth

import com.lightphone.imessage.data.datastore.ITokenRepository
import com.lightphone.imessage.data.native.ActivationStatus
import com.lightphone.imessage.data.native.INativeServiceClient
import com.lightphone.imessage.data.relay.IRelayClient
import com.lightphone.imessage.data.relay.LoginResponse
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Internal state machine managing authentication flow and state transitions.
 *
 * Handles:
 * - Credential submission with retry logic (3 retries, backoff: 1s, 2s, 4s)
 * - 2FA submission and resend (2 retries)
 * - Hardware provisioning and polling
 * - Session refresh with exponential backoff (1s → 60s cap)
 * - Logout and cleanup
 *
 * All state changes are persisted to a StateFlow for reactive updates.
 */
internal class AuthStateMachine(
        private val tokenRepository: ITokenRepository,
        private val relayClient: IRelayClient,
        private val nativeClient: INativeServiceClient,
        private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    private val stateMutex = Mutex()
    private var currentChallenge: String? = null
    private var loginRetryCount = 0
    private var twoFARetryCount = 0
    private var twoFAResendCount = 0

    fun getState(): StateFlow<AuthState> = _state.asStateFlow()

    /**
     * Initiates login with Apple ID credentials. Transitions: Idle → AwaitingCredentials
     *
     * Sends credentials to relay; if 2FA required, transitions to AwaitingTwoFactorCode. If direct
     * activation possible, transitions to ProvisioningHardware. On error, transitions to Failed.
     */
    suspend fun requestLogin(appleId: AppleId, password: String): Result<Unit> {
        return try {
            // Reset retry counters
            stateMutex.withLock {
                loginRetryCount = 0
                twoFARetryCount = 0
                twoFAResendCount = 0
            }
            _state.value = AuthState.AwaitingCredentials()

            // Attempt login with retry logic (3 retries on network failure)
            val loginResponse =
                    retryWithBackoff(maxRetries = 3, backoffDelays = listOf(1000, 2000, 4000)) {
                        relayClient.loginWithCredentials(appleId.email, password)
                    }

            when (loginResponse) {
                is LoginResponse.TwoFactorRequired -> {
                    stateMutex.withLock { currentChallenge = loginResponse.challenge }
                    _state.value = AuthState.AwaitingTwoFactorCode(loginResponse.challenge)
                    Result.success(Unit)
                }
                is LoginResponse.SessionToken -> {
                    proceedToProvisioning(appleId, loginResponse.token, loginResponse.expiresAt)
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Login failed"
            _state.value = AuthState.Failed(errorMsg)
            Result.failure(e)
        }
    }

    /**
     * Submits 2FA code. Only valid when state is AwaitingTwoFactorCode.
     *
     * On success, transitions to ProvisioningHardware. On failure, increments retry count and
     * transitions to Failed if retries exceeded.
     */
    suspend fun submitTwoFA(code: String): Result<Unit> {
        return try {
            val challenge =
                    stateMutex.withLock {
                        currentChallenge
                                ?: return Result.failure(
                                        IllegalStateException("No active 2FA challenge")
                                )
                    }

            // Validate 2FA code format: numeric 6-digit codes as per Apple iMessage specification.
            // Non-numeric codes or codes of different lengths are rejected.
            if (code.length != 6 || !code.all { it.isDigit() }) {
                return Result.failure(IllegalArgumentException("2FA code must be exactly 6 digits"))
            }

            // Retry 2FA submission up to 2 times
            val sessionResponse =
                    retryWithBackoff(maxRetries = 2, backoffDelays = listOf(500, 1000)) {
                        relayClient.submitTwoFactor(challenge, code)
                    }

            // Get Apple ID from repository (stored during login)
            val appleIdResult = tokenRepository.getAppleId()
            if (appleIdResult.isFailure) {
                throw appleIdResult.exceptionOrNull() ?: Exception("Apple ID not found")
            }

            val appleIdEmail = appleIdResult.getOrNull() ?: throw Exception("Apple ID not found")

            proceedToProvisioning(
                    AppleId(appleIdEmail),
                    sessionResponse.token,
                    sessionResponse.expiresAt
            )
        } catch (e: Exception) {
            stateMutex.withLock { twoFARetryCount++ }
            val errorMsg = e.message ?: "2FA submission failed"
            _state.value = AuthState.Failed(errorMsg)
            Result.failure(e)
        }
    }

    /**
     * Requests a new 2FA code via SMS. Only valid when state is AwaitingTwoFactorCode. Limited to 3
     * resend attempts.
     */
    suspend fun resendTwoFA(): Result<Unit> {
        return try {
            val (challenge, canResend) =
                    stateMutex.withLock {
                        val ch =
                                currentChallenge
                                        ?: return Result.failure(
                                                IllegalStateException("No active 2FA challenge")
                                        )
                        val can = twoFAResendCount < 3
                        Pair(ch, can)
                    }

            if (!canResend) {
                return Result.failure(IllegalStateException("Maximum 2FA resend attempts exceeded"))
            }

            val resendResult = relayClient.resendTwoFactor(challenge)
            if (resendResult.isSuccess) {
                stateMutex.withLock { twoFAResendCount++ }
                // Keep state as AwaitingTwoFactorCode
                return Result.success(Unit)
            } else {
                val error = resendResult.exceptionOrNull() ?: Exception("Resend failed")
                _state.value = AuthState.Failed(error.message ?: "Resend failed")
                Result.failure(error)
            }
        } catch (e: Exception) {
            _state.value = AuthState.Failed(e.message ?: "Resend failed")
            Result.failure(e)
        }
    }

    /**
     * Refreshes the session token before expiration. Uses exponential backoff (1s → 60s cap) on
     * network failures.
     *
     * On success, updates token in repository and maintains SessionEstablished state. On failure,
     * transitions to AwaitingCredentials (user must re-authenticate).
     */
    suspend fun refreshToken(): Result<Unit> {
        return try {
            val currentTokenResult = tokenRepository.getSessionToken()
            if (currentTokenResult.isFailure) {
                throw currentTokenResult.exceptionOrNull()
                        ?: Exception("No session token to refresh")
            }

            val currentToken =
                    currentTokenResult.getOrNull() ?: throw Exception("No session token to refresh")

            // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 60s (cap)
            val backoffDelays = (0..6).map { i -> min(1000L shl i, 60000L).toInt() }

            val sessionResponse =
                    retryWithBackoff(
                            maxRetries = backoffDelays.size,
                            backoffDelays = backoffDelays
                    ) { relayClient.refreshToken(currentToken) }

            // Update token repository
            val saveResult =
                    tokenRepository.saveSessionToken(
                            sessionResponse.token,
                            sessionResponse.expiresAt
                    )
            if (saveResult.isFailure) {
                throw saveResult.exceptionOrNull() ?: Exception("Failed to save token")
            }

            _state.value =
                    AuthState.SessionEstablished(sessionResponse.token, sessionResponse.expiresAt)
            Result.success(Unit)
        } catch (e: Exception) {
            // Token refresh failed; require re-authentication
            _state.value =
                    AuthState.AwaitingCredentials(
                            lastError = "Session expired. Please log in again."
                    )
            Result.failure(e)
        }
    }

    /** Logs out and clears all session data. Transitions: SessionEstablished → LoggingOut → Idle */
    suspend fun logout(): Result<Unit> {
        return try {
            _state.value = AuthState.LoggingOut

            // Clear all sensitive data from repository
            val clearTokenResult = tokenRepository.clearSessionToken()
            if (clearTokenResult.isFailure) {
                throw clearTokenResult.exceptionOrNull() ?: Exception("Failed to clear session")
            }

            stateMutex.withLock {
                currentChallenge = null
                loginRetryCount = 0
                twoFARetryCount = 0
                twoFAResendCount = 0
            }

            _state.value = AuthState.Idle
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = AuthState.Failed(e.message ?: "Logout failed")
            Result.failure(e)
        }
    }

    /** Internal: Transitions from login/2FA to hardware provisioning. */
    private suspend fun proceedToProvisioning(
            appleId: AppleId,
            token: String,
            expiresAt: Long
    ): Result<Unit> {
        return try {
            // Save Apple ID and token
            val saveAppleIdResult = tokenRepository.saveAppleId(appleId.email)
            if (saveAppleIdResult.isFailure) {
                throw saveAppleIdResult.exceptionOrNull() ?: Exception("Failed to save Apple ID")
            }

            val saveTokenResult = tokenRepository.saveSessionToken(token, expiresAt)
            if (saveTokenResult.isFailure) {
                throw saveTokenResult.exceptionOrNull() ?: Exception("Failed to save token")
            }

            _state.value = AuthState.ProvisioningHardware(progress = 10)

            // Register hardware with native service
            val hardwareResult = nativeClient.registerHardware(token, appleId.email)
            if (hardwareResult.isFailure) {
                throw hardwareResult.exceptionOrNull() ?: Exception("Hardware registration failed")
            }

            val hardwareInfo =
                    hardwareResult.getOrNull() ?: throw Exception("Hardware info not returned")

            _state.value = AuthState.ProvisioningHardware(progress = 50)

            // Poll for activation status
            val activationResult =
                    nativeClient.pollActivationStatus(deviceId = hardwareInfo.deviceId)
            if (activationResult.isFailure) {
                throw activationResult.exceptionOrNull() ?: Exception("Activation polling failed")
            }

            val activationStatus =
                    activationResult.getOrNull()
                            ?: throw Exception("Activation status not returned")

            when (activationStatus) {
                ActivationStatus.Activated -> {
                    // Save hardware info
                    val saveHwResult =
                            tokenRepository.saveHardwareInfo(hardwareInfo.certificateData)
                    if (saveHwResult.isFailure) {
                        throw saveHwResult.exceptionOrNull()
                                ?: Exception("Failed to save hardware info")
                    }

                    _state.value = AuthState.ProvisioningHardware(progress = 100)
                    _state.value = AuthState.SessionEstablished(token, expiresAt)
                    Result.success(Unit)
                }
                ActivationStatus.Pending -> {
                    Result.failure(Exception("Activation timed out"))
                }
                is ActivationStatus.Failed -> {
                    Result.failure(Exception("Activation failed: ${activationStatus.reason}"))
                }
            }
        } catch (e: Exception) {
            _state.value = AuthState.Failed(e.message ?: "Hardware provisioning failed")
            Result.failure(e)
        }
    }

    /**
     * Helper: Retries a suspended operation with exponential backoff.
     *
     * @param maxRetries Number of retry attempts (not including initial attempt)
     * @param backoffDelays List of delays in milliseconds between retries
     * @param operation Suspend function to execute
     * @return Result from successful operation or last failed attempt
     */
    private suspend fun <T> retryWithBackoff(
            maxRetries: Int,
            backoffDelays: List<Int>,
            operation: suspend () -> Result<T>
    ): T {
        var lastResult: Result<T>? = null

        for (attempt in 0..maxRetries) {
            lastResult = operation()

            if (lastResult.isSuccess) {
                return lastResult.getOrNull()
                        ?: throw Exception("Operation succeeded but returned null")
            }

            // Don't delay after the last attempt
            if (attempt < maxRetries && attempt < backoffDelays.size) {
                val delayMs = backoffDelays[attempt]
                delay(delayMs.toLong())
            }
        }

        // All retries exhausted; throw the last error
        val lastError = lastResult?.exceptionOrNull()
        throw lastError ?: Exception("Operation failed after $maxRetries retries")
    }
}
