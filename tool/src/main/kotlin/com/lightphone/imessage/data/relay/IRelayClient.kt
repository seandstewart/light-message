package com.lightphone.imessage.data.relay

/**
 * HTTP client interface for communicating with the relay (Apple ID) servers.
 *
 * This interface abstracts the HTTP layer and is implemented separately. See TASK_006 for
 * implementation details.
 */
interface IRelayClient {
    /**
     * Authenticates with Apple ID credentials.
     *
     * @param email Apple ID email
     * @param password Apple ID password
     * @return Result containing either a LoginResponse or error details
     */
    suspend fun loginWithCredentials(email: String, password: String): Result<LoginResponse>

    /**
     * Submits 2FA code to complete authentication.
     *
     * @param challenge Challenge string from previous login attempt
     * @param code 6-digit 2FA code
     * @return Result containing either a SessionResponse or error details
     */
    suspend fun submitTwoFactor(challenge: String, code: String): Result<SessionResponse>

    /**
     * Requests a new 2FA code via SMS.
     *
     * @param challenge Challenge string from previous login attempt
     * @return Result indicating success or error
     */
    suspend fun resendTwoFactor(challenge: String): Result<Unit>

    /**
     * Refreshes an expired session token.
     *
     * @param token Current session token
     * @return Result containing either a new SessionResponse or error details
     */
    suspend fun refreshToken(token: String): Result<SessionResponse>
}

/**
 * Response from login endpoint. Either contains a session token (direct activation) or a 2FA
 * challenge.
 */
sealed class LoginResponse {
    /**
     * 2FA is required.
     * @param challenge Challenge string to use in 2FA submission
     */
    data class TwoFactorRequired(val challenge: String) : LoginResponse()

    /**
     * No 2FA needed; direct session token.
     * @param token Session token
     * @param expiresAt Expiration timestamp in milliseconds
     */
    data class SessionToken(val token: String, val expiresAt: Long) : LoginResponse()
}

/** Response from 2FA or token refresh endpoints. */
data class SessionResponse(val token: String, val expiresAt: Long)
