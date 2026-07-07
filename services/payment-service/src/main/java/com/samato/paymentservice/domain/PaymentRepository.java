package com.samato.paymentservice.domain;

import com.samato.paymentservice.eventstore.EventSerde;
import com.samato.paymentservice.eventstore.PostgresEventStore;
import com.samato.paymentservice.eventstore.snapshot.PaymentSnapshot;
import com.samato.paymentservice.eventstore.snapshot.Snapshotter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads a {@link Payment} aggregate.
 *
 *   - If a snapshot exists at version V, hydrate from the snapshot.
 *   - Then replay events with version > V.
 *   - If no snapshot, replay all events from the event store.
 *
 * This is the only place that knows about both the snapshot store
 * and the event store. Everything else just sees a "load by id" API.
 */
@Repository
public class PaymentRepository {

    private static final Logger log = LoggerFactory.getLogger(PaymentRepository.class);

    private final PostgresEventStore eventStore;
    private final Snapshotter snapshotter;

    public PaymentRepository(PostgresEventStore eventStore, Snapshotter snapshotter) {
        this.eventStore = eventStore;
        this.snapshotter = snapshotter;
    }

    /**
     * Load a payment by id. Returns Optional.empty() if the aggregate
     * doesn't exist (no events at all).
     */
    public Optional<Payment> load(UUID paymentId) {
        PaymentSnapshot snap = snapshotter.loadLatest(paymentId);
        if (snap == null) {
            // No snapshot — replay from the start.
            List<com.samato.paymentservice.domain.event.PaymentEvent> events =
                    eventStore.loadStream(paymentId);
            if (events.isEmpty()) return Optional.empty();
            return Optional.of(Payment.rehydrate(events));
        }
        // We have a snapshot. Hydrate from it, then replay events AFTER its version.
        Snapshotter.SnapshotState state = snapshotter.loadState(paymentId);
        if (state == null) {
            // Snapshot row exists but body is unparseable — bail loudly.
            // Don't fall back silently; the operator needs to know.
            throw new IllegalStateException("Snapshot for " + paymentId + " is unparseable");
        }
        Payment p = Payment.rehydrateFromSnapshot(state);
        List<com.samato.paymentservice.domain.event.PaymentEvent> tail =
                eventStore.loadStreamAfter(paymentId, snap.getVersion());
        for (var e : tail) p.apply(e);
        log.debug("Loaded payment {} from snapshot v{} + {} events", paymentId, snap.getVersion(), tail.size());
        return Optional.of(p);
    }
}
