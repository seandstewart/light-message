package com.lightphone.imessage.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for message attachments.
 *
 * Schema matches milestone-2.md L85-94. Relationships:
 * - FK messageId → MessageEntity.id
 * - PK id (UUIDv4)
 */
@Entity(
        tableName = "attachments",
        foreignKeys =
                [
                        ForeignKey(
                                entity = MessageEntity::class,
                                parentColumns = ["id"],
                                childColumns = ["messageId"],
                                onDelete = ForeignKey.CASCADE
                        )],
        indices = [Index("messageId")]
)
data class AttachmentEntity(
        @PrimaryKey val id: String,
        val messageId: String,
        val url: String,
        val encryptionKey: ByteArray,
        val size: Long,
        val mimeType: String,
        val fileName: String,
        val status: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AttachmentEntity
        if (id != other.id) return false
        if (messageId != other.messageId) return false
        if (url != other.url) return false
        if (!encryptionKey.contentEquals(other.encryptionKey)) return false
        if (size != other.size) return false
        if (mimeType != other.mimeType) return false
        if (fileName != other.fileName) return false
        if (status != other.status) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + encryptionKey.contentHashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + status
        return result
    }
}
