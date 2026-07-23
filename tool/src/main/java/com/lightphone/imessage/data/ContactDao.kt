package com.lightphone.imessage.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Data access object for contact entities. Supports CRUD operations and contact lookup.
 *
 * Unique Constraint:
 * - handle is UNIQUE (tel: or mailto: URI)
 */
@Dao
interface ContactDao {
    @Insert suspend fun insertContact(contact: ContactEntity)

    @Update suspend fun updateContact(contact: ContactEntity)

    @Delete suspend fun deleteContact(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    suspend fun getContactById(contactId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE handle = :handle")
    suspend fun getContactByHandle(handle: String): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    suspend fun getAllContacts(): List<ContactEntity>
}
