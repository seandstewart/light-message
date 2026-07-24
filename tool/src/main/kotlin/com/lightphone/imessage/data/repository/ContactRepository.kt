package com.lightphone.imessage.data.repository

import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Implementation of IContactRepository using Room database. Delegates all queries to ContactDao.
 * Room's suspend DAO methods already run on Room's internal executor, so no manual
 * `withContext(Dispatchers.IO)` wrapping is required. Spec: milestone-2.md § TASK_010 (Repository
 * Layer).
 *
 * Note on [insertContact] vs [upsertContact]: both are kept intentionally. [insertContact] is used
 * by callers (and integration tests) that need to observe a duplicate-key failure on the first
 * write of a new contact. [upsertContact] is the correct choice for idempotent sync operations
 * where a re-observed contact should overwrite the existing row.
 */
class ContactRepository(private val database: ImessageDatabase) : IContactRepository {
    private val contactDao = database.contactDao()

    override suspend fun insertContact(contact: ContactEntity): Result<Unit> =
            try {
                contactDao.insert(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override suspend fun updateContact(contact: ContactEntity): Result<Unit> =
            try {
                contactDao.update(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override suspend fun deleteContact(contactId: String): Result<Unit> =
            try {
                contactDao.deleteById(contactId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }

    override fun getContactById(contactId: String): Flow<ContactEntity?> {
        return contactDao.getById(contactId)
    }

    override fun getContactByHandle(handle: String): Flow<ContactEntity?> {
        return contactDao.getByHandle(handle)
    }

    override fun getAllContacts(): Flow<List<ContactEntity>> {
        return contactDao.getAll()
    }

    override suspend fun upsertContact(contact: ContactEntity): Result<Unit> =
            try {
                contactDao.upsert(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
}
