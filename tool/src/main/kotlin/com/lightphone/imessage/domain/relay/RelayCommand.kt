package com.lightphone.imessage.domain.relay

/**
 * Commands to send over WebSocket to relay server. Encoded as length-prefixed JSON (4-byte
 * big-endian length + UTF-8 JSON). Spec: milestone-2.md § 4.1–4.2, command framing details.
 */
sealed class RelayCommand {
    /**
     * Send an encrypted message via relay.
     * @param messageId Unique identifier for this send attempt
     * @param recipientUri Recipient iMessage URI (e.g., "tel:+1...", "mailto:...")
     * @param envelope Encrypted message envelope (output from MessageCodec.encodeEnvelope)
     */
    data class SendMessage(
            val messageId: MessageId,
            val recipientUri: String,
            val envelope: ByteArray
    ) : RelayCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendMessage) return false
            if (messageId != other.messageId) return false
            if (recipientUri != other.recipientUri) return false
            if (!envelope.contentEquals(other.envelope)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = messageId.hashCode()
            result = 31 * result + recipientUri.hashCode()
            result = 31 * result + envelope.contentHashCode()
            return result
        }
    }

    /**
     * Acknowledge receipt of a message from relay.
     * @param messageId Identifier of the received message
     */
    data class AckMessage(val messageId: MessageId) : RelayCommand()

    /** Request full sync (used after reconnect or by background worker). */
    object RequestSync : RelayCommand()

    /** Keepalive ping (sent by client every 30s). */
    object Ping : RelayCommand()

    /** Keepalive pong (response from relay). */
    object Pong : RelayCommand()
}

/** Unique message identifier for tracking sends and acks. Typically a UUID string. */
data class MessageId(val value: String)

/**
 * Message envelope sent to relay.
 * @param recipient Recipient iMessage URI
 * @param payload Encrypted message bytes
 * @param messageId Unique identifier for tracking
 */
data class OutgoingMessage(
        val recipient: String,
        val payload: ByteArray,
        val messageId: MessageId
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutgoingMessage) return false
        if (recipient != other.recipient) return false
        if (!payload.contentEquals(other.payload)) return false
        if (messageId != other.messageId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = recipient.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + messageId.hashCode()
        return result
    }
}

/**
 * Relay server endpoint configuration.
 * @param url WebSocket URL (e.g., "wss://relay.example.com/ws")
 * @param token Bearer token for Authorization header
 */
data class RelayEndpoint(val url: String, val token: String)
