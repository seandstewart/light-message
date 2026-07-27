package com.lightphone.imessage.domain.relay.policy

import android.util.Log
import com.lightphone.imessage.data.entity.MessageEntity
import com.lightphone.imessage.data.repository.MessageRepository
import com.lightphone.imessage.domain.relay.MessageAckPolicy
import com.lightphone.imessage.domain.relay.RelayCommand
import kotlinx.coroutines.CancellationException

/**
 * MessageAckPolicy implementation that ensures messages are persisted to the local database
 * before sending an acknowledgment to the relay server.
 *
 * **Design (F-2: persist-then-ack):**
 * 1. Receive message from relay WebSocket (RelayCommand.SendMessage)
 * 2. Extract message ID, recipient, and encrypted envelope
 * 3. Insert into MessageRepository as a new incoming message (status=RECEIVED, isOutgoing=false)
 * 4. On persist success → return true (caller sends ACK)
 * 5. On persist failure → return false (caller skips ACK, relay retries after timeout)
 *
 * This prevents message loss if a crash occurs between ack and persist: if the process dies
 * before persist completes, the relay will timeout waiting for the ack and resend the message.
 *
 * **Error Handling:**
 * - Logs error details on persist failure
 * - Returns false on exception so relay doesn't drop the message
 * - CancellationException is re-thrown (coroutine cancellation should propagate)
 */
class PersistThenAckPolicy(private val messageRepository: MessageRepository) : MessageAckPolicy {
    private companion object {
        private const val TAG = "PersistThenAckPolicy"

        // Message status constants (from milestone-2.md)
        private const val STATUS_RECEIVED = 0 // Incoming message, not yet processed
    }

    override suspend fun onIncomingMessage(cmd: RelayCommand.SendMessage): Boolean {
        Log.d(TAG, "onIncomingMessage: messageId=${cmd.messageId.value}")

        return try {
            // Step 1: Create a MessageEntity from the incoming command
            val messageEntity =
                MessageEntity(
                    id = cmd.messageId.value,
                    threadId = cmd.recipientUri, // Use recipient as thread ID for now
                    sender = cmd.recipientUri, // Relay doesn't provide sender; use recipient
                    body = "", // Body is encrypted in rawEnvelope; leave empty for now
                    timestamp = System.currentTimeMillis(),
                    type = 0, // Message type (text = 0)
                    isOutgoing = false, // This is an incoming message
                    status = STATUS_RECEIVED,
                    rawEnvelope = cmd.envelope,
                )

            // Step 2: Persist to database (must succeed before returning true)
            Log.d(TAG, "Persisting message ${cmd.messageId.value} to repository...")
            val persistResult = messageRepository.insertMessage(messageEntity)

            // Step 3: Check result
            val success = persistResult.isSuccess
            if (success) {
                Log.d(TAG, "Successfully persisted message ${cmd.messageId.value}; will ACK")
            } else {
                val error = persistResult.exceptionOrNull()
                Log.e(TAG, "Failed to persist message ${cmd.messageId.value}: ${error?.message}", error)
            }

            // Return true only if persist succeeded
            success
        } catch (e: CancellationException) {
            // Propagate coroutine cancellation
            throw e
        } catch (e: Exception) {
            // Unexpected error during persist
            Log.e(TAG, "Unexpected error in onIncomingMessage: ${e.message}", e)
            false
        }
    }
}
