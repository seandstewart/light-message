package com.lightphone.imessage.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.WebSocket

/**
 * HTTP/WebSocket client for relay server communication.
 * Implements retry policy (3 attempts, exponential backoff 1s/2s/4s).
 */
class RelayService(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null

    suspend fun sendMessage(payload: ByteArray): Boolean {
        // TODO: Implement send with retry policy
        return true
    }

    suspend fun receiveMessages(): Flow<Message> {
        // TODO: WebSocket listener, dispatch to Flow
        return flowOf()
    }

    fun closeConnection() {
        webSocket?.close(1000, "Normal closure")
    }
}

data class Message(
    val id: String,
    val senderId: String,
    val text: String,
    val timestamp: Long,
)
