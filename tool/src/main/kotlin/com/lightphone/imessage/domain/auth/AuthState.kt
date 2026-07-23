package com.lightphone.imessage.domain.auth

/**
 * Sealed class representing authentication states.
 *
 * Valid state transitions:
 * - Idle → AwaitingCredentials
 * - AwaitingCredentials → ProvisioningHardware / Failed
 * - AwaitingTwoFactorCode → ProvisioningHardware / Failed
 * - ProvisioningHardware → SessionEstablished / Failed
 * - SessionEstablished → ProvisioningHardware (on refresh), LoggingOut, or stays stable
 * - Failed → AwaitingCredentials (retry allowed) or Idle (abort/max retries)
 * - LoggingOut → Idle
 */
sealed class AuthState {
    /** Initial state; no authentication in progress. */
    object Idle : AuthState()

    /**
     * Waiting for user to provide Apple ID and password.
     * @param lastError Optional error message from previous attempt (e.g., invalid credentials).
     */
    data class AwaitingCredentials(val lastError: String? = null) : AuthState()

    /**
     * Server requested 2FA code. User must submit code.
     * @param challenge The 2FA challenge string from the server.
     */
    data class AwaitingTwoFactorCode(val challenge: String) : AuthState()

    /**
     * Hardware is being provisioned (registered with server).
     * @param progress Progress percentage (0–100).
     */
    data class ProvisioningHardware(val progress: Int) : AuthState() {
        init {
            require(progress in 0..100) { "Progress must be between 0 and 100, got $progress" }
        }
    }

    /**
     * Session is established and valid.
     * @param token Session token for API calls.
     * @param expiresAt Expiration timestamp in milliseconds since epoch.
     */
    data class SessionEstablished(val token: String, val expiresAt: Long) : AuthState()

    /**
     * Authentication failed. User can retry if retries remain.
     * @param error Description of the error.
     */
    data class Failed(val error: String) : AuthState()

    /** Logout in progress; clearing session and credentials. */
    object LoggingOut : AuthState()
}
