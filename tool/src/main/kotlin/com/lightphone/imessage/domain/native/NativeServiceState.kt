package com.lightphone.imessage.domain.native

/**
 * Unix domain socket connection state for native service IPC. Follows state machine defined in
 * milestone-2.md.
 */
sealed class NativeServiceState {
    /** No active connection. Initial state. */
    object Disconnected : NativeServiceState()

    /** Connection attempt in progress. */
    object Connecting : NativeServiceState()

    /** Unix domain socket is open and ready to send/receive. */
    object Connected : NativeServiceState()

    /**
     * Permanent failure after all retries exhausted.
     * @param error Human-readable error message
     */
    data class Failed(val error: String) : NativeServiceState()

    /**
     * Waiting before retry attempt after connection failure.
     * @param attempt Retry attempt number (1-based)
     * @param nextRetryIn Milliseconds until next retry
     */
    data class Reconnecting(val attempt: Int, val nextRetryIn: Long) : NativeServiceState()
}

/**
 * Activation status response from native service. Represents the result of polling for hardware
 * activation.
 */
sealed class ActivationStatus {
    /**
     * Hardware is activated and ready for use.
     * @param deviceId Unique device identifier from native service
     * @param publicKey RSA public key for device
     */
    data class Activated(val deviceId: String, val publicKey: java.security.PublicKey) :
        ActivationStatus()

    /**
     * Hardware activation is pending. Caller should retry after nextPollIn milliseconds.
     * @param attempt Current poll attempt number
     * @param nextPollIn Milliseconds until next recommended poll
     */
    data class Pending(val attempt: Int, val nextPollIn: Long) : ActivationStatus()

    /**
     * Activation failed permanently.
     * @param error Human-readable error message
     */
    data class Failed(val error: String) : ActivationStatus()
}
