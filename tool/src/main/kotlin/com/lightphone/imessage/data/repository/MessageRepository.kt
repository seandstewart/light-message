package com.lightphone.imessage.data.repository

import androidx.room.withTransaction
import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Implementation of IMessageRepository using Room database. Delegates all queries to MessageDao.
 * Room's suspend DAO methods already run on Room's internal executor, so no manual
 * `withContext(Dispatchers.IO)` wrapping is required. Spec: milestone-2.md § TASK_010 (Repository
 * Layer).
 */
class MessageRepository(private val database: ImessageDatabase) : IMessageRepository {
    private val messageDao = database.messageDao()

    override suspend fun insertMessage(message: MessageEntity): Result<Unit> =
            try {
                messageDao.insert(message)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override suspend fun updateMessage(message: MessageEntity): Result<Unit> =
            try {
                messageDao.update(message)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
            try {
                messageDao.deleteById(messageId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override fun getMessageById(messageId: String): Flow<MessageEntity?> {
        return messageDao.getById(messageId)
    }

    override fun getMessagesByThreadId(threadId: String): Flow<List<MessageEntity>> {
        return messageDao.getByThreadId(threadId)
    }

    override fun getAllMessages(): Flow<List<MessageEntity>> {
        return messageDao.getAll()
    }

    override suspend fun markAsDelivered(
            messageId: String,
            deliveryReceiptAt: Long,
    ): Result<Unit> =
            try {
                messageDao.markDelivered(messageId, deliveryReceiptAt)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override suspend fun markAsRead(
            messageId: String,
            readReceiptAt: Long,
    ): Result<Unit> =
            try {
                messageDao.markRead(messageId, readReceiptAt)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override fun getUndeliveredMessages(): Flow<List<MessageEntity>> {
        return messageDao.getUndelivered()
    }

    override fun getUnreadMessages(): Flow<List<MessageEntity>> {
        return messageDao.getUnread()
    }

    /**
     * Run [block] inside a single Room transaction. Lets callers atomically commit compound
     * operations that span multiple repositories (e.g. "insert message + update thread + upsert
     * contact"). Not exposed on [IMessageRepository] on purpose — callers who need transactional
     * semantics can depend on the concrete class or add it to a use-case layer.
     */
    suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction(block)
}
