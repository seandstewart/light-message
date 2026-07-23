package com.lightphone.imessage.domain.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Public facade for authentication management.
 *
 * Delegates all operations to an internal AuthStateMachine while exposing a clean, idiomatic
 * interface that implements IAuthManager.
 */
class AuthManager(private val stateMachine: AuthStateMachine) : IAuthManager {
    override val state: StateFlow<AuthState> = stateMachine.getState()

    override suspend fun startAuthentication(appleId: AppleId, password: String): Result<Unit> {
        return stateMachine.requestLogin(appleId, password)
    }

    override suspend fun submitTwoFactorCode(code: String): Result<Unit> {
        return stateMachine.submitTwoFA(code)
    }

    override suspend fun resendTwoFactorCode(): Result<Unit> {
        return stateMachine.resendTwoFA()
    }

    override suspend fun refreshSession(): Result<Unit> {
        return stateMachine.refreshToken()
    }

    override suspend fun logout(): Result<Unit> {
        return stateMachine.logout()
    }
}
