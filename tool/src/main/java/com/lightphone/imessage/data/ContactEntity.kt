package com.lightphone.imessage.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for contact information.
 *
 * Schema matches milestone-2.md L79-84. Relationships:
 * - PK id (UUIDv4)
 * - handle is UNIQUE and NOT NULL
 */
@Entity(tableName = "contacts", indices = [Index("handle", unique = true)])
data class ContactEntity(
    @PrimaryKey val id: String,
    val handle: String,
    val displayName: String,
    val avatarUrl: String? = null,
)
