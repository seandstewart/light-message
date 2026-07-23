package com.lightphone.imessage.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lightphone.imessage.domain.push.PushMessage
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * BroadcastReceiver for UnifiedPush notifications from rustpush.
 *
 * Responsibilities:
 * 1. Extract and parse JSON payload from UnifiedPush intent
 * 2. Deduplicate against 30-second window
 * 3. Enqueue decryption and persistence via WorkManager
 *
 * Does NOT block in onReceive(); queues async work to avoid ANR.
 *
 * Spec: milestone-2.md § 4.3 (Native Push Notification)
 */
class PushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Push received: ${intent.action}")

        // Handle registration result (for debugging)
        if (intent.action == ACTION_REGISTRATION_RESULT) {
            Log.d(TAG, "Registration result: ${intent.extras}")
            return
        }

        // Extract UnifiedPush payload
        val payload =
                intent.getStringExtra("message")
                        ?: run {
                            Log.w(TAG, "Received push with no message payload")
                            return
                        }

        // Parse JSON payload
        val pushMessage =
                try {
                    parsePushPayload(payload)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse push payload: ${e.message}", e)
                    return
                }

        // Enqueue async processing via WorkManager
        enqueuePushProcessing(context, pushMessage)
    }

    /**
     * Parse JSON payload into PushMessage data class.
     *
     * Payload format (from rustpush):
     * ```json
     * {
     *   "message_id": "uuid",
     *   "sender": "user@icloud.com",
     *   "timestamp": 1234567890,
     *   "envelope": "base64-encoded-encrypted-bytes"
     * }
     * ```
     *
     * @param json JSON string
     * @return Parsed PushMessage
     * @throws Exception on JSON parsing error
     */
    private fun parsePushPayload(json: String): PushMessage {
        val dto = Json.decodeFromString(PushPayloadDto.serializer(), json)

        // Decode base64 envelope
        val envelopeBytes =
                Base64.decode(dto.envelope, Base64.DEFAULT)
                        ?: throw IllegalArgumentException("Invalid base64 envelope")

        return PushMessage(
                messageId = dto.message_id,
                sender = dto.sender,
                timestamp = dto.timestamp,
                envelope = envelopeBytes
        )
    }

    /**
     * Enqueue PushProcessingWorker to handle decryption, deduplication, and persistence. Uses
     * WorkManager to avoid ANR and handle retries.
     */
    private fun enqueuePushProcessing(context: Context, pushMessage: PushMessage) {
        val workData =
                Data.Builder()
                        .putString("messageId", pushMessage.messageId)
                        .putString("sender", pushMessage.sender)
                        .putLong("timestamp", pushMessage.timestamp)
                        .putByteArray("envelope", pushMessage.envelope)
                        .build()

        val constraints =
                Constraints.Builder()
                        // Don't require network for local persistence
                        .build()

        val pushProcessingRequest =
                OneTimeWorkRequestBuilder<PushProcessingWorker>()
                        .setInputData(workData)
                        .setConstraints(constraints)
                        .setInitialDelay(0, TimeUnit.SECONDS)
                        .addTag(WORK_TAG_PUSH_PROCESSING)
                        .build()

        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        "push_${pushMessage.messageId}",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        pushProcessingRequest
                )

        Log.d(TAG, "Enqueued push processing: ${pushMessage.messageId}")
    }

    /** DTO for JSON payload deserialization (matches rustpush format). */
    @Serializable
    private data class PushPayloadDto(
            val message_id: String,
            val sender: String,
            val timestamp: Long,
            val envelope: String // base64
    )

    companion object {
        private const val TAG = "PushReceiver"
        private const val ACTION_REGISTRATION_RESULT =
                "org.unifiedpush.android.distributor.REGISTRATION_RESULT"
        private const val WORK_TAG_PUSH_PROCESSING = "push_processing"
    }
}
