package com.lightphone.imessage.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for domain events in event sourcing.
 *
 * Schema matches milestone-2.md L95-102. Relationships:
 * - PK id (UUIDv4)
 * - aggregateId references the affected aggregate (not enforced as FK)
 */
@Entity(
        tableName = "domain_events",
        indices = [Index("aggregateId"), Index("occurredAt"), Index("processed")]
)
data class DomainEventEntity(
        @PrimaryKey val id: String,
        val aggregateId: String,
        val eventType: String,
        val payload: String,
        val occurredAt: Long,
        val processed: Boolean = false
)
