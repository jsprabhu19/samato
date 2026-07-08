package com.samato.restaurantservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event row.
 *
 * This is the cornerstone of the **transactional outbox pattern**:
 * when a service wants to publish an event, it does NOT call Kafka
 * directly. Instead, it writes a row to this table in the same DB
 * transaction as the business write. A separate poller picks rows up,
 * publishes them to Kafka, and marks them as sent.
 *
 * Why?
 *   The classic problem: if you write to the DB and then call Kafka
 *   and Kafka fails, your DB and your event bus disagree. The outbox
 *   makes the write **atomic** with the event by piggybacking on the
 *   DB transaction. The poller retries until success.
 *
 * What we store:
 *   - `aggregateType` + `aggregateId` — for partitioning and audit
 *   - `topic` — the Kafka topic to publish to
 *   - `eventType` — the FQN of the Avro class (so the poller can
 *     deserialize to the right type)
 *   - `payload` — the Avro-encoded bytes (serialized by the producer)
 *   - `createdAt` — for ordering and TTL
 *   - `sentAt` — null = unsent; non-null = published successfully
 *
 * In a real system you'd add:
 *   - `partitionKey` (or derive from aggregateId)
 *   - `headers` (correlation id, etc.)
 *   - `retryCount` (for backoff on transient failures)
 *   - `lastError` (for debugging)
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_unsent", columnList = "sent_at")
})
public class OutboxEvent {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 100)
    private String aggregateType;       // "Restaurant"

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;               // "samato.restaurant.created"

    @Column(nullable = false, length = 200)
    private String eventType;           // "com.samato.events.RestaurantCreatedEvent"

    // No @Lob: migration uses BYTEA, but Hibernate 6 + Postgres maps @Lob byte[] -> OID.
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] payload;             // Avro-encoded bytes

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getCreatedAt() { return createdAt; }
}
