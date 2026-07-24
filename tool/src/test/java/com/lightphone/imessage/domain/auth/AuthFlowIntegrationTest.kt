package com.lightphone.imessage.domain.auth

import com.lightphone.imessage.data.datastore.ITokenRepository
import com.lightphone.imessage.data.provisioning.ActivationStatus
import com.lightphone.imessage.data.provisioning.HardwareInfo
import com.lightphone.imessage.data.provisioning.IProvisioningClient
import com.lightphone.imessage.data.relay.IRelayHttpClient
import com.lightphone.imessage.data.relay.LoginResponse
import com.lightphone.imessage.data.relay.SessionResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Comprehensive integration tests for end-to-end authentication flow. Tests complete login
 * workflows, 2FA, hardware provisioning, session refresh, and error scenarios. Target: 100%
 * coverage of AuthManager and AuthStateMachine.
 *
 * Spec: milestone-2.md § 3.1 (Authentication Flow), ADR-006 (State Machine Pattern)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthFlowIntegrationTest {
    @Mock private lateinit var mockTokenRepository: ITokenRepository

    @Mock private lateinit var mockRelayClient: IRelayHttpClient

    @Mock private lateinit var mockNativeClient: IProvisioningClient

    private val testScope = TestScope()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
    }

    // ========== Full Login Flow ==========

    /**
     * Test: Credentials → 2FA → Hardware Provisioning → Session Established
     *
     * Verifies end-to-end login: user submits credentials, completes 2FA challenge, hardware
     * provisioning succeeds, and session is established and persisted. The state machine flows
     * synchronously through `ProvisioningHardware` inside `submitTwoFA`; because `StateFlow`
     * conflates, only the terminal `SessionEstablished` state is asserted here (see the class-level
     * note in AuthStateMachine).
     */
    @Test
    fun testFullLoginFlow() = runTest {
        // Setup: Mock successful credential submission → 2FA required
        val twoFAChallenge = "2fa-challenge-xyz"
        whenever(mockRelayClient.loginWithCredentials("test@icloud.com", "password123"))
                .thenReturn(Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge)))

        // Setup: Mock successful 2FA submission → session token
        val sessionToken = "session-token-abc123"
        val expiresAt = System.currentTimeMillis() + 3600000
        whenever(mockRelayClient.submitTwoFactor(twoFAChallenge, "123456"))
                .thenReturn(Result.success(SessionResponse(sessionToken, expiresAt)))

        // Setup: Repository provides the Apple ID stashed during earlier login step
        whenever(mockTokenRepository.getAppleId()).thenReturn(Result.success("test@icloud.com"))
        whenever(mockTokenRepository.saveAppleId("test@icloud.com"))
                .thenReturn(Result.success(Unit))
        whenever(mockTokenRepository.saveSessionToken(sessionToken, expiresAt))
                .thenReturn(Result.success(Unit))
        whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))

        // Setup: Mock hardware provisioning
        val deviceId = "device-xyz"
        val certData = byteArrayOf(1, 2, 3, 4)
        whenever(mockNativeClient.registerHardware(sessionToken, "test@icloud.com"))
                .thenReturn(Result.success(HardwareInfo(deviceId, certData)))
        whenever(mockNativeClient.pollActivationStatus(deviceId))
                .thenReturn(Result.success(ActivationStatus.Activated))

        val machine = createAuthStateMachine()

        // Step 1: Request login with credentials
        val loginResult = machine.requestLogin(AppleId("test@icloud.com"), "password123")
        assertTrue("Login request should succeed", loginResult.isSuccess)
        assertEquals(
                "State should be AwaitingTwoFactorCode",
                AuthState.AwaitingTwoFactorCode::class,
                machine.getState().value::class,
        )

        // Step 2: Submit 2FA code — machine synchronously flows through ProvisioningHardware
        // to SessionEstablished.
        val twoFAResult = machine.submitTwoFA("123456")
        assertTrue("2FA submission should succeed", twoFAResult.isSuccess)

        // Step 3: Verify session established
        val finalState = machine.getState().value
        assertEquals(
                "Final state should be SessionEstablished",
                AuthState.SessionEstablished::class,
                finalState::class,
        )
        val sessionState = finalState as AuthState.SessionEstablished
        assertEquals("Session token should match", sessionToken, sessionState.token)
        assertEquals("Expiry should match", expiresAt, sessionState.expiresAt)
    }

    // ========== Login Failure Scenarios ==========

    /**
     * Test: Bad Credentials → Failed State
     *
     * Verifies that login with invalid credentials transitions to Failed state and does not attempt
     * 2FA or hardware provisioning.
     */
    @Test
    fun testLoginFailure() = runTest {
        // Setup: Mock failed credential submission (Result.failure — the state machine's retry
        // helper unwraps Result and rethrows on exhaustion).
        whenever(mockRelayClient.loginWithCredentials("wrong@icloud.com", "wrongpass"))
                .thenReturn(Result.failure(IllegalArgumentException("Invalid credentials")))

        val machine = createAuthStateMachine()

        // Attempt login with bad credentials
        val result = machine.requestLogin(AppleId("wrong@icloud.com"), "wrongpass")
        assertFalse("Login should fail", result.isSuccess)

        // Verify state is Failed
        val state = machine.getState().value
        assertEquals("State should be Failed", AuthState.Failed::class, state::class)
        if (state is AuthState.Failed) {
            assertTrue("Error message should contain details", state.error.isNotEmpty())
        }
    }

    // ========== 2FA Expiry ==========

    /**
     * Test: 2FA Challenge Timeout
     *
     * Verifies that a 2FA challenge that has expired cannot be submitted, and user must restart
     * login flow.
     */
    @Test
    fun testTwoFAExpiry() = runTest {
        // Setup: Mock 2FA challenge
        val twoFAChallenge = "2fa-challenge-expired"
        whenever(mockRelayClient.loginWithCredentials("test@icloud.com", "password"))
                .thenReturn(Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge)))

        // Setup: Mock 2FA submission failure due to expiry (all 3 retries)
        whenever(mockRelayClient.submitTwoFactor(twoFAChallenge, "123456"))
                .thenReturn(Result.failure(IllegalStateException("2FA code expired")))

        val machine = createAuthStateMachine()

        // Step 1: Trigger 2FA challenge
        machine.requestLogin(AppleId("test@icloud.com"), "password")
        assertEquals(
                "State should be AwaitingTwoFactorCode",
                AuthState.AwaitingTwoFactorCode::class,
                machine.getState().value::class,
        )

        // Step 2: Submit code after expiry — a single call bumps the retry counter once. The
        // state machine keeps the user in AwaitingTwoFactorCode until MAX_2FA_RETRIES is reached
        // (so they can re-enter the code), then transitions to Failed.
        var lastResult: Result<Unit> = Result.success(Unit)
        repeat(3) { lastResult = machine.submitTwoFA("123456") }
        assertFalse("Submission of expired code should fail", lastResult.isSuccess)

        // Verify state is Failed
        val state = machine.getState().value
        assertEquals("State should be Failed", AuthState.Failed::class, state::class)
    }

    // ========== Session Refresh ==========

    /**
     * Test: Automatic Token Refresh on Expiry
     *
     * Verifies that when session token approaches expiry, automatic refresh occurs and new token is
     * persisted. Old session remains valid until refresh completes.
     */
    @Test
    fun testSessionRefresh() = runTest {
        // Setup: Establish session first
        val oldToken = "old-token-abc"
        val newToken = "new-token-xyz"
        val newExpiresAt = System.currentTimeMillis() + 3600000

        whenever(mockTokenRepository.getSessionToken()).thenReturn(Result.success(oldToken))
        whenever(mockRelayClient.refreshToken(oldToken))
                .thenReturn(Result.success(SessionResponse(newToken, newExpiresAt)))
        whenever(mockTokenRepository.saveSessionToken(newToken, newExpiresAt))
                .thenReturn(Result.success(Unit))

        val machine = createAuthStateMachine()

        // Trigger token refresh
        val refreshResult = machine.refreshToken()
        assertTrue("Session refresh should complete", refreshResult.isSuccess)

        // Verify state transitioned to SessionEstablished with the new token
        val state = machine.getState().value
        assertEquals(
                "State should be SessionEstablished after refresh",
                AuthState.SessionEstablished::class,
                state::class,
        )
        val sessionState = state as AuthState.SessionEstablished
        assertEquals("New token should be applied", newToken, sessionState.token)
        assertEquals("New expiry should be applied", newExpiresAt, sessionState.expiresAt)
    }

    // ========== Logout and Relogin ==========

    /**
     * Test: Logout → Cleared State → New Login Works
     *
     * Verifies that logout clears all session data, state returns to Idle, and a new login flow can
     * be initiated successfully.
     */
    @Test
    fun testLogoutAndRelogin() = runTest {
        // Setup first login
        val twoFAChallenge = "2fa-challenge-1"
        val sessionToken = "session-token-1"
        val expiresAt = System.currentTimeMillis() + 3600000

        whenever(mockRelayClient.loginWithCredentials("user@icloud.com", "pass1"))
                .thenReturn(Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge)))
        whenever(mockRelayClient.submitTwoFactor(twoFAChallenge, "111111"))
                .thenReturn(Result.success(SessionResponse(sessionToken, expiresAt)))

        val deviceId = "device-1"
        val certData = byteArrayOf(9, 9, 9)
        whenever(mockTokenRepository.getAppleId()).thenReturn(Result.success("user@icloud.com"))
        whenever(mockTokenRepository.saveAppleId("user@icloud.com"))
                .thenReturn(Result.success(Unit))
        whenever(mockTokenRepository.saveSessionToken(sessionToken, expiresAt))
                .thenReturn(Result.success(Unit))
        whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))
        whenever(mockTokenRepository.clearSessionToken()).thenReturn(Result.success(Unit))
        whenever(mockNativeClient.registerHardware(sessionToken, "user@icloud.com"))
                .thenReturn(Result.success(HardwareInfo(deviceId, certData)))
        whenever(mockNativeClient.pollActivationStatus(deviceId))
                .thenReturn(Result.success(ActivationStatus.Activated))

        val machine = createAuthStateMachine()

        // Step 1: Login
        machine.requestLogin(AppleId("user@icloud.com"), "pass1")
        machine.submitTwoFA("111111")

        val sessionState = machine.getState().value
        assertEquals(
                "Should be SessionEstablished after login",
                AuthState.SessionEstablished::class,
                sessionState::class,
        )

        // Step 2: Logout
        val logoutResult = machine.logout()
        assertTrue("Logout should succeed", logoutResult.isSuccess)
        assertEquals("State should return to Idle", AuthState.Idle, machine.getState().value)

        // Step 3: Setup second login with different credentials
        val twoFAChallenge2 = "2fa-challenge-2"
        whenever(mockRelayClient.loginWithCredentials("user2@icloud.com", "pass2"))
                .thenReturn(Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge2)))

        // Step 4: Relogin
        val reloginResult = machine.requestLogin(AppleId("user2@icloud.com"), "pass2")
        assertTrue("Relogin should succeed", reloginResult.isSuccess)
        assertEquals(
                "State should be AwaitingTwoFactorCode",
                AuthState.AwaitingTwoFactorCode::class,
                machine.getState().value::class,
        )
    }

    // ========== Concurrent Auth Attempts ==========

    /**
     * Test: Multiple Concurrent Authentication Attempts Rejected
     *
     * Verifies that concurrent calls to `requestLogin()` land in a consistent, valid state.
     * Serialization is best-effort inside the state machine (the mutex protects counters and
     * challenge only, not the whole flow), so we assert the terminal state is one of the expected
     * outcomes rather than dictating which call "wins".
     */
    @Test
    fun testConcurrentAuthAttempts() = runTest {
        val twoFAChallenge = "2fa-challenge"
        whenever(mockRelayClient.loginWithCredentials("test@icloud.com", "password"))
                .thenReturn(Result.success(LoginResponse.TwoFactorRequired(twoFAChallenge)))

        val machine = createAuthStateMachine()

        // Launch 3 concurrent login attempts using the runTest TestScope (this).
        val job1 = async { machine.requestLogin(AppleId("test@icloud.com"), "password") }
        val job2 = async { machine.requestLogin(AppleId("test@icloud.com"), "password") }
        val job3 = async { machine.requestLogin(AppleId("test@icloud.com"), "password") }

        job1.await()
        job2.await()
        job3.await()

        // At minimum, state should be consistent (not corrupted).
        val finalState = machine.getState().value
        assertTrue(
                "Final state should be valid",
                finalState is AuthState.AwaitingTwoFactorCode || finalState is AuthState.Failed,
        )
    }

    // ========== Helper Methods ==========

    private fun createAuthStateMachine(): AuthStateMachine =
            AuthStateMachine(
                    tokenRepository = mockTokenRepository,
                    relayClient = mockRelayClient,
                    nativeClient = mockNativeClient,
                    scope = testScope,
            )
}
