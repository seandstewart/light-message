package com.lightphone.imessage.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for conversation threads.
 *
 * Schema matches milestone-2.md L70-78. Relationships:
 * - PK id (deterministic hash of sorted participant URIs)
 * - 1-to-many with MessageEntity (inverse: messageId -> threadId)
 */
@Entity(tableName = "threads", indices = [Index("lastTimestamp")])
data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val participantUris: String,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
)
