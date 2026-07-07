package com.samato.paymentservice.eventstore;

import com.samato.paymentservice.domain.Payment;
import com.samato.paymentservice.domain.event.PaymentEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The event store.
 *
 *   - append(): writes new events for an aggregate. The
 *     UNIQUE(aggregate_id, version) constraint is the safety net for
 *     optimistic concurrency: if a concurrent writer already
 *     committed a row with the same (aggregate_id, version), the
 *     INSERT fails with a DataIntegrityViolationException, which we
 *     translate to an OptimisticLockException.
 *
 *   - loadStream(): reads all events for an aggregate in order. The
 *     repository uses this to rebuild the aggregate via replay.
 *
 *   - findByRazorpayOrderId(): used by the webhook handler to find
 *     the aggregate given the Razorpay-side id.
 *
 * Why REQUIRES_NEW?  Each append is its own transaction so a
 * half-committed batch doesn't pollute the store. The caller wraps
 * the command handler's writes in its own transaction; we don't
 * double-wrap.
 */
@Component
public class PostgresEventStore {

    private final EventStoreEntryRepository repository;
    private final EventSerde eventSerde;

    public PostgresEventStore(EventStoreEntryRepository repository, EventSerde eventSerde) {
        this.repository = repository;
        this.eventSerde = eventSerde;
    }

    /**
     * Appends a batch of events to the store. The first event's
     * version must equal (currentLatestVersion + 1).
     *
     * @param aggregateId  the aggregate id
     * @param commandId    the originating command id (for trace)
     * @param events       the new events to append, in order
     * @return             the persisted entries, in order, with sequence numbers
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<EventStoreEntry> append(UUID aggregateId,
                                        UUID commandId,
                                        List<PaymentEvent> events) {
        int currentVersion = repository.findLatestVersion(aggregateId);
        for (PaymentEvent e : events) {
            int expected = ++currentVersion;
            if (e.version() != expected) {
                throw new IllegalStateException(
                        "Event version " + e.version() + " does not match expected " + expected
                                + " for aggregate " + aggregateId);
            }
        }

        try {
            List<EventStoreEntry> entries = events.stream()
                    .map(e -> EventStoreEntry.from(aggregateId, "Payment", e, commandId, eventSerde))
                    .toList();
            return repository.saveAll(entries);
        } catch (DataIntegrityViolationException dup) {
            // UNIQUE(aggregate_id, version) violation — concurrent writer beat us.
            int actual = repository.findLatestVersion(aggregateId);
            throw new OptimisticLockException(aggregateId, currentVersion, actual);
        }
    }

    /**
     * Loads the entire event stream for an aggregate, in version order.
     */
    public List<PaymentEvent> loadStream(UUID aggregateId) {
        return repository.findStreamByAggregateId(aggregateId).stream()
                .map(e -> eventSerde.fromJson(e.getEventType(), e.getEventData()))
                .toList();
    }

    /**
     * Loads the stream for an aggregate starting strictly after the
     * given version. Used by the snapshot-aware loader.
     */
    public List<PaymentEvent> loadStreamAfter(UUID aggregateId, int afterVersion) {
        return repository.findStreamByAggregateId(aggregateId).stream()
                .filter(e -> e.getVersion() > afterVersion)
                .map(e -> eventSerde.fromJson(e.getEventType(), e.getEventData()))
                .toList();
    }

    /**
     * Find the aggregate id for a Razorpay order id. Used by the
     * webhook handler.
     */
    public Optional<UUID> findAggregateIdByRazorpayOrderId(String razorpayOrderId) {
        List<EventStoreEntry> entries = repository.findByRazorpayOrderId(razorpayOrderId);
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(entries.get(0).getAggregateId());
    }

    /**
     * Find the aggregate id for a Razorpay payment id. The payment id
     * is set by a {@code PaymentInitiated} or {@code PaymentCaptured}
     * event. The first match wins.
     */
    public Optional<UUID> findAggregateIdByRazorpayPaymentId(String razorpayPaymentId) {
        List<EventStoreEntry> entries = repository.findByRazorpayPaymentId(razorpayPaymentId);
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(entries.get(0).getAggregateId());
    }

    /**
     * Used by the snapshot loader to build a fully-rehydrated Payment
     * from the store (or empty if the aggregate is new).
     */
    public Payment loadAggregate(UUID aggregateId) {
        return Payment.rehydrate(loadStream(aggregateId));
    }
}
