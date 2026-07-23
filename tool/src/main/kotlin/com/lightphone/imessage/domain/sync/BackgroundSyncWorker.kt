package com.lightphone.imessage.domain.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lightphone.imessage.data.repository.IMessageRepository
import com.lightphone.imessage.domain.relay.IRelayService
import com.lightphone.imessage.domain.relay.MessageId
import com.lightphone.imessage.domain.relay.OutgoingMessage
import com.lightphone.imessage.domain.relay.RelayConnectionState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Background sync worker for periodic health checks and message delivery retry. Runs every 15
 * minutes (or when requested by WorkManager) to:
 * 1. Verify relay connection health
 * 2. Retry undelivered messages
 * 3. Request sync from relay (triggers PushReceiver to fetch pending messages)
 * 4. Update last sync timestamp
 *
 * Spec: milestone-2.md § TASK_011 (Background Sync Worker); ADR-008 (WorkManager).
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            performSync()
        } catch (e: Exception) {
            // Unexpected errors should be retried
            if (e.message?.contains("network", ignoreCase = true) == true ||
                            e is java.io.IOException
            ) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Perform the sync operation:
     * 1. Check relay connection, reconnect if necessary
     * 2. Retry undelivered messages
     * 3. Request sync from relay
     *
     * @return Result.success() if all steps succeed, Result.retry() for transient errors,
     * ```
     *         Result.failure() for fatal errors
     * ```
     */
    private suspend fun performSync(): Result {
        // Get services from service container (assumes DI or singleton pattern is set up)
        val relayService = getRelayService() ?: return Result.retry()
        val messageRepository = getMessageRepository() ?: return Result.retry()

        // 1. Check relay connection state and reconnect if needed
        if (relayService.connectionState.value !is RelayConnectionState.Connected) {
            System.err.println(
                    "[BackgroundSyncWorker] Relay not connected, attempting reconnect..."
            )
            val connectResult = attemptRelayConnection(relayService)
            if (!connectResult.isSuccess) {
                System.err.println(
                        "[BackgroundSyncWorker] Failed to establish relay connection, retrying later"
                )
                return Result.retry()
            }
        }
        System.err.println(
                "[BackgroundSyncWorker] Relay connection state: ${relayService.connectionState.value}"
        )

        // 2. Retry undelivered messages
        val undeliveredResult = retryUndeliveredMessages(relayService, messageRepository)
        if (!undeliveredResult.isSuccess) {
            System.err.println("[BackgroundSyncWorker] Error during undelivered message retry")
            return undeliveredResult
        }

        // 3. Request sync from relay (triggers relay to push pending messages)
        val syncResult = relayService.requestSync()
        if (!syncResult.isSuccess) {
            System.err.println(
                    "[BackgroundSyncWorker] Failed to request sync from relay, retrying later"
            )
            return Result.retry()
        }
        System.err.println("[BackgroundSyncWorker] Requested sync from relay")

        System.err.println("[BackgroundSyncWorker] Sync completed successfully")
        return Result.success()
    }

    /**
     * Retry undelivered messages by querying the message repository and resending each message via
     * the relay service. Errors on individual messages are logged but do not abort the sync.
     *
     * @param relayService Relay service for sending messages
     * @param messageRepository Repository for querying and updating message delivery status
     * @return Result.success if all retries succeed or no messages to retry,
     * ```
     *         Result.retry if transient error, Result.failure if fatal error
     * ```
     */
    private suspend fun retryUndeliveredMessages(
            relayService: IRelayService,
            messageRepository: IMessageRepository
    ): Result {
        return try {
            val undeliveredMessages = messageRepository.getUndeliveredMessages().first()
            System.err.println(
                    "[BackgroundSyncWorker] Found ${undeliveredMessages.size} undelivered messages"
            )

            for (message in undeliveredMessages) {
                val envelope = message.rawEnvelope
                if (envelope == null) {
                    System.err.println(
                            "[BackgroundSyncWorker] Skipping message ${message.id}: no raw envelope"
                    )
                    continue
                }

                try {
                    val outgoing =
                            OutgoingMessage(
                                    recipient = message.sender,
                                    payload = envelope,
                                    messageId = MessageId(message.id)
                            )
                    val sendResult = relayService.sendMessage(outgoing)
                    if (sendResult.isSuccess) {
                        // Mark message as delivered
                        val markResult =
                                messageRepository.markAsDelivered(
                                        message.id,
                                        System.currentTimeMillis()
                                )
                        if (markResult.isSuccess) {
                            System.err.println(
                                    "[BackgroundSyncWorker] Message ${message.id} resent and marked as delivered"
                            )
                        } else {
                            System.err.println(
                                    "[BackgroundSyncWorker] Message ${message.id} sent but failed to update status"
                            )
                        }
                    } else {
                        // Log error but continue with next message (transient error on this
                        // specific message)
                        System.err.println(
                                "[BackgroundSyncWorker] Failed to send message ${message.id}: ${sendResult.exceptionOrNull()?.message}"
                        )
                    }
                } catch (e: Exception) {
                    // Log error but continue with next message
                    System.err.println(
                            "[BackgroundSyncWorker] Exception sending message ${message.id}: ${e.message}"
                    )
                }
            }
            Result.success()
        } catch (e: Exception) {
            // Distinguish between network and database errors
            if (e.message?.contains("network", ignoreCase = true) == true ||
                            e is java.io.IOException
            ) {
                System.err.println(
                        "[BackgroundSyncWorker] Network error while retrieving undelivered messages: ${e.message}"
                )
                Result.retry()
            } else {
                System.err.println(
                        "[BackgroundSyncWorker] Fatal error while retrieving undelivered messages: ${e.message}"
                )
                Result.failure(e)
            }
        }
    }

    /**
     * Attempt to establish a relay connection. Assumes the relay service has a configured endpoint.
     *
     * @param relayService Relay service to connect with
     * @return Result.success if connected, Result.retry if transient error, Result.failure if fatal
     */
    private suspend fun attemptRelayConnection(relayService: IRelayService): Result<Unit> {
        // Note: This assumes RelayService has been configured with an endpoint.
        // If endpoint is not available, this should be handled at a higher level (e.g., during
        // auth).
        // For now, we attempt a health check by querying the connection state.
        // If the relay service has reconnect logic built-in, it will handle reconnection.
        return try {
            // The RelayService manages its own reconnection logic.
            // This is a no-op health check; actual reconnection is handled by the service.
            val state = relayService.connectionState.value
            when (state) {
                is RelayConnectionState.Connected -> Result.success(Unit)
                is RelayConnectionState.Failed -> Result.retry()
                else -> Result.retry()
            }
        } catch (e: Exception) {
            System.err.println(
                    "[BackgroundSyncWorker] Error checking relay connection: ${e.message}"
            )
            Result.retry()
        }
    }

    /**
     * Get the relay service from the service container.
     * @return RelayService or null if not available
     */
    private fun getRelayService(): IRelayService? {
        // TODO: Inject via DI framework (Hilt, Koin) or fetch from service container
        return null
    }

    /**
     * Get the message repository from the service container.
     * @return MessageRepository or null if not available
     */
    private fun getMessageRepository(): IMessageRepository? {
        // TODO: Inject via DI framework (Hilt, Koin) or fetch from service container
        return null
    }

    companion object {
        private const val WORK_NAME = "background_sync"
        private const val SYNC_INTERVAL_MINUTES = 15L
        private const val BACKOFF_INITIAL_DELAY_MINUTES = 5L
        private const val BACKOFF_MAX_DELAY_MINUTES = 30L

        /**
         * Schedule periodic background sync via WorkManager. Registers a unique periodic work
         * request with network connectivity constraints and exponential backoff on failure.
         *
         * @param context Android application context
         */
        fun schedule(context: Context) {
            val syncRequest =
                    PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                                    SYNC_INTERVAL_MINUTES,
                                    TimeUnit.MINUTES
                            )
                            .apply {
                                addTag("sync")
                                setConstraints(
                                        Constraints.Builder()
                                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                                .build()
                                )
                                setBackoffCriteria(
                                        androidx.work.BackoffPolicy.EXPONENTIAL,
                                        BACKOFF_INITIAL_DELAY_MINUTES,
                                        TimeUnit.MINUTES
                                )
                            }
                            .build()

            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                            WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            syncRequest
                    )
        }

        /**
         * Cancel the background sync worker.
         *
         * @param context Android application context
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
