package com.lightphone.imessage.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lightphone.imessage.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for message operations. Provides query and mutation operations on the messages
 * table. All Flow-based queries return reactive updates. Spec: milestone-2.md § 2 (Data Model).
 */
@Dao
interface MessageDao {
    @Insert suspend fun insert(message: MessageEntity)

    @Update suspend fun update(message: MessageEntity)

    @Delete suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId") suspend fun deleteById(messageId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    fun getById(messageId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC")
    fun getByThreadId(threadId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC") fun getAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE status != 2 ORDER BY timestamp ASC")
    fun getUndelivered(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE readReceiptAt IS NULL ORDER BY timestamp ASC")
    fun getUnread(): Flow<List<MessageEntity>>

    @Query(
            "UPDATE messages SET status = 2, deliveryReceiptAt = :deliveryReceiptAt WHERE id = :messageId"
    )
    suspend fun markDelivered(messageId: String, deliveryReceiptAt: Long)

    @Query("UPDATE messages SET readReceiptAt = :readReceiptAt WHERE id = :messageId")
    suspend fun markRead(messageId: String, readReceiptAt: Long)
}
