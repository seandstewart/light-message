package com.lightphone.imessage.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lightphone.imessage.data.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for attachment operations.
 *
 * Foreign Key Constraints:
 * - AttachmentEntity.messageId → MessageEntity.id (CASCADE on delete)
 *
 * Indices: messageId (filter by message).
 */
@Dao
interface AttachmentDao {
    @Insert suspend fun insert(attachment: AttachmentEntity)

    @Update suspend fun update(attachment: AttachmentEntity)

    @Delete suspend fun delete(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :attachmentId")
    suspend fun deleteById(attachmentId: String)

    @Query("SELECT * FROM attachments WHERE id = :attachmentId")
    suspend fun getById(attachmentId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    fun getByMessageId(messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE status = :status")
    fun getByStatus(status: Int): Flow<List<AttachmentEntity>>
}
