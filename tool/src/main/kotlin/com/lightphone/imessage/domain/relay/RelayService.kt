package com.lightphone.imessage.domain.relay

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Thrown by sendCommand when there is no live WebSocket to send on. */
class NotConnectedException : Exception("Relay WebSocket is not connected")

/**
 * Callback hook for deciding whether to ACK an incoming SendMessage. Implementations must persist
 * the message durably before returning true; otherwise the sender may drop the message on their
 * side while the receiver loses it after a crash.
 */
interface MessageAckPolicy {
    /** @return true if the message has been persisted and it is safe to send an ACK. */
    suspend fun onIncomingMessage(cmd: RelayCommand.SendMessage): Boolean
}

/**
 * WebSocket-based relay service for iMessage communication. Handles connection lifecycle, command
 * framing, reconnection with exponential backoff, and keepalive pings.
 *
 * Spec: milestone-2.md § 5.2 (Relay Connection), § 6.4 (Reconnect Backoff).
 *
 * @param okHttpClient OkHttp client for WebSocket connection
 * @param messageCodec Codec for encoding/decoding message envelopes (future use)
 * @param scope Coroutine scope for launching async tasks
 * @param ackPolicy Optional persistence gate for incoming SendMessage ACKs. When null, ACKs are
 * sent unconditionally with a warning log (ack-before-persist risk).
 */
class RelayService(
        private val okHttpClient: OkHttpClient,
        private val messageCodec: IMessageCodec? = null,
        private val scope: CoroutineScope,
        private val ackPolicy: MessageAckPolicy? = null,
) : IRelayService {
    private companion object {
        private const val TAG = "RelayService"
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        private const val PONG_TIMEOUT_MS = 5_000L

        private val AUTH_REGEX = Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s\"]+")

        /** Redact bearer tokens from a log message so they never leak to logcat. */
        private fun redactAuth(msg: String): String = msg.replace(AUTH_REGEX, "$1***")
    }

    private val _connectionState =
            MutableStateFlow<RelayConnectionState>(RelayConnectionState.Disconnected)
    override val connectionState: StateFlow<RelayConnectionState>
        get() = _connectionState.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    private val commandQueue: ConcurrentLinkedQueue<RelayCommand> = ConcurrentLinkedQueue()
    private val reconnectPolicy: ReconnectPolicy =
            ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1000)

    private val reconnectAttempt = AtomicInteger(0)
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null
    private var currentEndpoint: RelayEndpoint? = null

    private val reconnectMutex = Mutex()
    private var reconnectInFlight = false

    @Volatile private var pendingPong: CompletableDeferred<Unit>? = null

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun connect(endpoint: RelayEndpoint): Result<Unit> {
        return try {
            _connectionState.emit(RelayConnectionState.Connecting)
            reconnectAttempt.set(0)
            currentEndpoint = endpoint
            performConnect()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "connect() failed: ${redactAuth(e.message ?: "unknown")}")
            _connectionState.emit(RelayConnectionState.Failed("connection failed"))
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return try {
            reconnectJob?.cancel()
            reconnectJob = null
            keepaliveJob?.cancel()
            keepaliveJob = null
            pendingPong?.cancel()
            pendingPong = null
            webSocket?.close(1000, "Disconnect requested")
            webSocket = null
            currentEndpoint = null
            reconnectMutex.withLock { reconnectInFlight = false }
            _connectionState.emit(RelayConnectionState.Disconnected)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendMessage(message: OutgoingMessage): Result<MessageId> {
        return try {
            val command =
                    RelayCommand.SendMessage(
                            messageId = message.messageId,
                            recipientUri = message.recipient,
                            envelope = message.payload,
                    )
            // Always attempt send; sendCommand reports NotConnected via Result so we can enqueue.
            val send = sendCommand(command)
            if (send.isFailure) {
                commandQueue.add(command)
            }
            Result.success(message.messageId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestSync(): Result<Unit> {
        return try {
            val send = sendCommand(RelayCommand.RequestSync)
            if (send.isFailure) {
                commandQueue.add(RelayCommand.RequestSync)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Perform WebSocket connection with retry logic. */
    private suspend fun performConnect() {
        val endpoint = currentEndpoint
        if (endpoint == null) {
            val error = "No endpoint configured for connection"
            _connectionState.emit(RelayConnectionState.Failed(error))
            return
        }

        if (!reconnectPolicy.shouldRetry(reconnectAttempt.get())) {
            val error = "Max reconnect attempts (${reconnectPolicy.maxAttempts}) exhausted"
            _connectionState.emit(RelayConnectionState.Failed(error))
            return
        }

        try {
            val request =
                    Request.Builder()
                            .url(endpoint.url)
                            .addHeader("Authorization", "Bearer ${endpoint.token}")
                            .build()

            val listener =
                    object : WebSocketListener() {
                        override fun onOpen(
                                webSocket: WebSocket,
                                response: okhttp3.Response,
                        ) {
                            this@RelayService.onWebSocketOpen(webSocket)
                        }

                        override fun onMessage(
                                webSocket: WebSocket,
                                bytes: ByteString,
                        ) {
                            this@RelayService.onWebSocketMessage(bytes)
                        }

                        override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: okhttp3.Response?,
                        ) {
                            this@RelayService.onWebSocketFailure(t)
                        }

                        override fun onClosed(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                        ) {
                            this@RelayService.onWebSocketClosed(code, reason)
                        }
                    }

            okHttpClient.newWebSocket(request, listener)
        } catch (e: Exception) {
            onWebSocketFailure(e)
        }
    }

    /**
     * Called when WebSocket connection opens. Emits Connected state, drains command queue, and
     * starts keepalive.
     */
    private fun onWebSocketOpen(webSocket: WebSocket) {
        this.webSocket = webSocket
        reconnectAttempt.set(0)

        scope.launch {
            reconnectMutex.withLock { reconnectInFlight = false }
            _connectionState.emit(RelayConnectionState.Connected)

            // Drain command queue peek-then-poll so a failure mid-drain leaves the pending
            // commands at the head of the queue instead of silently dropping them.
            while (true) {
                val cmd = commandQueue.peek() ?: break
                val result = sendCommand(cmd)
                if (result.isFailure) break
                commandQueue.poll()
            }

            startKeepalive()
        }
    }

    /**
     * Called when WebSocket receives a binary message. Parses frame, routes to handler, sends ACK
     * if needed.
     */
    private fun onWebSocketMessage(bytes: ByteString) {
        scope.launch {
            try {
                val frame = parseFrame(bytes.toByteArray())
                handleIncomingCommand(frame)
            } catch (e: Exception) {
                // Log and ignore parse errors, continue operation
                Log.e(TAG, "Failed to parse WebSocket frame: ${redactAuth(e.message ?: "")}")
            }
        }
    }

    /**
     * Called when WebSocket connection fails. Serialized behind [reconnectMutex] and guarded by
     * [reconnectInFlight] so that a real socket failure and a ping timeout cannot spawn two
     * concurrent reconnect loops.
     */
    private fun onWebSocketFailure(t: Throwable) {
        scope.launch {
            reconnectMutex.withLock {
                if (reconnectInFlight) return@withLock
                reconnectInFlight = true

                keepaliveJob?.cancel()
                keepaliveJob = null
                pendingPong?.cancel()
                pendingPong = null
                webSocket = null

                val current = reconnectAttempt.get()
                if (reconnectPolicy.shouldRetry(current)) {
                    val delayMs = reconnectPolicy.getDelayMs(current)
                    val nextAttempt = current + 1

                    _connectionState.emit(
                            RelayConnectionState.Reconnecting(
                                    attempt = nextAttempt,
                                    nextRetryIn = delayMs,
                            ),
                    )

                    reconnectAttempt.set(nextAttempt)
                    reconnectJob?.cancel()
                    reconnectJob =
                            scope.launch {
                                delay(delayMs)
                                performConnect()
                            }
                } else {
                    Log.w(
                            TAG,
                            "WebSocket failure (exhausted retries): " + redactAuth(t.message ?: ""),
                    )
                    _connectionState.emit(RelayConnectionState.Failed("connection failed"))
                }
            }
        }
    }

    /** Called when WebSocket connection closes. Emits Disconnected state and cleans up. */
    private fun onWebSocketClosed(
            code: Int,
            reason: String,
    ) {
        scope.launch {
            keepaliveJob?.cancel()
            keepaliveJob = null
            pendingPong?.cancel()
            pendingPong = null
            webSocket = null
            _connectionState.emit(RelayConnectionState.Disconnected)
        }
    }

    /**
     * Send a command frame over WebSocket. Returns [NotConnectedException] as a failure when the
     * socket is not live, so callers can enqueue the command instead of silently dropping it.
     */
    private fun sendCommand(cmd: RelayCommand): Result<Unit> {
        val ws = webSocket ?: return Result.failure(NotConnectedException())
        return try {
            val payload = serializeCommand(cmd)
            val frame = frameData(payload.toByteArray(StandardCharsets.UTF_8))
            val enqueued = ws.send(frame.toByteString(0, frame.size))
            if (enqueued) {
                Result.success(Unit)
            } else {
                Result.failure(NotConnectedException())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: ${redactAuth(e.message ?: "")}")
            Result.failure(e)
        }
    }

    /** Serialize a RelayCommand to JSON string. */
    private fun serializeCommand(cmd: RelayCommand): String =
            when (cmd) {
                is RelayCommand.SendMessage -> {
                    val dto =
                            SendMessageDto(
                                    command = "send_message",
                                    message_id = cmd.messageId.value,
                                    recipient = cmd.recipientUri,
                                    envelope = cmd.envelope.joinToString("") { "%02x".format(it) },
                            )
                    json.encodeToString(SendMessageDto.serializer(), dto)
                }
                is RelayCommand.AckMessage -> {
                    val dto =
                            AckMessageDto(command = "ack_message", message_id = cmd.messageId.value)
                    json.encodeToString(AckMessageDto.serializer(), dto)
                }
                is RelayCommand.RequestSync -> {
                    val dto = RequestSyncDto(command = "request_sync")
                    json.encodeToString(RequestSyncDto.serializer(), dto)
                }
                is RelayCommand.Ping -> {
                    val dto = PingDto(command = "ping")
                    json.encodeToString(PingDto.serializer(), dto)
                }
                is RelayCommand.Pong -> {
                    val dto = PongDto(command = "pong")
                    json.encodeToString(PongDto.serializer(), dto)
                }
            }

    /**
     * Parse incoming WebSocket frame and return command. Format: 4-byte big-endian length prefix +
     * UTF-8 JSON payload. Command dispatch uses a typed envelope decode rather than substring
     * matching so payloads containing `"command":"…"` as literal data cannot be misrouted.
     */
    private fun parseFrame(data: ByteArray): RelayCommand {
        val input = DataInputStream(ByteArrayInputStream(data))

        // Read 4-byte big-endian length
        val length = input.readInt()
        if (length <= 0 || length > data.size - 4) {
            throw IllegalArgumentException("Invalid frame length: $length")
        }

        // Read JSON payload
        val payload = ByteArray(length)
        input.readFully(payload)
        val jsonStr = String(payload, StandardCharsets.UTF_8)

        val envelope = json.decodeFromString(CommandEnvelope.serializer(), jsonStr)
        return when (envelope.command) {
            "ping" -> RelayCommand.Ping
            "pong" -> RelayCommand.Pong
            "send_message" -> {
                val dto = json.decodeFromString(SendMessageDto.serializer(), jsonStr)
                RelayCommand.SendMessage(
                        messageId = MessageId(dto.message_id),
                        recipientUri = dto.recipient,
                        envelope =
                                dto.envelope.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                )
            }
            "ack_message" -> {
                val dto = json.decodeFromString(AckMessageDto.serializer(), jsonStr)
                RelayCommand.AckMessage(MessageId(dto.message_id))
            }
            "request_sync" -> RelayCommand.RequestSync
            else -> throw IllegalArgumentException("Unknown command: ${envelope.command}")
        }
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

    /** Handle incoming command from relay. */
    private suspend fun handleIncomingCommand(cmd: RelayCommand) {
        when (cmd) {
            is RelayCommand.SendMessage -> {
                val policy = ackPolicy
                if (policy == null) {
                    // TODO: wire a MessageAckPolicy that persists the message before ACKing.
                    // Sending the ACK now risks the relay dropping the message before it is
                    // durably stored locally (ack-before-persist).
                    Log.w(
                            TAG,
                            "No MessageAckPolicy configured; ACKing before persistence (risky).",
                    )
                    sendCommand(RelayCommand.AckMessage(cmd.messageId))
                } else {
                    val shouldAck =
                            try {
                                policy.onIncomingMessage(cmd)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(
                                        TAG,
                                        "MessageAckPolicy failed: ${redactAuth(e.message ?: "")}",
                                )
                                false
                            }
                    if (shouldAck) {
                        sendCommand(RelayCommand.AckMessage(cmd.messageId))
                    }
                }
            }
            is RelayCommand.AckMessage -> {
                // Relay acknowledged our message send
                // TODO: Update message repository with status=SENT
            }
            is RelayCommand.Ping -> {
                sendCommand(RelayCommand.Pong)
            }
            is RelayCommand.Pong -> {
                // Keepalive pong received; complete the outstanding deferred so the timeout
                // watcher exits cleanly.
                pendingPong?.complete(Unit)
                pendingPong = null
            }
            is RelayCommand.RequestSync -> {
                // TODO: Trigger sync from relay
            }
        }
    }

    /**
     * Start keepalive ping every 30 seconds. Uses a per-ping [CompletableDeferred] so pong arrival
     * vs. timeout is unambiguous and we cannot leak orphan timeout jobs.
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob =
                scope.launch {
                    while (true) {
                        delay(KEEPALIVE_INTERVAL_MS)

                        // Abort any previous outstanding pong watcher so only one is live at a
                        // time.
                        pendingPong?.cancel()
                        val pong = CompletableDeferred<Unit>()
                        pendingPong = pong

                        val sendResult =
                                try {
                                    sendCommand(RelayCommand.Ping)
                                } catch (e: Exception) {
                                    Log.e(
                                            TAG,
                                            "Failed to send keepalive ping: ${redactAuth(e.message ?: "")}",
                                    )
                                    pong.cancel()
                                    break
                                }

                        if (sendResult.isFailure) {
                            pong.cancel()
                            onPongTimeout()
                            break
                        }

                        scope.launch {
                            val ok =
                                    try {
                                        withTimeoutOrNull(PONG_TIMEOUT_MS) { pong.await() }
                                    } catch (e: CancellationException) {
                                        // Replaced by a newer ping (or disconnect); nothing to do.
                                        return@launch
                                    }
                            if (ok == null && pong === pendingPong) {
                                onPongTimeout()
                            }
                        }
                    }
                }
    }

    /**
     * Called when a pong was not received within [PONG_TIMEOUT_MS]. Closes the socket and routes
     * through the single serialized reconnect path.
     */
    private fun onPongTimeout() {
        try {
            webSocket?.close(1001, "Ping timeout")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close socket on pong timeout: ${redactAuth(e.message ?: "")}")
        }
        onWebSocketFailure(Exception("Ping timeout"))
    }
}

/** DTOs for JSON serialization (length-prefixed WebSocket frames). */
@Serializable
private data class SendMessageDto(
        val command: String,
        val message_id: String,
        val recipient: String,
        val envelope: String, // hex-encoded bytes
)

@Serializable private data class AckMessageDto(val command: String, val message_id: String)

@Serializable private data class RequestSyncDto(val command: String)

@Serializable private data class PingDto(val command: String)

@Serializable private data class PongDto(val command: String)

/** Minimal peek shape used for typed command dispatch. */
@Serializable private data class CommandEnvelope(val command: String)

/**
 * Placeholder interface for message codec. TODO: Link to actual implementation when MessageCodec is
 * finalized.
 */
interface IMessageCodec {
    fun encodeEnvelope(payload: ByteArray): Result<ByteArray>

    fun decodeEnvelope(envelope: ByteArray): Result<ByteArray>
}
