package com.lightphone.imessage.domain.native

import kotlinx.coroutines.flow.StateFlow

/**
 * Client interface for communicating with native service via Unix domain socket IPC. Implements
 * length-prefixed JSON message framing, correlation ID request-response matching, reconnection with
 * exponential backoff, and keepalive heartbeat.
 *
 * Spec: milestone-2.md § 4.4 (Native Push Notification), § 4.3 (Device Activation).
 */
interface INativeServiceClient {
    /**
     * Current connection state as a StateFlow for reactive UI updates. Initial value is
     * Disconnected.
     */
    val connectionState: StateFlow<NativeServiceState>

    /**
     * Establish Unix domain socket connection to native service at /dev/socket/rustpush_ipc
     * (configurable). Emits state = Connecting immediately, then Connected on socket open. On
     * failure, retries with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s).
     *
     * @return Result.success if socket opens; Result.failure if all retries exhausted
     */
    suspend fun connect(): Result<Unit>

    /**
     * Close socket connection cleanly. Emits state = Disconnected. Cancels any pending reconnect
     * timers and keepalive heartbeat.
     *
     * @return Result.success
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * Register hardware with native service. Sends RegisterHardware IPC message with hardware
     * information and waits for response containing device ID.
     *
     * @param hwInfo Hardware information as raw bytes (format defined by native service)
     * @return Result.success with device ID string; Result.failure if timeout, parse error, or
     * socket not connected
     */
    suspend fun registerHardware(hwInfo: ByteArray): Result<String>

    /**
     * Poll native service for hardware activation status. Returns ActivationStatus sealed class
     * indicating activated, pending, or failed state with relevant metadata.
     *
     * @param deviceId Device ID previously returned by registerHardware
     * @return Result.success with ActivationStatus variant; Result.failure if timeout or socket
     * error
     */
    suspend fun pollActivationStatus(deviceId: String): Result<ActivationStatus>

    /**
     * Handle incoming push notification from UnifiedPush distributor. Forwards payload to native
     * service asynchronously (non-blocking). Returns immediately; errors logged but not returned.
     *
     * @param payload Encrypted push payload bytes from UnifiedPush
     * @return Result.success if queued; Result.failure if queue full (>100 messages)
     */
    suspend fun handlePushNotification(payload: ByteArray): Result<Unit>
}
