package com.lightphone.imessage.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lightphone.imessage.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for contact operations. Provides query and mutation operations on the contacts
 * table. All Flow-based queries return reactive updates. Spec: milestone-2.md § 2 (Data Model).
 */
@Dao
interface ContactDao {
    @Insert suspend fun insert(contact: ContactEntity)

    @Update suspend fun update(contact: ContactEntity)

    @Delete suspend fun delete(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :contactId") suspend fun deleteById(contactId: String)

    @Query("SELECT * FROM contacts WHERE id = :contactId")
    fun getById(contactId: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE handle = :handle")
    fun getByHandle(handle: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun getAll(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(contact: ContactEntity)
}
