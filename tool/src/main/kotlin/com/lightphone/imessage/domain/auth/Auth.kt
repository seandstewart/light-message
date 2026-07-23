package com.lightphone.imessage.domain.auth

import kotlinx.coroutines.flow.StateFlow

/** Represents an Apple ID email. */
data class AppleId(val email: String)

/**
 * Public interface for authentication management.
 *
 * Provides methods to authenticate with Apple ID, handle 2FA, refresh sessions, and logout. All
 * operations return Result<Unit> to enable proper error handling and recovery.
 */
interface IAuthManager {
    /**
     * Reactive state flow of the current authentication state. Subscribers receive updates whenever
     * the state changes.
     */
    val state: StateFlow<AuthState>

    /**
     * Initiates Apple ID authentication flow. Transitions state to AwaitingCredentials → sends
     * credentials to relay → may transition to AwaitingTwoFactorCode or ProvisioningHardware.
     *
     * @param appleId Apple ID (email) to authenticate
     * @param password Apple ID password
     * @return Result.success(Unit) if credentials accepted, Result.failure otherwise
     */
    suspend fun startAuthentication(
        appleId: AppleId,
        password: String,
    ): Result<Unit>

    /**
     * Submits 2FA code received via SMS or security prompt. Only valid when state is
     * AwaitingTwoFactorCode.
     *
     * @param code 6-digit 2FA code
     * @return Result.success(Unit) if code valid, Result.failure otherwise
     */
    suspend fun submitTwoFactorCode(code: String): Result<Unit>

    /**
     * Requests a new 2FA code via SMS. Only valid when state is AwaitingTwoFactorCode. Limited to 3
     * resend attempts per challenge.
     *
     * @return Result.success(Unit) if resend initiated, Result.failure otherwise
     */
    suspend fun resendTwoFactorCode(): Result<Unit>

    /**
     * Refreshes the session token before expiration. If token is expired or invalid, returns to
     * AwaitingCredentials state. Exponential backoff is applied on network failures (1s → 60s cap).
     *
     * @return Result.success(Unit) if token refreshed, Result.failure otherwise
     */
    suspend fun refreshSession(): Result<Unit>

    /**
     * Logs out and clears all session and credential data. Transitions state to LoggingOut → Idle.
     *
     * @return Result.success(Unit) on success, Result.failure on storage errors
     */
    suspend fun logout(): Result<Unit>
}
