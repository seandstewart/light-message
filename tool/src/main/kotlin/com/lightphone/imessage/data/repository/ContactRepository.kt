package com.lightphone.imessage.data.repository

import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.ContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Implementation of IContactRepository using Room database. Delegates all queries to ContactDao and
 * runs suspend operations on IO dispatcher for database safety. Spec: milestone-2.md § TASK_010
 * (Repository Layer).
 */
class ContactRepository(private val database: ImessageDatabase) : IContactRepository {
    private val contactDao = database.contactDao()

    override suspend fun insertContact(contact: ContactEntity): Result<Unit> =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    contactDao.insert(contact)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

    override suspend fun updateContact(contact: ContactEntity): Result<Unit> =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    contactDao.update(contact)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

    override suspend fun deleteContact(contactId: String): Result<Unit> =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    contactDao.deleteById(contactId)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
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
            withContext(Dispatchers.IO) {
                return@withContext try {
                    contactDao.upsert(contact)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
}
