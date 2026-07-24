package com.lightphone.imessage.domain.auth

import com.lightphone.imessage.data.datastore.ITokenRepository
import com.lightphone.imessage.data.provisioning.ActivationStatus
import com.lightphone.imessage.data.provisioning.IProvisioningClient
import com.lightphone.imessage.data.relay.IRelayHttpClient
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
 * State machine managing authentication flow and state transitions.
 *
 * Handles:
 * - Credential submission with retry logic (3 attempts, backoff: 1s, 2s)
 * - 2FA submission and user-facing retry accounting (up to [MAX_2FA_RETRIES] wrong-code attempts)
 * - 2FA resend, capped at [MAX_2FA_RESENDS] per challenge
 * - Hardware provisioning and polling
 * - Session refresh with exponential backoff (1s → 60s cap)
 * - Logout and cleanup
 *
 * All state changes are persisted to a [StateFlow] for reactive updates.
 *
 * NOTE ON PROGRESS EMISSIONS: Progress increments emitted through [AuthState.ProvisioningHardware]
 * are advisory. [StateFlow] conflates rapid updates, so intermediate percentages (10, 50, 100) may
 * not surface. UI should treat the final terminal state as authoritative and not rely on observing
 * every intermediate percentage.
 */
class AuthStateMachine(
        private val tokenRepository: ITokenRepository,
        private val relayClient: IRelayHttpClient,
        private val nativeClient: IProvisioningClient,
        private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    private val stateMutex = Mutex()
    private var currentChallenge: String? = null
    private var loginRetryCount = 0
    private var twoFARetryCount = 0
    private var twoFAResendCount = 0

    fun getState(): StateFlow<AuthState> = _state.asStateFlow()

    /**
     * Initiates login with Apple ID credentials. Emits [AuthState.Authenticating] while the relay
     * call is in flight.
     *
     * Sends credentials to relay; if 2FA required, transitions to AwaitingTwoFactorCode. If direct
     * activation possible, transitions to ProvisioningHardware. On error, transitions to Failed.
     */
    suspend fun requestLogin(
            appleId: AppleId,
            password: String,
    ): Result<Unit> {
        return try {
            // Reset retry counters + clear any stale 2FA challenge from a prior attempt.
            stateMutex.withLock {
                loginRetryCount = 0
                twoFARetryCount = 0
                twoFAResendCount = 0
                currentChallenge = null
            }
            _state.value = AuthState.Authenticating

            // Attempt login with retry logic (3 attempts total on network failure)
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
            _state.value = AuthState.Failed(sanitize(e, "Login failed"))
            Result.failure(e)
        }
    }

    /**
     * Submits 2FA code. Only valid when state is AwaitingTwoFactorCode.
     *
     * On success, transitions to ProvisioningHardware and clears the current challenge. On a
     * recoverable failure (retries remain per [MAX_2FA_RETRIES]), stays in AwaitingTwoFactorCode so
     * the user can re-enter the code. On the final retry, transitions to Failed.
     */
    suspend fun submitTwoFA(code: String): Result<Unit> {
        return try {
            val challenge =
                    stateMutex.withLock {
                        currentChallenge
                                ?: return Result.failure(
                                        IllegalStateException("No active 2FA challenge"),
                                )
                    }

            // Validate 2FA code format: numeric 6-digit codes as per Apple iMessage specification.
            // Non-numeric codes or codes of different lengths are rejected.
            if (code.length != 6 || !code.all { it.isDigit() }) {
                return Result.failure(IllegalArgumentException("2FA code must be exactly 6 digits"))
            }

            // Retry 2FA submission network layer up to 3 attempts
            val sessionResponse =
                    retryWithBackoff(maxRetries = 3, backoffDelays = listOf(500, 1000)) {
                        relayClient.submitTwoFactor(challenge, code)
                    }

            // Get Apple ID from repository (stored during login)
            val appleIdResult = tokenRepository.getAppleId()
            if (appleIdResult.isFailure) {
                throw appleIdResult.exceptionOrNull() ?: Exception("Apple ID not found")
            }

            val appleIdEmail = appleIdResult.getOrNull() ?: throw Exception("Apple ID not found")

            // Clear challenge on success — it must not be reused.
            stateMutex.withLock { currentChallenge = null }

            proceedToProvisioning(
                    AppleId(appleIdEmail),
                    sessionResponse.token,
                    sessionResponse.expiresAt,
            )
        } catch (e: Exception) {
            // Atomically consume a retry slot and decide whether the user has attempts remaining.
            val exhausted =
                    stateMutex.withLock {
                        twoFARetryCount++
                        twoFARetryCount >= MAX_2FA_RETRIES
                    }
            val sanitized = sanitize(e, "2FA submission failed")
            if (exhausted) {
                _state.value = AuthState.Failed(sanitized)
            }
            // Otherwise leave state as AwaitingTwoFactorCode; caller inspects Result.failure to
            // surface the error to the user without losing the challenge.
            Result.failure(e)
        }
    }

    /**
     * Requests a new 2FA code via SMS. Only valid when state is AwaitingTwoFactorCode. Limited to
     * [MAX_2FA_RESENDS] resend attempts per challenge.
     */
    suspend fun resendTwoFA(): Result<Unit> {
        return try {
            // Atomically read challenge, verify quota, and reserve the slot in a single critical
            // section so concurrent resends cannot bypass the cap (TOCTOU-safe).
            val challenge =
                    stateMutex.withLock {
                        val ch =
                                currentChallenge
                                        ?: return Result.failure(
                                                IllegalStateException("No active 2FA challenge"),
                                        )
                        if (twoFAResendCount >= MAX_2FA_RESENDS) {
                            return Result.failure(
                                    IllegalStateException("Maximum 2FA resend attempts exceeded"),
                            )
                        }
                        twoFAResendCount++
                        ch
                    }

            val resendResult = relayClient.resendTwoFactor(challenge)
            if (resendResult.isSuccess) {
                // Keep state as AwaitingTwoFactorCode
                return Result.success(Unit)
            } else {
                val error = resendResult.exceptionOrNull() ?: Exception("Resend failed")
                _state.value = AuthState.Failed(sanitize(error, "Resend failed"))
                Result.failure(error)
            }
        } catch (e: Exception) {
            _state.value = AuthState.Failed(sanitize(e, "Resend failed"))
            Result.failure(e)
        }
    }

    /**
     * Refreshes the session token before expiration. Uses exponential backoff (1s → 60s cap) on
     * network failures.
     *
     * On success, updates token in repository and transitions to SessionEstablished. On
     * authentication failure ([UnauthorizedException] or an HTTP 401/403 wrapped as such by the
     * relay layer), demotes to AwaitingCredentials. Transient errors (network, timeout, 5xx) do NOT
     * demote — the caller receives Result.failure and the state is left unchanged so the UI can
     * retry without forcing a full re-login.
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
                            backoffDelays = backoffDelays,
                    ) { relayClient.refreshToken(currentToken) }

            // Update token repository
            val saveResult =
                    tokenRepository.saveSessionToken(
                            sessionResponse.token,
                            sessionResponse.expiresAt,
                    )
            if (saveResult.isFailure) {
                throw saveResult.exceptionOrNull() ?: Exception("Failed to save token")
            }

            _state.value =
                    AuthState.SessionEstablished(sessionResponse.token, sessionResponse.expiresAt)
            Result.success(Unit)
        } catch (e: Exception) {
            if (isAuthFailure(e)) {
                // Server explicitly rejected the token — force re-authentication.
                _state.value =
                        AuthState.AwaitingCredentials(
                                lastError = "Session expired. Please log in again.",
                        )
            }
            // For transient errors (network/timeout/5xx) leave state untouched so the caller can
            // retry without demoting the whole session. The failure is surfaced via Result.
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
            _state.value = AuthState.Failed(sanitize(e, "Logout failed"))
            Result.failure(e)
        }
    }

    /** Internal: Transitions from login/2FA to hardware provisioning. */
    private suspend fun proceedToProvisioning(
            appleId: AppleId,
            token: String,
            expiresAt: Long,
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
                    val err = Exception("Activation timed out")
                    _state.value = AuthState.Failed(sanitize(err, "Activation timed out"))
                    Result.failure(err)
                }
                is ActivationStatus.Failed -> {
                    val reason = activationStatus.reason
                    val err = Exception("Activation failed: $reason")
                    // The reason string comes from our native layer and is considered safe to
                    // surface to the user; still route it through sanitize for consistency.
                    _state.value = AuthState.Failed(sanitize(err, "Activation failed"))
                    Result.failure(err)
                }
            }
        } catch (e: Exception) {
            _state.value = AuthState.Failed(sanitize(e, "Hardware provisioning failed"))
            Result.failure(e)
        }
    }

    /**
     * Helper: Retries a suspended operation with backoff.
     *
     * @param maxRetries Total number of attempts (NOT extra attempts on top of an initial call).
     * Passing `maxRetries = 3` yields exactly 3 attempts.
     * @param backoffDelays Delays in milliseconds inserted between attempts. If fewer delays than
     * attempts are provided, later gaps are skipped.
     * @param operation Suspend function to execute
     * @return Result from successful operation or throws the last error after exhaustion
     */
    private suspend fun <T> retryWithBackoff(
            maxRetries: Int,
            backoffDelays: List<Int>,
            operation: suspend () -> Result<T>,
    ): T {
        var lastResult: Result<T>? = null

        for (attempt in 0 until maxRetries) {
            lastResult = operation()

            if (lastResult.isSuccess) {
                return lastResult.getOrNull()
                        ?: throw Exception("Operation succeeded but returned null")
            }

            // Don't delay after the last attempt
            if (attempt < maxRetries - 1 && attempt < backoffDelays.size) {
                val delayMs = backoffDelays[attempt]
                delay(delayMs.toLong())
            }
        }

        // All attempts exhausted; throw the last error
        val lastError = lastResult?.exceptionOrNull()
        throw lastError ?: Exception("Operation failed after $maxRetries attempts")
    }

    /**
     * Classifies an error as an authorization failure that should force re-authentication.
     *
     * The relay layer is expected to wrap HTTP 401/403 responses in [UnauthorizedException]. If a
     * different HTTP client leaks through (e.g., Retrofit `HttpException`) it will not be
     * classified here; callers should keep the relay layer's error mapping up to date.
     */
    private fun isAuthFailure(e: Throwable): Boolean = e is UnauthorizedException

    /**
     * Maps a raw exception to a safe, user-facing message. Upstream exceptions may contain the
     * user's Apple ID, tokens, cookies, or server internals — never propagate `e.message` verbatim
     * into user-visible state. The raw exception should be logged separately at warn/error level by
     * the caller if diagnostic detail is needed.
     */
    private fun sanitize(e: Throwable, fallback: String): String {
        return when (e) {
            // Developer-authored preconditions inside this module — messages are known-safe.
            is IllegalArgumentException -> e.message ?: fallback
            is IllegalStateException -> e.message ?: fallback
            is UnauthorizedException -> "Authentication failed. Please log in again."
            else -> fallback
        }
    }

    companion object {
        /** Maximum number of wrong-code 2FA submissions the user may make before demotion. */
        const val MAX_2FA_RETRIES: Int = 3

        /** Maximum number of resend requests permitted per 2FA challenge. */
        const val MAX_2FA_RESENDS: Int = 3
    }
}
