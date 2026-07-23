package com.lightphone.imessage.data.repository

import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Implementation of IMessageRepository using Room database. Delegates all queries to MessageDao and
 * runs suspend operations on IO dispatcher for database safety. Spec: milestone-2.md § TASK_010
 * (Repository Layer).
 */
class MessageRepository(private val database: ImessageDatabase) : IMessageRepository {
    private val messageDao = database.messageDao()

    override suspend fun insertMessage(message: MessageEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                messageDao.insert(message)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun updateMessage(message: MessageEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                messageDao.update(message)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun deleteMessage(messageId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                messageDao.deleteById(messageId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
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
        withContext(Dispatchers.IO) {
            return@withContext try {
                messageDao.markDelivered(messageId, deliveryReceiptAt)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun markAsRead(
        messageId: String,
        readReceiptAt: Long,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                messageDao.markRead(messageId, readReceiptAt)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override fun getUndeliveredMessages(): Flow<List<MessageEntity>> {
        return messageDao.getUndelivered()
    }

    override fun getUnreadMessages(): Flow<List<MessageEntity>> {
        return messageDao.getUnread()
    }
}
