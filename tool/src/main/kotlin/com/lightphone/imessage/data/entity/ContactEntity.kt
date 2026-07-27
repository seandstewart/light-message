package com.lightphone.imessage.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for iMessage contacts. Stores contact information including handle, display name, and
 * avatar URL.
 *
 * Schema matches milestone-2.md § 2 (Data Model). Constraints:
 * - PK id (UUIDv4)
 * - handle is UNIQUE (tel: or mailto: URI)
 */
@Entity(tableName = "contacts", indices = [Index("handle", unique = true)])
data class ContactEntity(
        @PrimaryKey val id: String,
        val handle: String,
        val displayName: String,
        val avatarUrl: String? = null,
)
