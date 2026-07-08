package com.samato.orderservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency record.
 *
 * When a client posts to /api/orders with `Idempotency-Key: foo`, we:
 *   1. Compute a deterministic key: `customerId + ":" + idempotencyKey`.
 *   2. Look up a record by that key.
 *      - If found: return the original response (replay).
 *      - If not found: process the request, then save the record.
 *
 * The unique constraint on `idempotency_key` (per customer) prevents two
 * concurrent requests with the same key from both proceeding. One will
 * lose the race and either:
 *   - Wait for the other to commit, then replay its response.
 *   - Or see the unique-constraint violation and replay from the table.
 *
 * Why per-customer?  A user can re-use their own key across sessions, but
 * two users can't accidentally collide on the same key.
 *
 * What we store:
 *   - `key`             — the raw Idempotency-Key header
 *   - `requestHash`     — hash of the request body. If the same key is
 *                          reused with a DIFFERENT body, we reject it.
 *   - `responseStatus`  — what to return on replay
 *   - `responseBody`    — the JSON we returned
 *   - `orderId`         — for the controller to load the order on replay
 */
@Entity
@Table(name = "idempotency_records", indexes = {
        @Index(name = "idx_idem_key", columnList = "idempotency_key", unique = true)
})
public class IdempotencyRecord {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    private UUID customerId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    // No @Lob: migration uses TEXT, but Hibernate 6 + Postgres maps @Lob String -> OID.
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "order_id", columnDefinition = "uuid")
    private UUID orderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public Instant getCreatedAt() { return createdAt; }
}
