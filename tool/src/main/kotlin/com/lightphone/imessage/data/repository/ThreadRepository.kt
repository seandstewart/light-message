package com.lightphone.imessage.data.repository

import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.ThreadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Implementation of IThreadRepository using Room database. Delegates all queries to ThreadDao and
 * runs suspend operations on IO dispatcher for database safety. Spec: milestone-2.md § TASK_010
 * (Repository Layer).
 */
class ThreadRepository(private val database: ImessageDatabase) : IThreadRepository {
    private val threadDao = database.threadDao()

    override suspend fun insertThread(thread: ThreadEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                threadDao.insert(thread)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun updateThread(thread: ThreadEntity): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                threadDao.update(thread)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun deleteThread(threadId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                threadDao.deleteById(threadId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override fun getThreadById(threadId: String): Flow<ThreadEntity?> {
        return threadDao.getById(threadId)
    }

    override fun getAllThreads(): Flow<List<ThreadEntity>> {
        return threadDao.getAll()
    }

    override fun getUnreadThreads(): Flow<List<ThreadEntity>> {
        return threadDao.getUnread()
    }

    override suspend fun markThreadAsRead(threadId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                threadDao.markRead(threadId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun updateLastMessage(
        threadId: String,
        lastMessage: String,
        timestamp: Long,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                threadDao.updateLastMessage(threadId, lastMessage, timestamp)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
