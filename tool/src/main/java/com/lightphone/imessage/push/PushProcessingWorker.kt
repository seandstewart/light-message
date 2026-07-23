package com.lightphone.imessage.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lightphone.imessage.data.ImessageDatabase
import com.lightphone.imessage.data.MessageEntity
import com.lightphone.imessage.domain.relay.IMessageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * WorkManager worker that processes incoming push messages:
 * 1. Deduplicates against 30-second window
 * 2. Decrypts envelope
 * 3. Persists MessageEntity to Room database
 * 4. Sends ACK to relay (if supported)
 *
 * Spec: milestone-2.md § 4.3 (Native Push Notification)
 */
class PushProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
    private val database: ImessageDatabase = ImessageDatabase.getInstance(appContext),
    private val messageCodec: IMessageCodec? = null, // TODO: Inject actual codec
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                // Extract input data
                val messageId =
                    inputData.getString("messageId") ?: return@withContext Result.failure()
                val sender =
                    inputData.getString("sender") ?: return@withContext Result.failure()
                val timestamp = inputData.getLong("timestamp", 0)
                val envelope =
                    inputData.getByteArray("envelope")
                        ?: return@withContext Result.failure()

                Log.d(TAG, "Processing push: messageId=$messageId, sender=$sender")

                // 1. Check for duplicates (30s window)
                val existingMessage = database.messageDao().getMessageById(messageId)
                if (existingMessage != null) {
                    val ageMs = System.currentTimeMillis() - existingMessage.timestamp
                    if (ageMs < DEDUP_WINDOW_MS) {
                        Log.d(TAG, "Duplicate message (age=${ageMs}ms): $messageId")
                        return@withContext Result.success()
                    }
                }

                // 2. Derive threadId from participants (deterministic)
                val deviceAddress = "+" // TODO: Get device address from AuthManager
                val threadId = deriveThreadId(sender, deviceAddress)

                // 3. Decrypt envelope
                val payloadBytes =
                    messageCodec?.decodeEnvelope(envelope)?.getOrNull()
                        ?: run {
                            Log.e(TAG, "Failed to decrypt envelope: $messageId")
                            return@withContext Result.retry()
                        }

                // Parse decrypted payload to extract body and other metadata
                val decryptedPayload =
                    try {
                        Json.decodeFromString(
                            DecryptedPayloadDto.serializer(),
                            String(payloadBytes),
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse decrypted payload: ${e.message}", e)
                        return@withContext Result.failure()
                    }

                // 4. Create MessageEntity
                val messageEntity =
                    MessageEntity(
                        id = messageId,
                        threadId = threadId,
                        sender = sender,
                        body = decryptedPayload.body,
                        timestamp = timestamp,
                        type = 0, // TEXT
                        isOutgoing = false,
                        status = STATUS_DELIVERED,
                        attachmentCount = 0,
                        rawEnvelope = envelope,
                    )

                // 5. Persist to database
                database.messageDao().insertMessage(messageEntity)
                Log.d(TAG, "Persisted message: $messageId")

                // 6. Send ACK to relay (if protocol requires)
                sendAckToRelay(messageId) // TODO: Implement when relay ACK protocol is defined

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error processing push: ${e.message}", e)
                Result.retry()
            }
        }

    /**
     * Derive deterministic threadId from two participants.
     *
     * Participants are sorted alphabetically and hashed to ensure consistent ID regardless of
     * message direction.
     *
     * @param sender First participant (iMessage address)
     * @param recipient Second participant (iMessage address)
     * @return Deterministic thread ID (UUIDv5 namespace + participants)
     */
    private fun deriveThreadId(
        sender: String,
        recipient: String,
    ): String {
        // Sort participants alphabetically for determinism
        val participants = listOf(sender, recipient).sorted()
        val combined = participants.joinToString("|")

        // Use UUIDv5 (SHA-1 based) with fixed namespace to ensure reproducibility
        // For now, use a simple hash to generate a stable UUID
        val hashCode = combined.hashCode().toLong()
        return UUID.nameUUIDFromBytes(combined.toByteArray()).toString()
    }

    /**
     * Send ACK back to relay (placeholder for future implementation).
     *
     * @param messageId Message ID to acknowledge
     */
    private fun sendAckToRelay(messageId: String) {
        // TODO: Send ACK via RelayService when protocol is defined
        Log.d(TAG, "ACK sent for: $messageId (placeholder)")
    }

    /**
     * DTO for decrypted message payload (parsed from plaintext after decryption).
     *
     * Structure matches iMessage message encoding.
     */
    @Serializable
    private data class DecryptedPayloadDto(
        val body: String,
        val type: Int = 0, // TEXT
    )

    companion object {
        private const val TAG = "PushProcessingWorker"

        // Message status constants (from milestone-2.md)
        private const val STATUS_DRAFT = 0
        private const val STATUS_ENCRYPTED = 1
        private const val STATUS_SENT = 2
        private const val STATUS_DELIVERED = 3
        private const val STATUS_READ = 4
        private const val STATUS_FAILED = 5

        // Deduplication window (30 seconds)
        private const val DEDUP_WINDOW_MS = 30_000L
    }
}
