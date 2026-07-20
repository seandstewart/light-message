package com.lightphone.imessage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for iMessage application state.
 */
@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ImessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: ImessageDatabase? = null

        fun getInstance(context: Context): ImessageDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context,
                    ImessageDatabase::class.java,
                    "imessage.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
