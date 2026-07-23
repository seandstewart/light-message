package com.lightphone.imessage.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for iMessage contacts. Stores contact information including handle, display name, and
 * avatar URL. Spec: milestone-2.md § 2 (Data Model).
 */
@Entity(tableName = "contacts")
data class ContactEntity(
        @PrimaryKey val id: String,
        val handle: String,
        val displayName: String,
        val avatarUrl: String? = null
)
