package com.lightphone.imessage.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Room entity for iMessage messages. Represents a single message in a thread. Includes delivery and
 * read receipt tracking, message type, and attachment metadata. Spec: milestone-2.md § 2 (Data
 * Model).
 */
@Entity(
        tableName = "messages",
        foreignKeys =
                [
                        ForeignKey(
                                entity = ThreadEntity::class,
                                parentColumns = ["id"],
                                childColumns = ["threadId"],
                                onDelete = ForeignKey.CASCADE
                        )]
)
data class MessageEntity(
        @PrimaryKey val id: String,
        @ColumnInfo(name = "threadId", index = true) val threadId: String,
        val sender: String,
        val body: String,
        val timestamp: Long,
        val type: Int,
        val isOutgoing: Boolean,
        val status: Int,
        @ColumnInfo(name = "deliveryReceiptAt") val deliveryReceiptAt: Long? = null,
        @ColumnInfo(name = "readReceiptAt") val readReceiptAt: Long? = null,
        val attachmentCount: Int = 0,
        @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val rawEnvelope: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false

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
        } else if (other.rawEnvelope != null) return false

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
