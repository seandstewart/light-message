package com.lightphone.imessage.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lightphone.imessage.data.dao.AttachmentDao
import com.lightphone.imessage.data.dao.ContactDao
import com.lightphone.imessage.data.dao.DomainEventDao
import com.lightphone.imessage.data.dao.MessageDao
import com.lightphone.imessage.data.dao.ThreadDao
import com.lightphone.imessage.data.entity.AttachmentEntity
import com.lightphone.imessage.data.entity.ContactEntity
import com.lightphone.imessage.data.entity.DomainEventEntity
import com.lightphone.imessage.data.entity.MessageEntity
import com.lightphone.imessage.data.entity.ThreadEntity

/**
 * Room database for iMessage cache. Single canonical database for all persistent state.
 *
 * Spec: milestone-2.md § 2 (Data Model); ADR-006 (Room and DataStore).
 *
 * Note: [exportSchema] is `false` until a `room.schemaLocation` KSP arg is wired in
 * `tool/build.gradle.kts`. Flip to `true` and commit the generated JSON under `tool/schemas/`
 * before shipping migrations.
 */
@Database(
        entities =
                [
                        MessageEntity::class,
                        ThreadEntity::class,
                        ContactEntity::class,
                        AttachmentEntity::class,
                        DomainEventEntity::class,
                ],
        version = 1,
        exportSchema = false,
)
abstract class ImessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun threadDao(): ThreadDao

    abstract fun contactDao(): ContactDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun domainEventDao(): DomainEventDao

    companion object {
        private const val DB_NAME = "imessage.db"

        @Volatile private var INSTANCE: ImessageDatabase? = null

        fun getInstance(context: Context): ImessageDatabase =
                INSTANCE
                        ?: synchronized(this) {
                            INSTANCE
                                    ?: Room.databaseBuilder(
                                                    context.applicationContext,
                                                    ImessageDatabase::class.java,
                                                    DB_NAME,
                                            )
                                            .build()
                                            .also { INSTANCE = it }
                        }
    }
}
