package com.lightphone.imessage.push

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.MessageEntity
import com.lightphone.imessage.data.entity.ThreadEntity
import com.lightphone.imessage.domain.auth.AuthManager
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
    private val database: ImessageDatabase,
    private val messageCodec: IMessageCodec,
    private val senderCert: X509Certificate,
    private val recipientKey: PrivateKey,
    private val authManager: AuthManager? = null,
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

            // 2. All crypto dependencies (codec + sender cert + recipient private key) are now
            //    guaranteed to be non-null via AppWorkerFactory.createWorker() injection.
            //    Assert to catch any factory misconfiguration during development.
            require(messageCodec != null) { "MessageCodec must be injected by AppWorkerFactory" }
            require(senderCert != null) { "Sender cert must be injected by AppWorkerFactory" }
            require(recipientKey != null) { "Recipient key must be injected by AppWorkerFactory" }
            val codec = messageCodec

            // 3. Decrypt envelope. decodeEnvelope now returns a MessagePayload directly — the
            //    body is a first-class field, so no JSON re-parse is needed.
            val payload =
                messageCodec.decodeEnvelope(envelope, senderCert, recipientKey).getOrElse { e ->
                    Log.e(TAG, "Failed to decode envelope for $messageId", e)
                    return Result.failure()
                }

            // 4. Derive threadId from sender and device address (own phone number).
            //    If the device address is not yet available from AuthManager (auth not complete),
            //    fall back to sender-only derivation with a warning.
            val deviceAddress = authManager?.getDeviceAddress()
            val threadId = if (deviceAddress != null) {
                Log.d(TAG, "Deriving threadId with both sender and device address")
                deriveThreadId(sender, deviceAddress)
            } else {
                Log.w(
                    TAG,
                    "Device address not available from AuthManager; deriving threadId from sender only",
                )
                deriveThreadId(sender)
            }

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
                    val participantUris = if (deviceAddress != null) {
                        "$sender|$deviceAddress"
                    } else {
                        sender
                    }
                    threadDao.insert(
                        ThreadEntity(
                            id = threadId,
                            title = sender,
                            lastMessage = payload.body,
                            lastTimestamp = timestamp,
                            participantUris = participantUris,
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
