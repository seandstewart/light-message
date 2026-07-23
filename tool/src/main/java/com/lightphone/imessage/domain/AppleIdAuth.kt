package com.lightphone.imessage.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * State machine for Apple ID authentication and session management.
 * Handles login, 2FA, hardware provisioning, and relay retry policy.
 */
class AppleIdAuth {
    enum class State {
        Idle,
        AwaitingCredentials,
        AwaitingTwoFactor,
        ProvisioningHardware,
        AuthenticationFailed,
        SessionEstablished,
    }

    fun authenticate(
        appleId: String,
        password: String,
    ): Flow<State> {
        // TODO: Implement login flow with relay retry (3x, backoff)
        // TODO: Handle 2FA challenge
        // TODO: Provision hardware info to relay
        return flowOf()
    }

    fun persistSession(token: String) {
        // TODO: Store session token in encrypted DataStore
    }

    fun getPersistedSession(): String? {
        // TODO: Retrieve session token from DataStore
        return null
    }
}
