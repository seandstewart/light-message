package com.lightphone.imessage.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives UnifiedPush notifications forwarded from rustpush.
 * Dispatches to MessageRepository for persistence and UI update.
 */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Push received: ${intent.action}")
        // TODO: Deserialize payload, route to MessageRepository
    }

    companion object {
        private const val TAG = "PushReceiver"
    }
}
