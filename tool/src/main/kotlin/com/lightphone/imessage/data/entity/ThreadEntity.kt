package com.lightphone.imessage.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for iMessage conversation threads. Represents a single conversation with multiple
 * participants, stores the last message preview, unread count, and mute status. Spec:
 * milestone-2.md § 2 (Data Model).
 */
@Entity(tableName = "threads")
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
        val isMuted: Boolean = false
)
