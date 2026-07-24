package com.lightphone.imessage.push

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.MessageEntity
import com.lightphone.imessage.data.entity.ThreadEntity
import com.lightphone.imessage.domain.codec.IMessageCodec
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeoutException

/**
 * WorkManager worker that processes incoming push messages:
 * 1. Existence-based dedup against the local DB
 * 2. Decrypts the envelope via [IMessageCodec]
 * 3. Persists [MessageEntity] (and upserts the parent [ThreadEntity]) in a single Room transaction
 * 4. Sends ACK to the relay (placeholder)
 *
 * TODO: WorkManager's default [androidx.work.WorkerFactory] does NOT honor the constructor defaults
 * for [database] and [messageCodec] — they will always be re-instantiated (or left null) unless a
 * custom `WorkerFactory` is registered via `WorkManager.initialize(...)`. Wire one up so real
 * dependencies (including the codec, which is blocked on AuthManager) can be injected.
 *
 * Spec: milestone-2.md § 4.3 (Native Push Notification).
 */
class PushProcessingWorker(
        appContext: Context,
        params: WorkerParameters,
        private val database: ImessageDatabase = ImessageDatabase.getInstance(appContext),
        private val messageCodec: IMessageCodec? = null,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Bound WorkManager retries — otherwise transient failures cause retry storms.
        if (runAttemptCount >= RETRY_LIMIT) {
            Log.e(TAG, "Retry limit ($RETRY_LIMIT) exceeded; failing push permanently")
            return Result.failure()
        }

        val messageId = inputData.getString("messageId") ?: return Result.failure()
        val sender = inputData.getString("sender") ?: return Result.failure()
        val timestamp = inputData.getLong("timestamp", 0)
        val envelope = inputData.getByteArray("envelope") ?: return Result.failure()

        Log.d(TAG, "Processing push: messageId=$messageId, sender=$sender")

        return try {
            // 1. Existence-based dedup — if the message already lives in the DB, drop the push.
            if (database.messageDao().existsById(messageId)) {
                Log.d(TAG, "Duplicate message dropped: $messageId")
                return Result.success()
            }

            // 2. Fail permanently if the crypto path isn't wired yet. All three inputs
            //    (codec + sender cert + recipient private key) are gated on AuthManager, so
            //    a null on any of them means the feature isn't ready and retrying can't help.
            val codec =
                    messageCodec
                            ?: run {
                                Log.e(
                                        TAG,
                                        "MessageCodec not wired; failing push $messageId permanently",
                                )
                                return Result.failure()
                            }
            val senderCert =
                    loadSenderCert(sender)
                            ?: run {
                                Log.e(
                                        TAG,
                                        "Sender cert unavailable (AuthManager not wired); failing $messageId permanently",
                                )
                                return Result.failure()
                            }
            val recipientKey =
                    loadRecipientKey()
                            ?: run {
                                Log.e(
                                        TAG,
                                        "Recipient key unavailable (AuthManager not wired); failing $messageId permanently",
                                )
                                return Result.failure()
                            }

            // 3. Decrypt envelope. decodeEnvelope now returns a MessagePayload directly — the
            //    body is a first-class field, so no JSON re-parse is needed.
            val payload =
                    codec.decodeEnvelope(envelope, senderCert, recipientKey).getOrElse { e ->
                        Log.e(TAG, "Failed to decode envelope for $messageId", e)
                        return Result.failure()
                    }

            // 4. Derive threadId from sender only.
            //    TODO(BLOCKED_ON=AuthManager): include the device address as a second participant
            //    once AuthManager exposes it. Until then, single-participant threads keep
            //    conversations from collapsing into a single bucket keyed by the placeholder "+".
            Log.w(
                    TAG,
                    "deriveThreadId called with sender only; device address pending AuthManager wiring",
            )
            val threadId = deriveThreadId(sender)

            // 5. Build the persisted entity.
            val messageEntity =
                    MessageEntity(
                            id = messageId,
                            threadId = threadId,
                            sender = sender,
                            body = payload.body,
                            timestamp = timestamp,
                            type = 0, // TEXT
                            isOutgoing = false,
                            status = STATUS_DELIVERED,
                            attachmentCount = 0,
                            rawEnvelope = envelope,
                    )

            // 6. Persist the message and upsert the parent thread in a single transaction so a
            //    partial write can't leave the DB in a state where the message row references
            //    a missing thread row (FK) or the thread preview drifts from the last message.
            database.withTransaction {
                val threadDao = database.threadDao()
                if (threadDao.existsById(threadId)) {
                    threadDao.updateLastMessage(threadId, payload.body, timestamp)
                } else {
                    threadDao.insert(
                            ThreadEntity(
                                    id = threadId,
                                    title = sender,
                                    lastMessage = payload.body,
                                    lastTimestamp = timestamp,
                                    participantUris = sender,
                            ),
                    )
                }
                database.messageDao().insert(messageEntity)
            }

            Log.d(TAG, "Persisted message: $messageId")

            // 7. Send ACK to relay (placeholder for future implementation).
            sendAckToRelay(messageId)

            Result.success()
        } catch (e: IOException) {
            Log.w(
                    TAG,
                    "Transient error processing push $messageId: ${e.javaClass.simpleName}",
                    e,
            )
            if (runAttemptCount >= RETRY_LIMIT) Result.failure() else Result.retry()
        } catch (e: TimeoutException) {
            Log.w(
                    TAG,
                    "Transient error processing push $messageId: ${e.javaClass.simpleName}",
                    e,
            )
            if (runAttemptCount >= RETRY_LIMIT) Result.failure() else Result.retry()
        } catch (e: Exception) {
            // Anything else (SerializationException, NPE, SQLiteConstraintException, …) is
            // structurally permanent — retrying will just re-hit the same fault.
            Log.e(TAG, "Permanent error processing push $messageId", e)
            Result.failure()
        }
    }

    /**
     * Derive a deterministic threadId from one or more participant URIs.
     *
     * Participants are sorted alphabetically before hashing so the ID is stable regardless of
     * message direction. Accepts 1..N participants — single-participant threads are used until the
     * device address is available via AuthManager.
     */
    private fun deriveThreadId(vararg participants: String): String {
        require(participants.isNotEmpty()) { "deriveThreadId requires at least one participant" }
        val combined = participants.sorted().joinToString("|")
        return UUID.nameUUIDFromBytes(combined.toByteArray()).toString()
    }

    /** TODO(BLOCKED_ON=AuthManager): resolve the verified X.509 cert for [sender]. */
    @Suppress("UNUSED_PARAMETER")
    private fun loadSenderCert(sender: String): X509Certificate? = null

    /** TODO(BLOCKED_ON=AuthManager): return the device's RSA-2048 private key. */
    private fun loadRecipientKey(): PrivateKey? = null

    /** Send ACK back to relay (placeholder for future implementation). */
    private fun sendAckToRelay(messageId: String) {
        // TODO: Send ACK via RelayService when protocol is defined
        Log.d(TAG, "ACK sent for: $messageId (placeholder)")
    }

    companion object {
        private const val TAG = "PushProcessingWorker"

        // Message status constants (from milestone-2.md)
        internal const val STATUS_DRAFT = 0
        internal const val STATUS_ENCRYPTED = 1
        internal const val STATUS_SENT = 2
        internal const val STATUS_DELIVERED = 3
        internal const val STATUS_READ = 4
        internal const val STATUS_FAILED = 5

        /** Maximum WorkManager retry attempts before failing permanently. */
        internal const val RETRY_LIMIT = 5
    }
}
