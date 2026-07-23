package com.lightphone.imessage.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lightphone.imessage.data.entity.ThreadEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for thread operations. Provides query and mutation operations on the threads
 * table. All Flow-based queries return reactive updates. Spec: milestone-2.md § 2 (Data Model).
 */
@Dao
interface ThreadDao {
    @Insert suspend fun insert(thread: ThreadEntity)

    @Update suspend fun update(thread: ThreadEntity)

    @Delete suspend fun delete(thread: ThreadEntity)

    @Query("DELETE FROM threads WHERE id = :threadId")
    suspend fun deleteById(threadId: String)

    @Query("SELECT * FROM threads WHERE id = :threadId")
    fun getById(threadId: String): Flow<ThreadEntity?>

    @Query("SELECT * FROM threads ORDER BY lastTimestamp DESC")
    fun getAll(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE unreadCount > 0 ORDER BY lastTimestamp DESC")
    fun getUnread(): Flow<List<ThreadEntity>>

    @Query("UPDATE threads SET unreadCount = 0 WHERE id = :threadId")
    suspend fun markRead(threadId: String)

    @Query(
        "UPDATE threads SET lastMessage = :lastMessage, lastTimestamp = :timestamp WHERE id = :threadId",
    )
    suspend fun updateLastMessage(
        threadId: String,
        lastMessage: String,
        timestamp: Long,
    )
}
