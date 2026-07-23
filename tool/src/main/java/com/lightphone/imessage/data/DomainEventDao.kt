package com.lightphone.imessage.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for domain event entities. Supports CRUD and event sourcing queries.
 *
 * Indices:
 * - aggregateId (for querying events by aggregate)
 * - occurredAt (for ordering by timestamp)
 * - processed (for finding unprocessed events)
 */
@Dao
interface DomainEventDao {
    @Insert suspend fun insertEvent(event: DomainEventEntity)

    @Update suspend fun updateEvent(event: DomainEventEntity)

    @Delete suspend fun deleteEvent(event: DomainEventEntity)

    @Query("SELECT * FROM domain_events WHERE id = :eventId")
    suspend fun getEventById(eventId: String): DomainEventEntity?

    @Query("SELECT * FROM domain_events WHERE aggregateId = :aggregateId ORDER BY occurredAt DESC")
    fun getEventsByAggregateId(aggregateId: String): Flow<List<DomainEventEntity>>

    @Query("SELECT * FROM domain_events WHERE processed = 0 ORDER BY occurredAt ASC")
    fun getUnprocessedEvents(): Flow<List<DomainEventEntity>>

    @Query("SELECT * FROM domain_events ORDER BY occurredAt DESC")
    fun getAllEvents(): Flow<List<DomainEventEntity>>
}
