package com.lightphone.imessage.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Service managing IPC connection to rustpush native service.
 * Handles Unix socket framing, heartbeat (PING/PONG), and message dispatch.
 */
class RustpushService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "RustpushService bound")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "RustpushService started")
        // TODO: Initialize rustpush native library, establish IPC connection
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RustpushService destroyed")
        // TODO: Close IPC connection, cleanup
    }

    companion object {
        private const val TAG = "RustpushService"
    }
}
