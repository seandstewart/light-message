package com.lightphone.imessage.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for thread entities. Supports CRUD and flow-based observation.
 *
 * Relationships:
 * - 1-to-many with MessageEntity (messages via threadId)
 */
@Dao
interface ThreadDao {
    @Insert suspend fun insertThread(thread: ThreadEntity)

    @Update suspend fun updateThread(thread: ThreadEntity)

    @Delete suspend fun deleteThread(thread: ThreadEntity)

    @Query("SELECT * FROM threads WHERE id = :threadId")
    suspend fun getThreadById(threadId: String): ThreadEntity?

    @Query("SELECT * FROM threads ORDER BY lastTimestamp DESC")
    fun getAllThreads(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE unreadCount > 0 ORDER BY lastTimestamp DESC")
    fun getUnreadThreads(): Flow<List<ThreadEntity>>
}
