package com.lightphone.imessage.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for attachment entities. Supports CRUD and flow-based observation.
 *
 * Foreign Key Constraints:
 * - AttachmentEntity.messageId \u2192 MessageEntity.id (CASCADE on delete)
 *
 * Indices:
 * - messageId (for filtering by message)
 */
@Dao
interface AttachmentDao {
    @Insert suspend fun insertAttachment(attachment: AttachmentEntity)

    @Update suspend fun updateAttachment(attachment: AttachmentEntity)

    @Delete suspend fun deleteAttachment(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :attachmentId")
    suspend fun getAttachmentById(attachmentId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE status = :status")
    fun getAttachmentsByStatus(status: Int): Flow<List<AttachmentEntity>>
}
