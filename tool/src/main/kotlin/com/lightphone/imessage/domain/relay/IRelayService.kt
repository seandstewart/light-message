package com.lightphone.imessage.domain.relay

import kotlinx.coroutines.flow.StateFlow

/**
 * Service interface for communicating with relay server via WebSocket. Implements command queueing,
 * reconnect backoff, and message framing. Spec: milestone-2.md § 5.2 (Relay Connection).
 */
interface IRelayService {
    /**
     * Current connection state as a StateFlow for reactive UI updates. Initial value is
     * Disconnected.
     */
    val connectionState: StateFlow<RelayConnectionState>

    /**
     * Establish WebSocket connection to relay server. Emits state = Connecting immediately, then
     * Connected on open. On failure, retries with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s).
     *
     * @param endpoint Relay server URL and Bearer token
     * @return Result.success if connection opens; Result.failure if all retries exhausted
     */
    suspend fun connect(endpoint: RelayEndpoint): Result<Unit>

    /**
     * Close WebSocket connection cleanly. Emits state = Disconnected. Cancels any pending reconnect
     * timers.
     *
     * @return Result.success
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * Send a message through the relay. Queues the message if not connected; sends immediately if
     * connected.
     *
     * @param message Outgoing message with recipient, payload, and messageId
     * @return Result.success with messageId if queued/sent; Result.failure if service not ready
     */
    suspend fun sendMessage(message: OutgoingMessage): Result<MessageId>

    /**
     * Request full sync from relay (used after reconnect or by background worker). Queues
     * RequestSync command to relay.
     *
     * @return Result.success if command queued; Result.failure if not connected
     */
    suspend fun requestSync(): Result<Unit>
}
