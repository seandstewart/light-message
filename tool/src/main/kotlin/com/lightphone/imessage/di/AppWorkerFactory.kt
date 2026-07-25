package com.lightphone.imessage.di

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.repository.IMessageRepository
import com.lightphone.imessage.domain.codec.IMessageCodec
import com.lightphone.imessage.domain.relay.IRelayService
import com.lightphone.imessage.domain.sync.BackgroundSyncWorker
import com.lightphone.imessage.push.PushProcessingWorker
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Custom WorkerFactory that injects dependencies into workers created by WorkManager.
 *
 * Handles instantiation of:
 * - [PushProcessingWorker] — processes incoming push messages
 * - [BackgroundSyncWorker] — periodic health checks and message delivery retry
 *
 * For unknown worker classes, returns null to allow WorkManager to use its default factory
 * (which will attempt no-arg or context-params constructor fallback).
 *
 * Spec: ADR-008 (WorkManager); F-1 (Application DI).
 */
class AppWorkerFactory(
    private val database: ImessageDatabase,
    private val messageCodec: IMessageCodec?,
    private val senderCert: X509Certificate?,
    private val recipientKey: PrivateKey?,
    private val messageRepository: IMessageRepository?,
    private val relayService: IRelayService?,
) : WorkerFactory() {
    override fun createWorker(
        context: Context,
        workerClassName: String,
        params: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            PushProcessingWorker::class.java.name -> {
                Log.d(TAG, "Creating PushProcessingWorker with injected dependencies")
                if (messageCodec == null || senderCert == null || recipientKey == null) {
                    Log.e(
                        TAG,
                        "Cannot create PushProcessingWorker: messageCodec, senderCert, or recipientKey not available"
                    )
                    return null
                }
                PushProcessingWorker(
                    appContext = context,
                    params = params,
                    database = database,
                    messageCodec = messageCodec,
                    senderCert = senderCert,
                    recipientKey = recipientKey,
                )
            }

            BackgroundSyncWorker::class.java.name -> {
                Log.d(TAG, "Creating BackgroundSyncWorker with injected dependencies")
                if (relayService == null || messageRepository == null) {
                    Log.e(
                        TAG,
                        "Cannot create BackgroundSyncWorker: relayService or messageRepository not available"
                    )
                    return null
                }
                BackgroundSyncWorker(
                    context = context,
                    params = params,
                    relayService = relayService,
                    messageRepository = messageRepository,
                )
            }

            else -> {
                Log.w(TAG, "Unknown worker class: $workerClassName, returning null for system fallback")
                null
            }
        }
    }

    companion object {
        private const val TAG = "AppWorkerFactory"
    }
}
