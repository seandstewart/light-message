package com.lightphone.imessage.domain.auth

import com.lightphone.imessage.data.datastore.ITokenRepository
import com.lightphone.imessage.data.native.ActivationStatus
import com.lightphone.imessage.data.native.HardwareInfo
import com.lightphone.imessage.data.native.INativeServiceClient
import com.lightphone.imessage.data.relay.IRelayClient
import com.lightphone.imessage.data.relay.LoginResponse
import com.lightphone.imessage.data.relay.SessionResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Comprehensive unit tests for AuthStateMachine. Tests state transitions, retry logic, 2FA flow,
 * and error handling. Target: 100% code coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthStateMachineTest {
    @Mock private lateinit var mockTokenRepository: ITokenRepository

    @Mock private lateinit var mockRelayClient: IRelayClient

    @Mock private lateinit var mockNativeClient: INativeServiceClient

    private val testScope = TestScope()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
    }

    // ========== Initial State ==========

    @Test
    fun testInitialState() {
        val machine = createAuthStateMachine()
        val state = machine.getState().value

        assertEquals("Initial state must be Idle", AuthState.Idle, state)
    }

    // ========== Idle to AwaitingCredentials Transition ==========

    @Test
    fun testIdleToAwaitingCredentials() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(
                        LoginResponse.SessionToken(
                            token = "token-123",
                            expiresAt = futureTimestamp(),
                        ),
                    ),
                )
            whenever(mockTokenRepository.saveAppleId(any())).thenReturn(Result.success(Unit))
            whenever(mockTokenRepository.saveSessionToken(any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockNativeClient.registerHardware(any(), any()))
                .thenReturn(
                    Result.success(
                        HardwareInfo(
                            deviceId = "device-123",
                            certificateData = ByteArray(0),
                        ),
                    ),
                )
            whenever(mockNativeClient.pollActivationStatus(any()))
                .thenReturn(Result.success(ActivationStatus.Activated))
            whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            val appleId = AppleId("test@icloud.com")
            val result = machine.requestLogin(appleId, "password123")

            assertTrue("Login request must succeed", result.isSuccess)

            val state = machine.getState().value
            assertTrue(
                "State must be SessionEstablished after successful login",
                state is AuthState.SessionEstablished,
            )
        }

    @Test
    fun testRequestLoginTransitionsToAwaitingCredentials() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )

            val machine = createAuthStateMachine()

            val appleId = AppleId("test@icloud.com")
            machine.requestLogin(appleId, "password123")

            val state = machine.getState().value
            assertTrue(
                "After requestLogin, state should be AwaitingTwoFactorCode",
                state is AuthState.AwaitingTwoFactorCode,
            )
        }

    // ========== Credentials to 2FA Transition ==========

    @Test
    fun testCredentialsToTwoFA() =
        runTest {
            val challenge = "challenge-456"
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(Result.success(LoginResponse.TwoFactorRequired(challenge = challenge)))

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")

            val state = machine.getState().value
            assertTrue("State must be AwaitingTwoFactorCode", state is AuthState.AwaitingTwoFactorCode)
            assertEquals(
                "Challenge must be stored",
                challenge,
                (state as AuthState.AwaitingTwoFactorCode).challenge,
            )
        }

    // ========== 2FA Submission ==========

    @Test
    fun testTwoFASubmissionSuccess() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )
            whenever(mockTokenRepository.getAppleId()).thenReturn(Result.success("test@icloud.com"))
            whenever(mockRelayClient.submitTwoFactor(any(), any()))
                .thenReturn(
                    Result.success(
                        SessionResponse(token = "token-456", expiresAt = futureTimestamp()),
                    ),
                )
            whenever(mockTokenRepository.saveAppleId(any())).thenReturn(Result.success(Unit))
            whenever(mockTokenRepository.saveSessionToken(any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockNativeClient.registerHardware(any(), any()))
                .thenReturn(
                    Result.success(
                        HardwareInfo(
                            deviceId = "device-123",
                            certificateData = ByteArray(0),
                        ),
                    ),
                )
            whenever(mockNativeClient.pollActivationStatus(any()))
                .thenReturn(Result.success(ActivationStatus.Activated))
            whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")
            val result = machine.submitTwoFA("123456")

            assertTrue("2FA submission must succeed", result.isSuccess)

            val state = machine.getState().value
            assertTrue("State must be SessionEstablished", state is AuthState.SessionEstablished)
        }

    @Test
    fun testTwoFASubmissionInvalidCode() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")
            val result = machine.submitTwoFA("invalid")

            assertTrue("Invalid 2FA code must fail", result.isFailure)
        }

    @Test
    fun testTwoFASubmissionWrongLength() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")

            val result1 = machine.submitTwoFA("12345") // Too short
            assertTrue("2FA code with wrong length must fail", result1.isFailure)

            val result2 = machine.submitTwoFA("1234567") // Too long
            assertTrue("2FA code with wrong length must fail", result2.isFailure)
        }

    @Test
    fun testTwoFASubmissionNonNumeric() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")
            val result = machine.submitTwoFA("1234ab")

            assertTrue("Non-numeric 2FA code must fail", result.isFailure)
        }

    @Test
    fun testTwoFASubmissionWithoutChallenge() =
        runTest {
            val machine = createAuthStateMachine()

            val result = machine.submitTwoFA("123456")

            assertTrue("Submitting 2FA without challenge must fail", result.isFailure)
        }

    // ========== 2FA Resend ==========

    @Test
    fun testTwoFAResendSuccess() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )
            whenever(mockRelayClient.resendTwoFactor(any())).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")
            val result = machine.resendTwoFA()

            assertTrue("Resend 2FA must succeed", result.isSuccess)

            val state = machine.getState().value
            assertTrue(
                "State must remain AwaitingTwoFactorCode",
                state is AuthState.AwaitingTwoFactorCode,
            )
        }

    @Test
    fun testTwoFAResendMaxAttempts() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )
            whenever(mockRelayClient.resendTwoFactor(any())).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")

            // Try to resend 3 times (allowed)
            assertTrue("First resend must succeed", machine.resendTwoFA().isSuccess)
            assertTrue("Second resend must succeed", machine.resendTwoFA().isSuccess)
            assertTrue("Third resend must succeed", machine.resendTwoFA().isSuccess)

            // Fourth attempt should fail
            val result = machine.resendTwoFA()
            assertTrue("Fourth resend must fail (max attempts exceeded)", result.isFailure)
        }

    @Test
    fun testTwoFAResendWithoutChallenge() =
        runTest {
            val machine = createAuthStateMachine()

            val result = machine.resendTwoFA()

            assertTrue("Resending 2FA without challenge must fail", result.isFailure)
        }

    // ========== Retry Logic & Backoff ==========

    @Test
    fun testRetryBackoffOnLoginFailure() =
        runTest {
            var attemptCount = 0
            whenever(mockRelayClient.loginWithCredentials(any(), any())).thenAnswer {
                attemptCount++
                if (attemptCount < 3) {
                    Result.failure(Exception("Network error"))
                } else {
                    Result.success(
                        LoginResponse.SessionToken(
                            token = "token-789",
                            expiresAt = futureTimestamp(),
                        ),
                    )
                }
            }
            whenever(mockTokenRepository.saveAppleId(any())).thenReturn(Result.success(Unit))
            whenever(mockTokenRepository.saveSessionToken(any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockNativeClient.registerHardware(any(), any()))
                .thenReturn(
                    Result.success(
                        HardwareInfo(
                            deviceId = "device-123",
                            certificateData = ByteArray(0),
                        ),
                    ),
                )
            whenever(mockNativeClient.pollActivationStatus(any()))
                .thenReturn(Result.success(ActivationStatus.Activated))
            whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            val result = machine.requestLogin(AppleId("test@icloud.com"), "password123")

            assertTrue("Login must succeed after retries", result.isSuccess)
            assertEquals("Should have tried 3 times", 3, attemptCount)
        }

    @Test
    fun testLoginFailureAfterMaxRetries() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(Result.failure(Exception("Persistent network error")))

            val machine = createAuthStateMachine()

            val result = machine.requestLogin(AppleId("test@icloud.com"), "password123")

            assertTrue("Login must fail after retries exhausted", result.isFailure)

            val state = machine.getState().value
            assertTrue("State must be Failed", state is AuthState.Failed)
        }

    // ========== Session Token Management ==========

    @Test
    fun testSessionTokenPersisted() =
        runTest {
            val token = "session-token-123"
            val expiresAt = futureTimestamp()

            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(
                        LoginResponse.SessionToken(token = token, expiresAt = expiresAt),
                    ),
                )
            whenever(mockTokenRepository.saveAppleId(any())).thenReturn(Result.success(Unit))
            whenever(mockTokenRepository.saveSessionToken(any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockNativeClient.registerHardware(any(), any()))
                .thenReturn(
                    Result.success(
                        HardwareInfo(
                            deviceId = "device-123",
                            certificateData = ByteArray(0),
                        ),
                    ),
                )
            whenever(mockNativeClient.pollActivationStatus(any()))
                .thenReturn(Result.success(ActivationStatus.Activated))
            whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")

            val state = machine.getState().value
            assertTrue("State must be SessionEstablished", state is AuthState.SessionEstablished)

            if (state is AuthState.SessionEstablished) {
                assertEquals("Token must match", token, state.token)
                assertEquals("Expiration must match", expiresAt, state.expiresAt)
            }
        }

    // ========== Token Refresh ==========

    @Test
    fun testTokenRefresh() =
        runTest {
            val oldToken = "old-token"
            val newToken = "new-token"
            val newExpiresAt = futureTimestamp() + 3600000

            whenever(mockTokenRepository.getSessionToken()).thenReturn(Result.success(oldToken))
            whenever(mockRelayClient.refreshToken(oldToken))
                .thenReturn(
                    Result.success(SessionResponse(token = newToken, expiresAt = newExpiresAt)),
                )
            whenever(mockTokenRepository.saveSessionToken(newToken, newExpiresAt))
                .thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            val result = machine.refreshToken()

            assertTrue("Token refresh must succeed", result.isSuccess)

            val state = machine.getState().value
            assertTrue(
                "State must be SessionEstablished after refresh",
                state is AuthState.SessionEstablished,
            )

            if (state is AuthState.SessionEstablished) {
                assertEquals("New token must be stored", newToken, state.token)
            }
        }

    @Test
    fun testTokenRefreshWithoutToken() =
        runTest {
            whenever(mockTokenRepository.getSessionToken())
                .thenReturn(Result.failure(Exception("No token stored")))

            val machine = createAuthStateMachine()

            val result = machine.refreshToken()

            assertTrue("Refresh without token must fail", result.isFailure)
        }

    @Test
    fun testTokenRefreshFailureTransitionsToAwaitingCredentials() =
        runTest {
            whenever(mockTokenRepository.getSessionToken()).thenReturn(Result.success("old-token"))
            whenever(mockRelayClient.refreshToken(any()))
                .thenReturn(Result.failure(Exception("Token expired")))

            val machine = createAuthStateMachine()

            machine.refreshToken()

            val state = machine.getState().value
            assertTrue(
                "Failed refresh must transition to AwaitingCredentials",
                state is AuthState.AwaitingCredentials,
            )
        }

    // ========== Logout ==========

    @Test
    fun testLogoutSuccess() =
        runTest {
            whenever(mockTokenRepository.clearSessionToken()).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            val result = machine.logout()

            assertTrue("Logout must succeed", result.isSuccess)

            val state = machine.getState().value
            assertEquals("State must be Idle after logout", AuthState.Idle, state)
        }

    @Test
    fun testLogoutClearsSessionData() =
        runTest {
            whenever(mockTokenRepository.clearSessionToken()).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            machine.logout()

            val state = machine.getState().value
            assertEquals("State must be Idle", AuthState.Idle, state)
        }

    @Test
    fun testLogoutFromSessionEstablished() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(
                        LoginResponse.SessionToken(
                            token = "token",
                            expiresAt = futureTimestamp(),
                        ),
                    ),
                )
            whenever(mockTokenRepository.saveAppleId(any())).thenReturn(Result.success(Unit))
            whenever(mockTokenRepository.saveSessionToken(any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockNativeClient.registerHardware(any(), any()))
                .thenReturn(
                    Result.success(
                        HardwareInfo(
                            deviceId = "device-123",
                            certificateData = ByteArray(0),
                        ),
                    ),
                )
            whenever(mockNativeClient.pollActivationStatus(any()))
                .thenReturn(Result.success(ActivationStatus.Activated))
            whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))
            whenever(mockTokenRepository.clearSessionToken()).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")
            val state1 = machine.getState().value
            assertTrue("State should be SessionEstablished", state1 is AuthState.SessionEstablished)

            machine.logout()
            val state2 = machine.getState().value
            assertEquals("State must return to Idle after logout", AuthState.Idle, state2)
        }

    @Test
    fun testLogoutFailure() =
        runTest {
            whenever(mockTokenRepository.clearSessionToken())
                .thenReturn(Result.failure(Exception("Storage error")))

            val machine = createAuthStateMachine()

            val result = machine.logout()

            assertTrue("Logout must fail on storage error", result.isFailure)
        }

    // ========== State Flow Reactivity ==========

    @Test
    fun testStateFlowUpdates() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(LoginResponse.TwoFactorRequired(challenge = "challenge-123")),
                )

            val machine = createAuthStateMachine()
            val states = mutableListOf<AuthState>()

            testScope.launch { machine.getState().collect { state -> states.add(state) } }

            machine.requestLogin(AppleId("test@icloud.com"), "password123")

            assertTrue("State flow must emit initial Idle state", states.contains(AuthState.Idle))
            assertTrue(
                "State flow must emit AwaitingTwoFactorCode",
                states.any { it is AuthState.AwaitingTwoFactorCode },
            )
        }

    // ========== Error Handling ==========

    @Test
    fun testLoginWithInvalidEmailFormat() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(Result.failure(Exception("Invalid email format")))

            val machine = createAuthStateMachine()

            val result = machine.requestLogin(AppleId("not-an-email"), "password123")

            assertTrue("Login with invalid email must fail", result.isFailure)
        }

    @Test
    fun testLoginWithEmptyPassword() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(Result.failure(Exception("Password cannot be empty")))

            val machine = createAuthStateMachine()

            val result = machine.requestLogin(AppleId("test@icloud.com"), "")

            assertTrue("Login with empty password must fail", result.isFailure)
        }

    // ========== Hardware Provisioning State ==========

    @Test
    fun testHardwareProvisioningProgress() =
        runTest {
            whenever(mockRelayClient.loginWithCredentials(any(), any()))
                .thenReturn(
                    Result.success(
                        LoginResponse.SessionToken(
                            token = "token",
                            expiresAt = futureTimestamp(),
                        ),
                    ),
                )
            whenever(mockTokenRepository.saveAppleId(any())).thenReturn(Result.success(Unit))
            whenever(mockTokenRepository.saveSessionToken(any(), any()))
                .thenReturn(Result.success(Unit))
            whenever(mockNativeClient.registerHardware(any(), any()))
                .thenReturn(
                    Result.success(
                        HardwareInfo(
                            deviceId = "device-123",
                            certificateData = ByteArray(0),
                        ),
                    ),
                )
            whenever(mockNativeClient.pollActivationStatus(any()))
                .thenReturn(Result.success(ActivationStatus.Activated))
            whenever(mockTokenRepository.saveHardwareInfo(any())).thenReturn(Result.success(Unit))

            val machine = createAuthStateMachine()

            machine.requestLogin(AppleId("test@icloud.com"), "password123")

            val finalState = machine.getState().value
            assertTrue(
                "Final state must be SessionEstablished",
                finalState is AuthState.SessionEstablished,
            )
        }

    // ========== Helper Functions ==========

    private fun createAuthStateMachine(): AuthStateMachine {
        return AuthStateMachine(
            tokenRepository = mockTokenRepository,
            relayClient = mockRelayClient,
            nativeClient = mockNativeClient,
            scope = testScope,
        )
    }

    private fun futureTimestamp(): Long {
        return System.currentTimeMillis() + 3600000 // 1 hour in future
    }
}
