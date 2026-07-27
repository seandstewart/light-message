package com.lightphone.imessage.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for iMessage conversation threads. Represents a single conversation with multiple
 * participants, stores the last message preview, unread count, and mute status.
 *
 * Schema matches milestone-2.md § 2 (Data Model). Relationships:
 * - PK id (deterministic hash of sorted participant URIs)
 * - 1-to-many with MessageEntity (inverse via MessageEntity.threadId)
 *
 * Index: lastTimestamp (thread-list ordering).
 */
@Entity(tableName = "threads", indices = [Index("lastTimestamp")])
data class ThreadEntity(
        @PrimaryKey val id: String,
        val title: String,
        val lastMessage: String,
        @ColumnInfo(name = "lastTimestamp") val lastTimestamp: Long,
        /**
         * Pipe-separated list of participant URIs (format: "uri1|uri2|uri3"). Must not be empty;
         * each URI should be a valid iMessage identifier (email or phone).
         */
        val participantUris: String,
        @ColumnInfo(name = "unreadCount") val unreadCount: Int = 0,
        val isMuted: Boolean = false,
)
