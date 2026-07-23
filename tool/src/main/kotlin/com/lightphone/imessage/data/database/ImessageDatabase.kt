package com.lightphone.imessage.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lightphone.imessage.data.dao.ContactDao
import com.lightphone.imessage.data.dao.MessageDao
import com.lightphone.imessage.data.dao.ThreadDao
import com.lightphone.imessage.data.entity.ContactEntity
import com.lightphone.imessage.data.entity.MessageEntity
import com.lightphone.imessage.data.entity.ThreadEntity

/**
 * Room database for iMessage cache. Provides access to DAOs for message, thread, and contact
 * operations. Database is created in application context. Spec: milestone-2.md § 2 (Data Model);
 * ADR-006 (Room and DataStore).
 */
@Database(
        entities = [MessageEntity::class, ThreadEntity::class, ContactEntity::class],
        version = 1,
        exportSchema = false
)
abstract class ImessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun threadDao(): ThreadDao

    abstract fun contactDao(): ContactDao
}
