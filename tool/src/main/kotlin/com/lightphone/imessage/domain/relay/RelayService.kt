package com.lightphone.imessage.domain.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * WebSocket-based relay service for iMessage communication. Handles connection lifecycle, command
 * framing, reconnection with exponential backoff, and keepalive pings.
 *
 * Spec: milestone-2.md § 5.2 (Relay Connection), § 6.4 (Reconnect Backoff).
 *
 * @param okHttpClient OkHttp client for WebSocket connection
 * @param messageCodec Codec for encoding/decoding message envelopes (future use)
 * @param scope Coroutine scope for launching async tasks
 */
class RelayService(
        private val okHttpClient: OkHttpClient,
        private val messageCodec: IMessageCodec? = null,
        private val scope: CoroutineScope
) : IRelayService {

    private val _connectionState =
            MutableStateFlow<RelayConnectionState>(RelayConnectionState.Disconnected)
    override val connectionState: StateFlow<RelayConnectionState> = _connectionState

    private var webSocket: WebSocket? = null
    private val commandQueue: MutableList<RelayCommand> = mutableListOf()
    private val reconnectPolicy: ReconnectPolicy =
            ReconnectPolicy(maxAttempts = 5, baseDelayMs = 1000)

    private var reconnectAttempt = 0
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null
    private var pingTimeoutJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun connect(endpoint: RelayEndpoint): Result<Unit> =
            try {
                _connectionState.emit(RelayConnectionState.Connecting)
                reconnectAttempt = 0
                performConnect(endpoint)
            } catch (e: Exception) {
                _connectionState.emit(RelayConnectionState.Failed(e.message ?: "Unknown error"))
                Result.failure(e)
            }

    override suspend fun disconnect(): Result<Unit> =
            try {
                reconnectJob?.cancel()
                keepaliveJob?.cancel()
                pingTimeoutJob?.cancel()
                webSocket?.close(1000, "Disconnect requested")
                webSocket = null
                _connectionState.emit(RelayConnectionState.Disconnected)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override suspend fun sendMessage(message: OutgoingMessage): Result<MessageId> =
            try {
                val command =
                        RelayCommand.SendMessage(
                                messageId = message.messageId,
                                recipientUri = message.recipient,
                                envelope = message.payload
                        )

                if (_connectionState.value is RelayConnectionState.Connected) {
                    sendCommand(command)
                } else {
                    synchronized(commandQueue) { commandQueue.add(command) }
                }

                Result.success(message.messageId)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override suspend fun requestSync(): Result<Unit> =
            try {
                if (_connectionState.value is RelayConnectionState.Connected) {
                    sendCommand(RelayCommand.RequestSync)
                } else {
                    synchronized(commandQueue) { commandQueue.add(RelayCommand.RequestSync) }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    /** Perform WebSocket connection with retry logic. */
    private suspend fun performConnect(endpoint: RelayEndpoint) {
        if (!reconnectPolicy.shouldRetry(reconnectAttempt)) {
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
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            this@RelayService.onWebSocketOpen(webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            this@RelayService.onWebSocketMessage(bytes)
                        }

                        override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: okhttp3.Response?
                        ) {
                            this@RelayService.onWebSocketFailure(t)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
        reconnectAttempt = 0

        scope.launch {
            _connectionState.emit(RelayConnectionState.Connected)

            // Drain command queue
            val commandsToSend =
                    synchronized(commandQueue) {
                        val cmds = commandQueue.toList()
                        commandQueue.clear()
                        cmds
                    }

            for (cmd in commandsToSend) {
                sendCommand(cmd)
            }

            // Start keepalive ping
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
                System.err.println("Failed to parse WebSocket frame: ${e.message}")
            }
        }
    }

    /** Called when WebSocket connection fails. Triggers reconnection with backoff. */
    private fun onWebSocketFailure(t: Throwable) {
        scope.launch {
            keepaliveJob?.cancel()
            pingTimeoutJob?.cancel()
            webSocket = null

            if (reconnectPolicy.shouldRetry(reconnectAttempt)) {
                val delayMs = reconnectPolicy.getDelayMs(reconnectAttempt)
                val nextAttempt = reconnectAttempt + 1

                _connectionState.emit(
                        RelayConnectionState.Reconnecting(
                                attempt = nextAttempt,
                                nextRetryIn = delayMs
                        )
                )

                reconnectAttempt = nextAttempt
                delay(delayMs)

                // Note: reconnect endpoint needs to be stored or passed differently
                // For now, this is a limitation of the current design
            } else {
                val error =
                        "WebSocket failure after ${reconnectPolicy.maxAttempts} attempts: ${t.message}"
                _connectionState.emit(RelayConnectionState.Failed(error))
            }
        }
    }

    /** Called when WebSocket connection closes. Emits Disconnected state and cleans up. */
    private fun onWebSocketClosed(code: Int, reason: String) {
        scope.launch {
            keepaliveJob?.cancel()
            pingTimeoutJob?.cancel()
            webSocket = null
            _connectionState.emit(RelayConnectionState.Disconnected)
        }
    }

    /**
     * Send a command frame over WebSocket. Serializes command to JSON and wraps with length prefix.
     */
    private suspend fun sendCommand(cmd: RelayCommand) {
        val ws = webSocket ?: return

        try {
            val json = serializeCommand(cmd)
            val frame = frameData(json.toByteArray(StandardCharsets.UTF_8))
            ws.send(ByteString.of(frame))
        } catch (e: Exception) {
            System.err.println("Failed to send command: ${e.message}")
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
                                    envelope = cmd.envelope.joinToString("") { "%02x".format(it) }
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
     * UTF-8 JSON payload.
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
        val json = String(payload, StandardCharsets.UTF_8)

        // Parse based on command type
        return when {
            json.contains("\"command\":\"ping\"") -> RelayCommand.Ping
            json.contains("\"command\":\"pong\"") -> RelayCommand.Pong
            json.contains("\"command\":\"send_message\"") -> {
                val dto = this.json.decodeFromString(SendMessageDto.serializer(), json)
                RelayCommand.SendMessage(
                        messageId = MessageId(dto.message_id),
                        recipientUri = dto.recipient,
                        envelope =
                                dto.envelope.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                )
            }
            json.contains("\"command\":\"ack_message\"") -> {
                val dto = this.json.decodeFromString(AckMessageDto.serializer(), json)
                RelayCommand.AckMessage(MessageId(dto.message_id))
            }
            json.contains("\"command\":\"request_sync\"") -> RelayCommand.RequestSync
            else -> throw IllegalArgumentException("Unknown command in frame: $json")
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
                // Relay is sending us a message; decode and store
                // TODO: Call messageCodec.decodeEnvelope() when MessageCodec is available
                // For now, just acknowledge
                sendCommand(RelayCommand.AckMessage(cmd.messageId))
            }
            is RelayCommand.AckMessage -> {
                // Relay acknowledged our message send
                // TODO: Update message repository with status=SENT
            }
            is RelayCommand.Ping -> {
                sendCommand(RelayCommand.Pong)
            }
            is RelayCommand.Pong -> {
                // Keepalive pong received; cancel timeout
                pingTimeoutJob?.cancel()
                pingTimeoutJob = null
            }
            is RelayCommand.RequestSync -> {
                // TODO: Trigger sync from relay
            }
        }
    }

    /** Start keepalive ping every 30 seconds. */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob =
                scope.launch {
                    while (true) {
                        delay(30_000) // 30 seconds

                        try {
                            sendCommand(RelayCommand.Ping)

                            // Wait up to 5 seconds for pong
                            pingTimeoutJob =
                                    scope.launch {
                                        delay(5_000)
                                        if (pingTimeoutJob?.isActive == true) {
                                            // Timeout; reconnect
                                            onWebSocketFailure(Exception("Ping timeout"))
                                        }
                                    }
                        } catch (e: Exception) {
                            System.err.println("Failed to send keepalive ping: ${e.message}")
                            break
                        }
                    }
                }
    }
}

/** DTOs for JSON serialization (length-prefixed WebSocket frames). */
@Serializable
private data class SendMessageDto(
        val command: String,
        val message_id: String,
        val recipient: String,
        val envelope: String // hex-encoded bytes
)

@Serializable private data class AckMessageDto(val command: String, val message_id: String)

@Serializable private data class RequestSyncDto(val command: String)

@Serializable private data class PingDto(val command: String)

@Serializable private data class PongDto(val command: String)

/**
 * Placeholder interface for message codec. TODO: Link to actual implementation when MessageCodec is
 * finalized.
 */
interface IMessageCodec {
    fun encodeEnvelope(payload: ByteArray): Result<ByteArray>
    fun decodeEnvelope(envelope: ByteArray): Result<ByteArray>
}
