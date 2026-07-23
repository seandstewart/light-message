package com.lightphone.imessage.domain.relay

/**
 * WebSocket connection state for relay server communication. Follows state machine defined in
 * milestone-2.md § 5.2.
 */
sealed class RelayConnectionState {
    /** No active connection. Initial state. */
    object Disconnected : RelayConnectionState()

    /** Connection attempt in progress. */
    object Connecting : RelayConnectionState()

    /** WebSocket is open and ready to send/receive. */
    object Connected : RelayConnectionState()

    /**
     * Permanent failure after all retries exhausted.
     * @param error Human-readable error message
     */
    data class Failed(val error: String) : RelayConnectionState()

    /**
     * Waiting before retry attempt after connection failure.
     * @param attempt Retry attempt number (1-based)
     * @param nextRetryIn Milliseconds until next retry
     */
    data class Reconnecting(val attempt: Int, val nextRetryIn: Long) : RelayConnectionState()
}
