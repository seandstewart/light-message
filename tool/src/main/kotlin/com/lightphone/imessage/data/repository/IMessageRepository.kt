package com.lightphone.imessage.data.repository

import com.lightphone.imessage.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for message operations. Provides reactive queries via Flow and suspend
 * operations for mutations. All operations are result-wrapped for error handling. Spec:
 * milestone-2.md § TASK_010 (Repository Layer).
 */
interface IMessageRepository {
    /**
     * Inserts a new message into the database.
     * @param message The MessageEntity to insert
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun insertMessage(message: MessageEntity): Result<Unit>

    /**
     * Updates an existing message.
     * @param message The MessageEntity with updated fields
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun updateMessage(message: MessageEntity): Result<Unit>

    /**
     * Deletes a message by ID.
     * @param messageId The ID of the message to delete
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun deleteMessage(messageId: String): Result<Unit>

    /**
     * Retrieves a single message by ID as a Flow for reactive updates.
     * @param messageId The ID of the message to retrieve
     * @return Flow emitting the MessageEntity or null if not found
     */
    fun getMessageById(messageId: String): Flow<MessageEntity?>

    /**
     * Retrieves all messages in a thread ordered by timestamp (newest first).
     * @param threadId The ID of the thread
     * @return Flow emitting a list of messages in the thread
     */
    fun getMessagesByThreadId(threadId: String): Flow<List<MessageEntity>>

    /**
     * Retrieves all messages in the database ordered by timestamp (newest first).
     * @return Flow emitting a list of all messages
     */
    fun getAllMessages(): Flow<List<MessageEntity>>

    /**
     * Marks a message as delivered and sets the delivery receipt timestamp.
     * @param messageId The ID of the message
     * @param deliveryReceiptAt Timestamp when delivery receipt was received
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun markAsDelivered(
        messageId: String,
        deliveryReceiptAt: Long,
    ): Result<Unit>

    /**
     * Marks a message as read and sets the read receipt timestamp.
     * @param messageId The ID of the message
     * @param readReceiptAt Timestamp when read receipt was received
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun markAsRead(
        messageId: String,
        readReceiptAt: Long,
    ): Result<Unit>

    /**
     * Retrieves all undelivered messages (status != DELIVERED).
     * @return Flow emitting a list of undelivered messages ordered by timestamp (oldest first)
     */
    fun getUndeliveredMessages(): Flow<List<MessageEntity>>

    /**
     * Retrieves all unread messages (readReceiptAt is null).
     * @return Flow emitting a list of unread messages
     */
    fun getUnreadMessages(): Flow<List<MessageEntity>>
}
