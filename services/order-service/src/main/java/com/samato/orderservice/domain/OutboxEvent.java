package com.samato.orderservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox event row — same pattern as restaurant-service.
 *
 * Why duplicate the entity? Each service owns its outbox table. The pattern
 * is identical so the poller (OutboxPublisher) is essentially copy-paste
 * with a different package. Phase 8 could promote it to shared-kafka.
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
    private String aggregateType;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 200)
    private String eventType;

    @Lob
    @Column(nullable = false)
    private byte[] payload;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
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
