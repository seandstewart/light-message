package com.lightphone.imessage.data.repository

import com.lightphone.imessage.data.entity.ThreadEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for thread operations. Provides reactive queries via Flow and suspend
 * operations for mutations. All operations are result-wrapped for error handling. Spec:
 * milestone-2.md § TASK_010 (Repository Layer).
 */
interface IThreadRepository {
    /**
     * Inserts a new thread into the database.
     * @param thread The ThreadEntity to insert
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun insertThread(thread: ThreadEntity): Result<Unit>

    /**
     * Updates an existing thread.
     * @param thread The ThreadEntity with updated fields
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun updateThread(thread: ThreadEntity): Result<Unit>

    /**
     * Deletes a thread by ID (cascades to related messages).
     * @param threadId The ID of the thread to delete
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun deleteThread(threadId: String): Result<Unit>

    /**
     * Retrieves a single thread by ID as a Flow for reactive updates.
     * @param threadId The ID of the thread to retrieve
     * @return Flow emitting the ThreadEntity or null if not found
     */
    fun getThreadById(threadId: String): Flow<ThreadEntity?>

    /**
     * Retrieves all threads ordered by last message timestamp (newest first).
     * @return Flow emitting a list of all threads
     */
    fun getAllThreads(): Flow<List<ThreadEntity>>

    /**
     * Retrieves all threads with unread messages.
     * @return Flow emitting a list of threads with unreadCount > 0
     */
    fun getUnreadThreads(): Flow<List<ThreadEntity>>

    /**
     * Marks a thread as read by setting unreadCount to 0.
     * @param threadId The ID of the thread
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun markThreadAsRead(threadId: String): Result<Unit>

    /**
     * Updates the last message preview and timestamp for a thread (bulk operation for efficiency).
     * @param threadId The ID of the thread
     * @param lastMessage The preview text of the last message
     * @param timestamp The timestamp of the last message
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun updateLastMessage(
        threadId: String,
        lastMessage: String,
        timestamp: Long,
    ): Result<Unit>
}
