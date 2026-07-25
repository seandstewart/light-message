package com.lightphone.imessage.data.relay

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp-based implementation of IRelayHttpClient.
 *
 * Communicates with the Apple relay (iMessage) servers to:
 * 1. Authenticate with Apple ID credentials
 * 2. Handle 2FA challenges and code submission
 * 3. Refresh expired session tokens
 *
 * Uses Kotlin serialization for JSON marshalling. All methods are suspending and return Result<T>.
 * Auth tokens are redacted from logs to prevent credential leaks.
 */
class RelayHttpClient(private val okHttpClient: OkHttpClient) : IRelayHttpClient {

    override suspend fun loginWithCredentials(
        email: String,
        password: String,
    ): Result<LoginResponse> {
        return try {
            Log.d(TAG, "Logging in with credentials for email: $email")

            val requestBody = LoginRequest(email = email, password = password)
            val jsonBody = Json.encodeToString(LoginRequest.serializer(), requestBody)
            val mediaType = "application/json".toMediaType()

            val request =
                Request.Builder()
                    .url("$BASE_URL/relay/login")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody(mediaType))
                    .build()

            val response = okHttpClient.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                    if (body != null) {
                        val result = Json.decodeFromString(LoginResponseData.serializer(), body)

                        if (result.challenge != null) {
                            Log.d(TAG, "2FA required for email: $email")
                            Result.success(LoginResponse.TwoFactorRequired(result.challenge))
                        } else if (result.token != null && result.expiresAt != null) {
                            Log.d(TAG, "Login successful, received session token for: $email")
                            Result.success(
                                LoginResponse.SessionToken(
                                    token = result.token,
                                    expiresAt = result.expiresAt,
                                )
                            )
                        } else {
                            Log.e(TAG, "loginWithCredentials: invalid response structure")
                            Result.failure(Exception("Invalid login response structure"))
                        }
                    } else {
                        Log.e(TAG, "loginWithCredentials: empty response body")
                        Result.failure(Exception("Empty response body"))
                    }
                }

                else -> {
                    val errorMsg = response.body?.string() ?: "Unknown error"
                    Log.e(
                        TAG,
                        "loginWithCredentials failed with status ${response.code}: ${redactAuth(errorMsg)}",
                    )
                    Result.failure(
                        Exception(
                            "Failed to login: HTTP ${response.code} - $errorMsg"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loginWithCredentials exception: ${redactAuth(e.message ?: "unknown")}", e)
            Result.failure(e)
        }
    }

    override suspend fun submitTwoFactor(
        challenge: String,
        code: String,
    ): Result<SessionResponse> {
        return try {
            Log.d(TAG, "Submitting 2FA code")

            val requestBody = TwoFactorRequest(challenge = challenge, code = code)
            val jsonBody = Json.encodeToString(TwoFactorRequest.serializer(), requestBody)
            val mediaType = "application/json".toMediaType()

            val request =
                Request.Builder()
                    .url("$BASE_URL/relay/verify-2fa")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody(mediaType))
                    .build()

            val response = okHttpClient.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                    if (body != null) {
                        val result =
                            Json.decodeFromString(
                                SessionResponseData.serializer(),
                                body,
                            )
                        Log.d(TAG, "2FA verification successful, received session token")
                        Result.success(
                            SessionResponse(
                                token = result.token,
                                expiresAt = result.expiresAt,
                            )
                        )
                    } else {
                        Log.e(TAG, "submitTwoFactor: empty response body")
                        Result.failure(Exception("Empty response body"))
                    }
                }

                else -> {
                    val errorMsg = response.body?.string() ?: "Unknown error"
                    Log.e(
                        TAG,
                        "submitTwoFactor failed with status ${response.code}: ${redactAuth(errorMsg)}",
                    )
                    Result.failure(
                        Exception(
                            "Failed to verify 2FA: HTTP ${response.code} - $errorMsg"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitTwoFactor exception: ${redactAuth(e.message ?: "unknown")}", e)
            Result.failure(e)
        }
    }

    override suspend fun resendTwoFactor(challenge: String): Result<Unit> {
        return try {
            Log.d(TAG, "Requesting 2FA code resend")

            val requestBody = ResendTwoFactorRequest(challenge = challenge)
            val jsonBody = Json.encodeToString(ResendTwoFactorRequest.serializer(), requestBody)
            val mediaType = "application/json".toMediaType()

            val request =
                Request.Builder()
                    .url("$BASE_URL/relay/resend-2fa")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody(mediaType))
                    .build()

            val response = okHttpClient.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    Log.d(TAG, "2FA code resend successful")
                    Result.success(Unit)
                }

                else -> {
                    val errorMsg = response.body?.string() ?: "Unknown error"
                    Log.e(
                        TAG,
                        "resendTwoFactor failed with status ${response.code}: ${redactAuth(errorMsg)}",
                    )
                    Result.failure(
                        Exception(
                            "Failed to resend 2FA: HTTP ${response.code} - $errorMsg"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resendTwoFactor exception: ${redactAuth(e.message ?: "unknown")}", e)
            Result.failure(e)
        }
    }

    override suspend fun refreshToken(token: String): Result<SessionResponse> {
        return try {
            Log.d(TAG, "Refreshing session token")

            val request =
                Request.Builder()
                    .url("$BASE_URL/relay/refresh-token")
                    .header("Authorization", "Bearer $token")
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()

            val response = okHttpClient.newCall(request).execute()

            when {
                response.isSuccessful -> {
                    val body = response.body?.string()
                    if (body != null) {
                        val result =
                            Json.decodeFromString(
                                SessionResponseData.serializer(),
                                body,
                            )
                        Log.d(TAG, "Token refresh successful, received new session token")
                        Result.success(
                            SessionResponse(
                                token = result.token,
                                expiresAt = result.expiresAt,
                            )
                        )
                    } else {
                        Log.e(TAG, "refreshToken: empty response body")
                        Result.failure(Exception("Empty response body"))
                    }
                }

                else -> {
                    val errorMsg = response.body?.string() ?: "Unknown error"
                    Log.e(
                        TAG,
                        "refreshToken failed with status ${response.code}: ${redactAuth(errorMsg)}",
                    )
                    Result.failure(
                        Exception(
                            "Failed to refresh token: HTTP ${response.code} - $errorMsg"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshToken exception: ${redactAuth(e.message ?: "unknown")}", e)
            Result.failure(e)
        }
    }

    private companion object {
        private const val TAG = "RelayHttpClient"
        private const val BASE_URL = "https://relay.apple.com"

        private val AUTH_REGEX = Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s\"]+")

        /** Redact bearer tokens and sensitive auth data from a log message. */
        private fun redactAuth(msg: String): String = msg.replace(AUTH_REGEX, "$1***")
    }
}

/** Request DTO for login with credentials. */
@Serializable
private data class LoginRequest(
    val email: String,
    val password: String,
)

/** Response DTO for login. */
@Serializable
private data class LoginResponseData(
    val challenge: String? = null,
    val token: String? = null,
    @SerialName("expires_at")
    val expiresAt: Long? = null,
)

/** Request DTO for 2FA submission. */
@Serializable
private data class TwoFactorRequest(
    val challenge: String,
    val code: String,
)

/** Response DTO for 2FA and token refresh. */
@Serializable
private data class SessionResponseData(
    val token: String,
    @SerialName("expires_at")
    val expiresAt: Long,
)

/** Request DTO for 2FA code resend. */
@Serializable
private data class ResendTwoFactorRequest(
    val challenge: String,
)
