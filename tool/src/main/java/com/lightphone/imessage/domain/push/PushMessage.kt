package com.lightphone.imessage.domain.push

/**
 * Data class representing a parsed UnifiedPush notification payload.
 *
 * Push payloads are JSON-formatted and delivered by the rustpush service. Spec: milestone-2.md §
 * 4.3 (Native Push Notification)
 *
 * @param messageId UUID-formatted message identifier (for deduplication)
 * @param sender iMessage address (tel: or mailto:) of the sender
 * @param timestamp Unix epoch milliseconds (UTC)
 * @param envelope Base64-decoded encrypted message envelope (AES-GCM encrypted)
 */
data class PushMessage(
        val messageId: String,
        val sender: String,
        val timestamp: Long,
        val envelope: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PushMessage
        if (messageId != other.messageId) return false
        if (sender != other.sender) return false
        if (timestamp != other.timestamp) return false
        if (!envelope.contentEquals(other.envelope)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + envelope.contentHashCode()
        return result
    }
}
