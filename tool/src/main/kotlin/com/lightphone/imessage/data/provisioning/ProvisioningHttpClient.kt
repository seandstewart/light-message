package com.lightphone.imessage.data.provisioning

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp-based implementation of IProvisioningClient.
 *
 * Communicates with the Apple provisioning server to:
 * 1. Register device hardware with relay after successful 2FA
 * 2. Poll for device activation status
 *
 * Uses Kotlin serialization for JSON marshalling. All methods are suspending and return Result<T>.
 * Auth tokens are redacted from logs to prevent credential leaks.
 */
class ProvisioningHttpClient(private val okHttpClient: OkHttpClient) : IProvisioningClient {

    override suspend fun registerHardware(
        sessionToken: String,
        email: String,
    ): Result<HardwareInfo> {
        return try {
            Log.d(TAG, "Registering hardware for email: $email")

            val requestBody = RegisterHardwareRequest(email = email)
            val jsonBody = Json.encodeToString(RegisterHardwareRequest.serializer(), requestBody)
            val mediaType = "application/json".toMediaType()

            val request =
                Request.Builder()
                    .url("$BASE_URL/provisioning/register-hardware")
                    .header("Authorization", "Bearer $sessionToken")
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
                                RegisterHardwareResponse.serializer(),
                                body,
                            )
                        Log.d(TAG, "Successfully registered hardware: ${result.deviceId}")
                        Result.success(
                            HardwareInfo(
                                deviceId = result.deviceId,
                                certificateData = result.certificateData.toByteArray(),
                            )
                        )
                    } else {
                        Log.e(TAG, "registerHardware: empty response body")
                        Result.failure(Exception("Empty response body"))
                    }
                }

                else -> {
                    val errorMsg = response.body?.string() ?: "Unknown error"
                    Log.e(
                        TAG,
                        "registerHardware failed with status ${response.code}: ${redactAuth(errorMsg)}",
                    )
                    Result.failure(
                        Exception(
                            "Failed to register hardware: HTTP ${response.code} - $errorMsg"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerHardware exception: ${redactAuth(e.message ?: "unknown")}", e)
            Result.failure(e)
        }
    }

    override suspend fun pollActivationStatus(
        deviceId: String,
        maxAttempts: Int,
        pollIntervalMs: Long,
    ): Result<ActivationStatus> {
        return try {
            Log.d(TAG, "Polling activation status for device: $deviceId (max $maxAttempts attempts)")

            var lastStatus: ActivationStatus = ActivationStatus.Pending

            for (attempt in 0 until maxAttempts) {
                try {
                    val request =
                        Request.Builder()
                            .url("$BASE_URL/provisioning/activation-status?device_id=$deviceId")
                            .get()
                            .build()

                    val response = okHttpClient.newCall(request).execute()

                    when {
                        response.isSuccessful -> {
                            val body = response.body?.string()
                            if (body != null) {
                                val result =
                                    Json.decodeFromString(
                                        ActivationStatusResponse.serializer(),
                                        body,
                                    )
                                when (result.status) {
                                    "activated" -> {
                                        Log.d(
                                            TAG,
                                            "Device activation completed after $attempt attempts",
                                        )
                                        return Result.success(ActivationStatus.Activated)
                                    }

                                    "pending" -> {
                                        Log.d(TAG, "Device activation pending (attempt ${attempt + 1})")
                                        lastStatus = ActivationStatus.Pending
                                    }

                                    "failed" -> {
                                        val reason = result.reason ?: "Unknown failure"
                                        Log.e(TAG, "Device activation failed: $reason")
                                        return Result.success(
                                            ActivationStatus.Failed(reason)
                                        )
                                    }

                                    else -> {
                                        Log.w(TAG, "Unknown activation status: ${result.status}")
                                        lastStatus = ActivationStatus.Pending
                                    }
                                }
                            }
                        }

                        response.code == 404 -> {
                            // Device not found yet; keep polling
                            Log.d(TAG, "Device not found yet (attempt ${attempt + 1}/$maxAttempts)")
                            lastStatus = ActivationStatus.Pending
                        }

                        else -> {
                            val errorMsg = response.body?.string() ?: "Unknown error"
                            Log.w(
                                TAG,
                                "pollActivationStatus returned HTTP ${response.code}: ${redactAuth(errorMsg)}",
                            )
                            lastStatus = ActivationStatus.Pending
                        }
                    }

                    // Delay before next attempt (except on last attempt)
                    if (attempt < maxAttempts - 1) {
                        delay(pollIntervalMs)
                    }
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "pollActivationStatus attempt ${attempt + 1} failed: ${redactAuth(e.message ?: "unknown")}",
                    )
                    lastStatus = ActivationStatus.Pending
                    if (attempt < maxAttempts - 1) {
                        delay(pollIntervalMs)
                    }
                }
            }

            // All polling attempts exhausted
            Log.e(TAG, "Device activation polling exhausted ($maxAttempts attempts)")
            Result.success(lastStatus)
        } catch (e: Exception) {
            Log.e(TAG, "pollActivationStatus exception: ${redactAuth(e.message ?: "unknown")}", e)
            Result.failure(e)
        }
    }

    private companion object {
        private const val TAG = "ProvisioningHttpClient"
        private const val BASE_URL = "https://api.apple.com"

        private val AUTH_REGEX = Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s\"]+")

        /** Redact bearer tokens and sensitive auth data from a log message. */
        private fun redactAuth(msg: String): String = msg.replace(AUTH_REGEX, "$1***")
    }
}

/** Request DTO for hardware registration. */
@Serializable
private data class RegisterHardwareRequest(
    val email: String,
)

/** Response DTO for hardware registration. */
@Serializable
private data class RegisterHardwareResponse(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("certificate_data")
    val certificateData: String,
)

/** Response DTO for activation status polling. */
@Serializable
private data class ActivationStatusResponse(
    val status: String, // "activated", "pending", "failed"
    val reason: String? = null,
)
