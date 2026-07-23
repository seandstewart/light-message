package com.lightphone.imessage.domain.native

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Unix domain socket IPC client for native service communication. Implements length-prefixed JSON
 * message framing, request-response correlation via UUID, heartbeat ping/pong, and reconnection
 * with exponential backoff.
 *
 * Protocol: 4-byte big-endian length prefix + UTF-8 JSON payload. Max frame size 1 MB, queue depth
 * ≤100 messages. Heartbeat every 30s with 5s pong timeout. All IPC calls timeout at 10s.
 *
 * Spec: milestone-2.md § 4.3 (Device Activation), § 4.4 (Push Notification), § 6.3 (IPC Framing), §
 * 6.4 (Reconnect Backoff).
 */
class NativeServiceClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val socketPath: String = "/dev/socket/rustpush_ipc",
) : INativeServiceClient {
    private val _connectionState =
        MutableStateFlow<NativeServiceState>(NativeServiceState.Disconnected)
    override val connectionState: StateFlow<NativeServiceState> = _connectionState

    private var ipcSocket: LocalSocket? = null
    private val ipcQueue: MutableList<IpcMessage> = mutableListOf()
    private val pendingRequests: MutableMap<String, CompletableFuture<IpcMessage>> = mutableMapOf()
    private val queueMutex = Mutex()
    private val socketMutex = Mutex()
    private val pendingRequestsMutex = Mutex()
    private val reconnectPolicy: ReconnectPolicy =
        ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1000)

    private var reconnectAttempt = 0
    private var keepaliveJob: Job? = null
    private var pingTimeoutJob: Job? = null
    private var readLoopJob: Job? = null
    private var reconnectJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        private const val TAG = "NativeServiceClient"
        private const val MAX_FRAME_SIZE = 1024 * 1024 // 1 MB
        private const val MAX_QUEUE_DEPTH = 100
        private const val IPC_TIMEOUT_MS = 10_000L // 10 seconds
        private const val HEARTBEAT_INTERVAL_MS = 30_000L // 30 seconds
        private const val PONG_TIMEOUT_MS = 5_000L // 5 seconds
    }

    override suspend fun connect(): Result<Unit> {
        return try {
            _connectionState.emit(NativeServiceState.Connecting)
            reconnectAttempt = 0
            performConnect()
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.emit(NativeServiceState.Failed(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return try {
            reconnectJob?.cancel()
            keepaliveJob?.cancel()
            pingTimeoutJob?.cancel()
            readLoopJob?.cancel()

            socketMutex.withLock {
                ipcSocket?.close()
                ipcSocket = null
            }

            _connectionState.emit(NativeServiceState.Disconnected)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun registerHardware(hwInfo: ByteArray): Result<String> {
        return try {
            val correlationId = UUID.randomUUID().toString()
            val message =
                IpcMessage(
                    correlationId = correlationId,
                    command = "register_hardware",
                    payload =
                        Base64.getEncoder()
                            .encodeToString(hwInfo)
                            .toByteArray(StandardCharsets.UTF_8),
                    timestamp = System.currentTimeMillis(),
                )

            val response =
                withTimeoutOrNull(IPC_TIMEOUT_MS) { sendMessage(message) }
                    ?: return Result.failure(IOException("IPC timeout"))

            // Parse response: { "device_id": "..." }
            val responseJson = String(response.payload, StandardCharsets.UTF_8)
            val dto = json.decodeFromString(RegisterHardwareResponseDto.serializer(), responseJson)
            Result.success(dto.device_id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pollActivationStatus(deviceId: String): Result<ActivationStatus> {
        return try {
            val correlationId = UUID.randomUUID().toString()
            val message =
                IpcMessage(
                    correlationId = correlationId,
                    command = "poll_activation",
                    payload = deviceId.toByteArray(StandardCharsets.UTF_8),
                    timestamp = System.currentTimeMillis(),
                )

            val response =
                withTimeoutOrNull(IPC_TIMEOUT_MS) { sendMessage(message) }
                    ?: return Result.failure(IOException("IPC timeout"))

            // Parse response: { "status": "activated|pending|failed", ... }
            val responseJson = String(response.payload, StandardCharsets.UTF_8)
            val dto = json.decodeFromString(ActivationStatusDto.serializer(), responseJson)

            val status =
                when (dto.status) {
                    "activated" -> {
                        // Decode public key from base64
                        val keyBytes = Base64.getDecoder().decode(dto.public_key ?: "")
                        val spec = X509EncodedKeySpec(keyBytes)
                        val factory = KeyFactory.getInstance("RSA")
                        val publicKey = factory.generatePublic(spec)
                        ActivationStatus.Activated(deviceId, publicKey)
                    }
                    "pending" ->
                        ActivationStatus.Pending(dto.attempt ?: 0, dto.next_poll_in ?: 0)
                    "failed" -> ActivationStatus.Failed(dto.error ?: "Unknown error")
                    else ->
                        return Result.failure(
                            IOException("Unknown activation status: ${dto.status}"),
                        )
                }

            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun handlePushNotification(payload: ByteArray): Result<Unit> {
        return try {
            queueMutex.withLock {
                if (ipcQueue.size >= MAX_QUEUE_DEPTH) {
                    return Result.failure(
                        IOException("IPC queue full (>${MAX_QUEUE_DEPTH} messages)"),
                    )
                }

                val message =
                    IpcMessage(
                        correlationId = UUID.randomUUID().toString(),
                        command = "push_notification",
                        payload =
                            Base64.getEncoder()
                                .encodeToString(payload)
                                .toByteArray(StandardCharsets.UTF_8),
                        timestamp = System.currentTimeMillis(),
                    )

                ipcQueue.add(message)
            }

            // Non-blocking: dispatch send in background
            scope.launch { drainQueue() }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Perform Unix domain socket connection with retry logic. */
    private suspend fun performConnect() {
        if (!reconnectPolicy.shouldRetry(reconnectAttempt)) {
            val error = "Max reconnect attempts (${reconnectPolicy.maxAttempts}) exhausted"
            _connectionState.emit(NativeServiceState.Failed(error))
            return
        }

        try {
            val socket = LocalSocket()
            val address = LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)

            socket.connect(address)
            socketMutex.withLock { ipcSocket = socket }

            scope.launch {
                _connectionState.emit(NativeServiceState.Connected)
                reconnectAttempt = 0

                // Drain any queued messages
                drainQueue()

                // Start heartbeat
                startHeartbeat()

                // Start read loop
                startReadLoop()
            }
        } catch (e: Exception) {
            onSocketFailure(e)
        }
    }

    /** Called when socket connection fails. Triggers reconnection with backoff. */
    private suspend fun onSocketFailure(t: Throwable) {
        keepaliveJob?.cancel()
        pingTimeoutJob?.cancel()
        readLoopJob?.cancel()

        socketMutex.withLock {
            ipcSocket?.close()
            ipcSocket = null
        }

        if (reconnectPolicy.shouldRetry(reconnectAttempt)) {
            val delayMs = reconnectPolicy.getDelayMs(reconnectAttempt)
            val nextAttempt = reconnectAttempt + 1

            _connectionState.emit(
                NativeServiceState.Reconnecting(attempt = nextAttempt, nextRetryIn = delayMs),
            )

            reconnectAttempt = nextAttempt
            delay(delayMs)

            if (_connectionState.value is NativeServiceState.Reconnecting) {
                performConnect()
            }
        } else {
            val error = "Socket failure after ${reconnectPolicy.maxAttempts} attempts: ${t.message}"
            _connectionState.emit(NativeServiceState.Failed(error))
        }
    }

    /** Send IPC message and wait for response with matching correlationId (with timeout). */
    private suspend fun sendMessage(msg: IpcMessage): IpcMessage {
        val responseFuture = CompletableFuture<IpcMessage>()
        pendingRequestsMutex.withLock { pendingRequests[msg.correlationId] = responseFuture }

        try {
            writeFrame(msg)
            return responseFuture.get() as IpcMessage
        } finally {
            pendingRequestsMutex.withLock { pendingRequests.remove(msg.correlationId) }
        }
    }

    /** Write IPC frame to socket: 4-byte big-endian length + JSON payload. */
    private suspend fun writeFrame(msg: IpcMessage) =
        socketMutex.withLock {
            val socket = ipcSocket ?: throw IOException("Socket not connected")

            val jsonString = serializeMessage(msg)
            val jsonBytes = jsonString.toByteArray(StandardCharsets.UTF_8)

            if (jsonBytes.size > MAX_FRAME_SIZE) {
                throw IOException("Message too large: ${jsonBytes.size} > $MAX_FRAME_SIZE")
            }

            val frame = frameData(jsonBytes)
            socket.outputStream?.write(frame)
            socket.outputStream?.flush()
        }

    /** Read IPC frame from socket: 4-byte big-endian length + JSON payload. */
    private suspend fun readFrame(): IpcMessage =
        socketMutex.withLock {
            val socket = ipcSocket ?: throw IOException("Socket not connected")
            val input = socket.inputStream ?: throw IOException("No input stream")

            val lenBytes = ByteArray(4)
            input.readNBytes(lenBytes, 0, 4)
            if (lenBytes.isEmpty()) {
                throw IOException("Socket closed or read failed")
            }

            val length = lenBytes.toInt()
            if (length <= 0 || length > MAX_FRAME_SIZE) {
                throw IOException("Invalid frame length: $length")
            }

            val payload = ByteArray(length)
            input.readNBytes(payload, 0, length)

            val json = String(payload, StandardCharsets.UTF_8)
            deserializeMessage(json)
        }

    /** Add 4-byte big-endian length prefix to data. */
    private fun frameData(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + 4)
        val writer = DataOutputStream(output)
        writer.writeInt(data.size)
        writer.write(data)
        writer.flush()
        return output.toByteArray()
    }

    /** Serialize IpcMessage to JSON. */
    private fun serializeMessage(msg: IpcMessage): String {
        val dto =
            IpcMessageDto(
                correlation_id = msg.correlationId,
                command = msg.command,
                payload = String(msg.payload, StandardCharsets.UTF_8),
                timestamp = msg.timestamp,
            )
        return json.encodeToString(IpcMessageDto.serializer(), dto)
    }

    /** Deserialize JSON to IpcMessage. */
    private fun deserializeMessage(jsonString: String): IpcMessage {
        val dto = json.decodeFromString(IpcMessageDto.serializer(), jsonString)
        return IpcMessage(
            correlationId = dto.correlation_id,
            command = dto.command,
            payload = dto.payload.toByteArray(StandardCharsets.UTF_8),
            timestamp = dto.timestamp,
        )
    }

    /** Start keepalive ping every 30 seconds. */
    private fun startHeartbeat() {
        keepaliveJob?.cancel()
        keepaliveJob =
            scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)

                    try {
                        val ping =
                            IpcMessage(
                                correlationId = UUID.randomUUID().toString(),
                                command = "ping",
                                payload = ByteArray(0),
                                timestamp = System.currentTimeMillis(),
                            )

                        writeFrame(ping)

                        // Wait up to 5 seconds for pong
                        pingTimeoutJob =
                            scope.launch {
                                delay(PONG_TIMEOUT_MS)
                                if (pingTimeoutJob?.isActive == true) {
                                    // Timeout; reconnect
                                    onSocketFailure(Exception("Ping timeout"))
                                }
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send keepalive ping: ${e.message}")
                        onSocketFailure(e)
                        break
                    }
                }
            }
    }

    /** Start read loop to receive incoming messages from socket. */
    private fun startReadLoop() {
        readLoopJob?.cancel()
        readLoopJob =
            scope.launch {
                try {
                    while (isActive) {
                        val msg = readFrame()
                        handleIncomingMessage(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read loop failed: ${e.message}")
                    onSocketFailure(e)
                }
            }
    }

    /** Handle incoming message: route to pending request or handle built-in commands. */
    private suspend fun handleIncomingMessage(msg: IpcMessage) {
        // Check if this is a response to a pending request
        val future = pendingRequestsMutex.withLock { pendingRequests[msg.correlationId] }
        if (future != null) {
            future.complete(msg)
            return
        }

        // Handle built-in commands
        when (msg.command) {
            "pong" -> {
                // Keepalive pong received; cancel timeout
                pingTimeoutJob?.cancel()
                pingTimeoutJob = null
            }
            "ping" -> {
                // Echo ping with pong
                val pong =
                    IpcMessage(
                        correlationId = msg.correlationId,
                        command = "pong",
                        payload = ByteArray(0),
                        timestamp = System.currentTimeMillis(),
                    )
                writeFrame(pong)
            }
            else -> {
                // Unknown unsolicited message; log and ignore
                Log.e(TAG, "Unknown unsolicited command: ${msg.command}")
            }
        }
    }

    /** Drain message queue and send to socket (non-blocking, fire-and-forget). */
    private suspend fun drainQueue() {
        val messagesToSend =
            queueMutex.withLock {
                val msgs = ipcQueue.toList()
                ipcQueue.clear()
                msgs
            }

        for (msg in messagesToSend) {
            try {
                writeFrame(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send queued message: ${e.message}")
                // Requeue on failure (up to queue depth limit)
                queueMutex.withLock {
                    if (ipcQueue.size < MAX_QUEUE_DEPTH) {
                        ipcQueue.add(msg)
                    }
                }
            }
        }
    }
}

/** Internal representation of IPC message. */
internal data class IpcMessage(
    val correlationId: String,
    val command: String,
    val payload: ByteArray,
    val timestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpcMessage) return false
        if (correlationId != other.correlationId) return false
        if (command != other.command) return false
        if (!payload.contentEquals(other.payload)) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = correlationId.hashCode()
        result = 31 * result + command.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/** Backoff policy for socket reconnection attempts (reuses RelayService pattern). */
data class ReconnectPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 1000, // 1s initial backoff
    val maxDelayMs: Long = 32000, // 32s cap
) {
    /**
     * Compute delay in milliseconds for a given retry attempt. Uses formula: baseDelayMs * (2 ^
     * attempt), capped at maxDelayMs.
     */
    fun getDelayMs(attempt: Int): Long {
        val exponent = attempt.coerceAtMost(5)
        val delay = baseDelayMs * (1L shl exponent)
        return delay.coerceAtMost(maxDelayMs)
    }

    /** Check if retry is allowed for a given attempt. */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts
}

/** DTOs for JSON serialization (length-prefixed IPC frames). */
@Serializable
internal data class IpcMessageDto(
    val correlation_id: String,
    val command: String,
    val payload: String, // base64 or raw string depending on command
    val timestamp: Long,
)

@Serializable internal data class RegisterHardwareResponseDto(val device_id: String)

@Serializable
internal data class ActivationStatusDto(
    val status: String, // "activated" | "pending" | "failed"
    val public_key: String? = null, // base64-encoded RSA public key (if activated)
    val attempt: Int? = null,
    val next_poll_in: Long? = null,
    val error: String? = null,
)

/** Extension to read exact number of bytes from InputStream. */
private fun java.io.InputStream.readNBytes(
    b: ByteArray,
    off: Int,
    len: Int,
): Int {
    var n = 0
    while (n < len) {
        val count = this.read(b, off + n, len - n)
        if (count < 0) break
        n += count
    }
    return n
}

/** Convert 4-byte array to big-endian Int. */
private fun ByteArray.toInt(): Int {
    return ((this[0].toInt() and 0xFF) shl 24) or
        ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8) or
        (this[3].toInt() and 0xFF)
}
