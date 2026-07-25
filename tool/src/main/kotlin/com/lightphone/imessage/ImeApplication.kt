package com.lightphone.imessage

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.repository.IMessageRepository
import com.lightphone.imessage.di.AppWorkerFactory
import com.lightphone.imessage.domain.codec.IMessageCodec
import com.lightphone.imessage.domain.relay.IRelayService
import okhttp3.OkHttpClient

/**
 * Application lifecycle entry point. Initializes application-wide dependencies:
 * - OkHttpClient singleton (for relay communication)
 * - ImessageDatabase singleton (persistent message store)
 * - WorkManager with custom WorkerFactory (dependency injection for background workers)
 *
 * Spec: F-1 (Application DI); ADR-008 (WorkManager).
 */
class ImeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ImeApplication.onCreate() called")

        // Initialize OkHttpClient singleton (no interceptors yet; see F-8 for pinning)
        val okHttpClient = createOkHttpClient()
        Log.d(TAG, "OkHttpClient singleton initialized")

        // Initialize ImessageDatabase singleton
        val database = ImessageDatabase.getInstance(applicationContext)
        Log.d(TAG, "ImessageDatabase singleton initialized")

        // Initialize lazy DI dependencies (will be wired when available)
        // TODO(F-4): messageCodec and crypto deps (senderCert, recipientKey) are blocked on
        //   AuthManager wiring. Once AuthManager is ready, inject these into AppWorkerFactory so
        //   PushProcessingWorker can process incoming encrypted messages.
        val messageCodec: IMessageCodec? = null
        val senderCert: java.security.cert.X509Certificate? = null
        val recipientKey: java.security.PrivateKey? = null
        // TODO(F-5): messageRepository will be wired via repository layer impl
        val messageRepository: IMessageRepository? = null
        // TODO: relayService will be wired via relay layer impl
        val relayService: com.lightphone.imessage.domain.relay.IRelayService? = null

        // Initialize WorkManager with custom WorkerFactory for dependency injection
        val config =
            Configuration.Builder()
                .setWorkerFactory(
                    AppWorkerFactory(
                        database = database,
                        messageCodec = messageCodec,
                        senderCert = senderCert,
                        recipientKey = recipientKey,
                        messageRepository = messageRepository,
                        relayService = relayService,
                    )
                )
                .build()

        WorkManager.initialize(applicationContext, config)
        Log.d(TAG, "WorkManager initialized with custom WorkerFactory")
    }

    /**
     * Create OkHttpClient singleton.
     *
     * Currently uses default configuration. Certificate pinning and other security measures will
     * be added later (F-8) when relay endpoints are finalized.
     *
     * @return OkHttpClient instance
     */
    private fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // No custom interceptors yet; can be added for pinning, logging, etc.
            .build()
    }

    companion object {
        private const val TAG = "ImeApplication"
    }
}
