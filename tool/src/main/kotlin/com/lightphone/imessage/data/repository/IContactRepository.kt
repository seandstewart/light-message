package com.lightphone.imessage.data.repository

import com.lightphone.imessage.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for contact operations. Provides reactive queries via Flow and suspend
 * operations for mutations. All operations are result-wrapped for error handling. Spec:
 * milestone-2.md § TASK_010 (Repository Layer).
 */
interface IContactRepository {
    /**
     * Inserts a new contact into the database.
     * @param contact The ContactEntity to insert
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun insertContact(contact: ContactEntity): Result<Unit>

    /**
     * Updates an existing contact.
     * @param contact The ContactEntity with updated fields
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun updateContact(contact: ContactEntity): Result<Unit>

    /**
     * Deletes a contact by ID.
     * @param contactId The ID of the contact to delete
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun deleteContact(contactId: String): Result<Unit>

    /**
     * Retrieves a single contact by ID as a Flow for reactive updates.
     * @param contactId The ID of the contact to retrieve
     * @return Flow emitting the ContactEntity or null if not found
     */
    fun getContactById(contactId: String): Flow<ContactEntity?>

    /**
     * Retrieves a contact by handle (phone number or email).
     * @param handle The contact handle (phone number or email)
     * @return Flow emitting the ContactEntity or null if not found
     */
    fun getContactByHandle(handle: String): Flow<ContactEntity?>

    /**
     * Retrieves all contacts ordered by display name.
     * @return Flow emitting a list of all contacts
     */
    fun getAllContacts(): Flow<List<ContactEntity>>

    /**
     * Inserts or updates a contact (upsert). If contact with same ID exists, updates it; otherwise
     * inserts a new contact.
     * @param contact The ContactEntity to upsert
     * @return Result.success(Unit) on success, Result.failure on error
     */
    suspend fun upsertContact(contact: ContactEntity): Result<Unit>
}
