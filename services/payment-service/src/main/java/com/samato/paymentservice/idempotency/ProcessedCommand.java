package com.samato.paymentservice.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A record that a particular (command_type, key) tuple has been processed.
 *
 * The key constraint is the (command_type, key) UNIQUE index — that's
 * the dedup mechanism. The body (status + JSON) is what we replay back
 * to the caller if they retry with the same key.
 *
 * This is the same pattern as the order-service.
 */
@Entity
@Table(name = "processed_commands")
public class ProcessedCommand {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "command_type", nullable = false, length = 80)
    private String commandType;

    @Column(name = "key", nullable = false, length = 200)
    private String key;

    @Column(name = "aggregate_id", nullable = false, columnDefinition = "uuid")
    private UUID aggregateId;

    @Column(name = "result_status", nullable = false)
    private int resultStatus;

    @Column(name = "result_body", columnDefinition = "text")
    private String resultBody;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public ProcessedCommand() {}

    public ProcessedCommand(UUID id, String commandType, String key,
                            UUID aggregateId, int resultStatus, String resultBody) {
        this.id = id;
        this.commandType = commandType;
        this.key = key;
        this.aggregateId = aggregateId;
        this.resultStatus = resultStatus;
        this.resultBody = resultBody;
    }

    public UUID getId()              { return id; }
    public String getCommandType()   { return commandType; }
    public String getKey()           { return key; }
    public UUID getAggregateId()     { return aggregateId; }
    public int getResultStatus()     { return resultStatus; }
    public String getResultBody()    { return resultBody; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
