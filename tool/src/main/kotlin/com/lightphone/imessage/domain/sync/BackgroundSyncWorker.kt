package com.lightphone.imessage.domain.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result as WorkerResult
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
import kotlinx.coroutines.flow.firstOrNull

/**
 * Background sync worker for periodic health checks and message delivery retry. Runs every 15
 * minutes (or when requested by WorkManager) to:
 * 1. Verify relay connection health
 * 2. Retry undelivered messages
 * 3. Request sync from relay (triggers PushReceiver to fetch pending messages)
 * 4. Update last sync timestamp
 *
 * Spec: milestone-2.md § TASK_011 (Background Sync Worker); ADR-008 (WorkManager).
 *
 * TODO: A custom [androidx.work.WorkerFactory] must be registered with WorkManager to inject
 * [IRelayService] and [IMessageRepository]. Until then, [getRelayService] and
 * [getMessageRepository] return null and the worker fails fast (returns [WorkerResult.failure])
 * rather than retrying forever and burning battery.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkerResult {
        return try {
            performSync()
        } catch (e: Exception) {
            // Unexpected errors: retry transient (network/IO) failures, fail permanent ones.
            if (isTransient(e)) {
                Log.w(TAG, "Transient error during sync, retrying", e)
                WorkerResult.retry()
            } else {
                Log.e(TAG, "Fatal error during sync", e)
                WorkerResult.failure(errorData(e))
            }
        }
    }

    /**
     * Perform the sync operation:
     * 1. Check relay connection, reconnect if necessary
     * 2. Retry undelivered messages
     * 3. Request sync from relay
     *
     * @return WorkerResult.success() if all steps succeed, WorkerResult.retry() for transient
     * errors, WorkerResult.failure() for fatal errors
     */
    private suspend fun performSync(): WorkerResult {
        // DI not yet wired: fail fast rather than retry forever and burn battery.
        val relayService =
                getRelayService()
                        ?: run {
                            Log.e(
                                    TAG,
                                    "RelayService DI not wired; failing sync permanently until WorkerFactory is registered",
                            )
                            return WorkerResult.failure()
                        }
        val messageRepository =
                getMessageRepository()
                        ?: run {
                            Log.e(
                                    TAG,
                                    "MessageRepository DI not wired; failing sync permanently until WorkerFactory is registered",
                            )
                            return WorkerResult.failure()
                        }

        // 1. Check relay connection state and reconnect if needed
        if (relayService.connectionState.value !is RelayConnectionState.Connected) {
            Log.w(TAG, "Relay not connected, checking connection state...")
            val connectResult = attemptRelayConnection(relayService)
            if (connectResult !is WorkerResult.Success) {
                Log.w(TAG, "Failed to establish relay connection, retrying later")
                return connectResult
            }
        }
        Log.i(TAG, "Relay connection state: ${relayService.connectionState.value}")

        // 2. Retry undelivered messages
        val undeliveredResult = retryUndeliveredMessages(relayService, messageRepository)
        if (undeliveredResult !is WorkerResult.Success) {
            Log.w(TAG, "Error during undelivered message retry")
            return undeliveredResult
        }

        // 3. Request sync from relay (triggers relay to push pending messages)
        val syncResult = relayService.requestSync()
        if (syncResult.isFailure) {
            Log.w(
                    TAG,
                    "Failed to request sync from relay, retrying later",
                    syncResult.exceptionOrNull()
            )
            return WorkerResult.retry()
        }
        Log.i(TAG, "Requested sync from relay")

        Log.i(TAG, "Sync completed successfully")
        return WorkerResult.success()
    }

    /**
     * Retry undelivered messages by querying the message repository and resending each message via
     * the relay service. Errors on individual messages are logged but do not abort the sync.
     *
     * @param relayService Relay service for sending messages
     * @param messageRepository Repository for querying and updating message delivery status
     * @return WorkerResult.success if all retries succeed or no messages to retry,
     * WorkerResult.retry if transient error, WorkerResult.failure if fatal error
     */
    private suspend fun retryUndeliveredMessages(
            relayService: IRelayService,
            messageRepository: IMessageRepository,
    ): WorkerResult {
        return try {
            // firstOrNull() guards against a flow that never emits (safer than first()).
            val undeliveredMessages =
                    messageRepository.getUndeliveredMessages().firstOrNull() ?: emptyList()
            Log.i(TAG, "Found ${undeliveredMessages.size} undelivered messages")

            for (message in undeliveredMessages) {
                val envelope = message.rawEnvelope
                if (envelope == null) {
                    Log.w(TAG, "Skipping message ${message.id}: no raw envelope")
                    continue
                }

                try {
                    val outgoing =
                            OutgoingMessage(
                                    recipient = message.sender,
                                    payload = envelope,
                                    messageId = MessageId(message.id),
                            )
                    val sendResult = relayService.sendMessage(outgoing)
                    if (sendResult.isSuccess) {
                        // Mark message as delivered
                        val markResult =
                                messageRepository.markAsDelivered(
                                        message.id,
                                        System.currentTimeMillis(),
                                )
                        if (markResult.isSuccess) {
                            Log.i(TAG, "Message ${message.id} resent and marked as delivered")
                        } else {
                            Log.w(
                                    TAG,
                                    "Message ${message.id} sent but failed to update status",
                                    markResult.exceptionOrNull(),
                            )
                        }
                    } else {
                        // Log error but continue with next message (transient error on this
                        // specific message)
                        Log.w(
                                TAG,
                                "Failed to send message ${message.id}",
                                sendResult.exceptionOrNull(),
                        )
                    }
                } catch (e: Exception) {
                    // Log error but continue with next message
                    Log.w(TAG, "Exception sending message ${message.id}", e)
                }
            }
            WorkerResult.success()
        } catch (e: Exception) {
            // Distinguish between transient (network/IO) and fatal errors by type.
            if (isTransient(e)) {
                Log.w(TAG, "Transient error while retrieving undelivered messages", e)
                WorkerResult.retry()
            } else {
                Log.e(TAG, "Fatal error while retrieving undelivered messages", e)
                WorkerResult.failure(errorData(e))
            }
        }
    }

    /**
     * Health-check the relay connection. The [IRelayService.connect] method requires a
     * [com.lightphone.imessage.domain.relay.RelayEndpoint] which this worker does not have access
     * to; reconnection with backoff is already handled internally by [IRelayService] on failure.
     * Here we simply observe the current state and translate it to a worker result.
     *
     * @param relayService Relay service whose connection state is queried
     * @return WorkerResult.success if connected, WorkerResult.retry otherwise
     */
    private suspend fun attemptRelayConnection(relayService: IRelayService): WorkerResult {
        return try {
            when (relayService.connectionState.value) {
                is RelayConnectionState.Connected -> WorkerResult.success()
                is RelayConnectionState.Failed -> WorkerResult.retry()
                else -> WorkerResult.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking relay connection", e)
            WorkerResult.retry()
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

    /**
     * Classify a throwable as transient (retryable) vs permanent. Uses type checks rather than
     * fragile message-substring matching so localized exception messages don't break
     * classification.
     */
    private fun isTransient(e: Throwable): Boolean =
            when (e) {
                is java.net.SocketTimeoutException,
                is java.net.UnknownHostException,
                is java.io.IOException,
                is java.util.concurrent.TimeoutException, -> true
                else -> false
            }

    private fun errorData(e: Throwable): Data =
            Data.Builder().putString(KEY_ERROR, e.message ?: e::class.java.simpleName).build()

    companion object {
        private const val TAG = "BackgroundSyncWorker"
        private const val WORK_NAME = "background_sync"
        private const val SYNC_INTERVAL_MINUTES = 15L
        private const val BACKOFF_INITIAL_DELAY_MINUTES = 5L
        private const val BACKOFF_MAX_DELAY_MINUTES = 30L
        private const val KEY_ERROR = "error"

        /**
         * Schedule periodic background sync via WorkManager. Registers a unique periodic work
         * request with network connectivity constraints and exponential backoff on failure.
         *
         * Constraints tradeoff: product wants periodic sync, but `setRequiresBatteryNotLow(true)`
         * prevents draining a nearly-empty battery. Device-idle is explicitly not required so the
         * worker can run while the user is actively using the phone.
         *
         * Uses `ExistingPeriodicWorkPolicy.UPDATE` so future changes to
         * interval/constraints/backoff propagate to existing schedules on app updates instead of
         * being ignored.
         *
         * @param context Android application context
         */
        fun schedule(context: Context) {
            val syncRequest =
                    PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                                    SYNC_INTERVAL_MINUTES,
                                    TimeUnit.MINUTES,
                            )
                            .apply {
                                addTag("sync")
                                setConstraints(
                                        Constraints.Builder()
                                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                                .setRequiresBatteryNotLow(true)
                                                .setRequiresDeviceIdle(false)
                                                .build(),
                                )
                                setBackoffCriteria(
                                        androidx.work.BackoffPolicy.EXPONENTIAL,
                                        BACKOFF_INITIAL_DELAY_MINUTES,
                                        TimeUnit.MINUTES,
                                )
                            }
                            .build()

            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                            WORK_NAME,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            syncRequest,
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
