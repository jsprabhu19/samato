package com.samato.paymentservice.eventstore.snapshot;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A snapshot of a Payment aggregate at a particular version.
 *
 * The snapshot stores the aggregate's *serialised state* as JSONB.
 * To rebuild:
 *   1. Load the latest snapshot (if any).
 *   2. Re-hydrate the aggregate from the snapshot.
 *   3. Replay events with version > snapshot.version.
 *
 * Without snapshots, a hot wallet (10,000+ events) would replay all
 * 10,000 events on every load. With snapshots every 50 events, the
 * worst case is ~50 events of replay.
 */
@Entity
@Table(name = "payment_snapshots")
public class PaymentSnapshot {

    @Id
    @Column(name = "payment_id", columnDefinition = "uuid")
    private UUID paymentId;

    @Column(nullable = false)
    private int version;

    @Type(JsonType.class)
    @Column(name = "snapshot_data", nullable = false, columnDefinition = "jsonb")
    private String snapshotData;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public PaymentSnapshot() {}

    public PaymentSnapshot(UUID paymentId, int version, String snapshotData) {
        this.paymentId = paymentId;
        this.version = version;
        this.snapshotData = snapshotData;
    }

    public UUID getPaymentId()        { return paymentId; }
    public int getVersion()           { return version; }
    public String getSnapshotData()   { return snapshotData; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
