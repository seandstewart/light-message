package com.lightphone.imessage.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lightphone.imessage.data.entity.DomainEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for domain event entities. Supports CRUD and event-sourcing queries.
 *
 * Indices: aggregateId (query by aggregate), occurredAt (ordering), processed (queue drain).
 */
@Dao
interface DomainEventDao {
    @Insert suspend fun insert(event: DomainEventEntity)

    @Update suspend fun update(event: DomainEventEntity)

    @Delete suspend fun delete(event: DomainEventEntity)

    @Query("SELECT * FROM domain_events WHERE id = :eventId")
    suspend fun getById(eventId: String): DomainEventEntity?

    @Query("SELECT * FROM domain_events WHERE aggregateId = :aggregateId ORDER BY occurredAt DESC")
    fun getByAggregateId(aggregateId: String): Flow<List<DomainEventEntity>>

    @Query("SELECT * FROM domain_events WHERE processed = 0 ORDER BY occurredAt ASC")
    fun getUnprocessed(): Flow<List<DomainEventEntity>>

    @Query("SELECT * FROM domain_events ORDER BY occurredAt DESC")
    fun getAll(): Flow<List<DomainEventEntity>>
}
