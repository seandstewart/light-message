package com.lightphone.imessage.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for cached iMessage messages.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val threadId: String,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val attachmentUrls: String? = null, // JSON-serialized list
    val reactionMap: String? = null, // JSON-serialized reactions
    val isDelivered: Boolean = false,
    val isRead: Boolean = false
)
