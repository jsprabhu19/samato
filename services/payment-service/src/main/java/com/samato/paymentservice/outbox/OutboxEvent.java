package com.samato.paymentservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transactional outbox row.
 *
 * The trick that makes "events written to the event store" and
 * "events published to Kafka" eventually consistent without losing
 * any:
 *
 *   1. In the same DB transaction, we insert into {@code outbox_events}
 *      a row describing what we want to publish.
 *   2. The transactional commit succeeds, so the row is durable.
 *   3. A scheduled poller ({@link OutboxPublisher}) reads unsent rows
 *      and publishes them to Kafka, marking {@code sent_at} on success.
 *
 * If the JVM crashes between (2) and (3), the row is still there and
 * will be retried. This is the only safe way to do
 * "DB-write + Kafka-publish" without 2PC.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "uuid")
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] payload;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public OutboxEvent() {}

    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId,
                       String topic, String eventType, byte[] payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.eventType = eventType;
        this.payload = payload;
    }

    public UUID getId()                { return id; }
    public String getAggregateType()   { return aggregateType; }
    public UUID getAggregateId()       { return aggregateId; }
    public String getTopic()           { return topic; }
    public String getEventType()       { return eventType; }
    public byte[] getPayload()         { return payload; }
    public OffsetDateTime getSentAt()  { return sentAt; }
    public void setSentAt(OffsetDateTime v) { this.sentAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
