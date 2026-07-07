package com.samato.paymentservice.eventstore;

import com.samato.paymentservice.domain.event.PaymentEvent;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA mapping of the {@code events} table.
 *
 * Why JSONB for the payload?
 *   We chose JSONB over Avro for the event store because:
 *     1. JSONB is debuggable: a human can read the rows in psql.
 *     2. JSONB supports GIN indexing (querying by payload fields).
 *     3. Avro adds friction (schema registry, codegen) for the same
 *        correctness guarantees.
 *   On the Kafka wire we still use Avro for schema evolution.
 *
 * The {@code @Type(JsonType.class)} annotation (Hibernate Types
 * hypersistence-utils) makes Hibernate write a {@code String} as a
 * JSONB column. It uses Jackson for serialisation.
 */
@Entity
@Table(name = "events")
public class EventStoreEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "uuid")
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    /**
     * Serialised event payload. The wire format is JSON; the storage
     * format is JSONB (PostgreSQL's binary JSON). Hibernate Types'
     * JsonType handles the round-trip.
     */
    @Type(JsonType.class)
    @Column(name = "event_data", nullable = false, columnDefinition = "jsonb")
    private String eventData;

    @Column(nullable = false)
    private int version;

    @Column(name = "command_id", nullable = false, columnDefinition = "uuid")
    private UUID commandId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public EventStoreEntry() {}

    public static EventStoreEntry from(UUID aggregateId,
                                       String aggregateType,
                                       PaymentEvent event,
                                       UUID commandId,
                                       EventSerde serde) {
        EventStoreEntry e = new EventStoreEntry();
        e.aggregateId   = aggregateId;
        e.aggregateType = aggregateType;
        e.eventType     = event.getClass().getSimpleName();
        e.eventData     = serde.toJson(event);
        e.version       = event.version();
        e.commandId     = commandId;
        return e;
    }

    public Long getSequenceNumber()  { return sequenceNumber; }
    public UUID getAggregateId()     { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public String getEventType()     { return eventType; }
    public String getEventData()     { return eventData; }
    public int getVersion()          { return version; }
    public UUID getCommandId()       { return commandId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
