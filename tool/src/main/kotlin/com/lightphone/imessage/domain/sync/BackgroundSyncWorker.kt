package com.lightphone.imessage.domain.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

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
     * 4. Update sync metadata
     *
     * @return Result.success() if all steps succeed, Result.retry() for transient errors,
     * ```
     *         Result.failure() for fatal errors
     * ```
     */
    private suspend fun performSync(): Result {
        // These would be injected in production via DI (Hilt, Koin, etc.)
        // For now, we structure the method to show the logic
        // TODO: Inject relayService, messageRepository, tokenRepository via WorkManager parameters
        // or app-wide singleton

        // 1. Check relay connection state
        // if (relayService.connectionState.value !is RelayConnectionState.Connected) {
        //     return Result.retry() // Relay unavailable, will retry
        // }

        // 2. Retry undelivered messages
        // val undeliveredResult = retryUndeliveredMessages()
        // if (!undeliveredResult.isSuccess) {
        //     return Result.retry() // Transient error, retry next period
        // }

        // 3. Request sync from relay (this triggers relay to push pending messages)
        // val syncResult = relayService.requestSync()
        // if (!syncResult.isSuccess) {
        //     return Result.retry() // Transient error, will retry
        // }

        // 4. Update sync timestamp
        // val now = System.currentTimeMillis()
        // val updateResult = tokenRepository.updateLastSync(now)
        // if (!updateResult.isSuccess) {
        //     return Result.failure()
        // }

        return Result.success()
    }

    /**
     * Retry undelivered messages by querying the message repository and resending each message via
     * the relay service.
     *
     * @return Result.success if all retries succeed or no messages to retry,
     * ```
     *         Result.retry if transient error, Result.failure if fatal error
     * ```
     */
    private suspend fun retryUndeliveredMessages(): Result {
        // TODO: Implement when repositories and services are injected
        // val undeliveredMessages = messageRepository.getUndeliveredMessages().first()
        // for (message in undeliveredMessages) {
        //     val envelope = message.rawEnvelope ?: continue
        //     val outgoing = OutgoingMessage(
        //         recipient = message.sender,
        //         payload = envelope,
        //         messageId = MessageId(message.id)
        //     )
        //     val sendResult = relayService.sendMessage(outgoing)
        //     if (!sendResult.isSuccess) {
        //         return Result.retry() // Transient error
        //     }
        //     // Update message status to delivered
        //     messageRepository.markAsDelivered(message.id, System.currentTimeMillis())
        // }
        return Result.success()
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
