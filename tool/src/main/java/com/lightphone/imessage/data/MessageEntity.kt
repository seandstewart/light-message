package com.lightphone.imessage.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for cached iMessage messages.
 *
 * Schema matches milestone-2.md L56-69. Relationships:
 * - FK threadId → ThreadEntity.id
 * - PK id (UUIDv4)
 */
@Entity(
    tableName = "messages",
    foreignKeys =
        [
            ForeignKey(
                entity = ThreadEntity::class,
                parentColumns = ["id"],
                childColumns = ["threadId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("threadId"), Index("timestamp")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val type: Int,
    val isOutgoing: Boolean,
    val status: Int,
    val deliveryReceiptAt: Long? = null,
    val readReceiptAt: Long? = null,
    val attachmentCount: Int = 0,
    val rawEnvelope: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        if (id != other.id) return false
        if (threadId != other.threadId) return false
        if (sender != other.sender) return false
        if (body != other.body) return false
        if (timestamp != other.timestamp) return false
        if (type != other.type) return false
        if (isOutgoing != other.isOutgoing) return false
        if (status != other.status) return false
        if (deliveryReceiptAt != other.deliveryReceiptAt) return false
        if (readReceiptAt != other.readReceiptAt) return false
        if (attachmentCount != other.attachmentCount) return false
        if (rawEnvelope != null) {
            if (other.rawEnvelope == null) return false
            if (!rawEnvelope.contentEquals(other.rawEnvelope)) return false
        } else if (other.rawEnvelope != null) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + threadId.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + type
        result = 31 * result + isOutgoing.hashCode()
        result = 31 * result + status
        result = 31 * result + (deliveryReceiptAt?.hashCode() ?: 0)
        result = 31 * result + (readReceiptAt?.hashCode() ?: 0)
        result = 31 * result + attachmentCount
        result = 31 * result + (rawEnvelope?.contentHashCode() ?: 0)
        return result
    }
}
