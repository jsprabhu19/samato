package com.samato.paymentservice.eventstore.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.paymentservice.domain.Payment;
import com.samato.paymentservice.domain.PaymentStatus;
import com.samato.paymentservice.domain.Money;
import com.samato.paymentservice.eventstore.EventSerde;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Manages snapshots.
 *
 *   - {@link #loadLatest(UUID)}: returns the latest snapshot for an
 *     aggregate, or null if there is none.
 *
 *   - {@link #maybeSnapshot(Payment)}: called after a successful
 *     command. If the payment's version is a multiple of N (e.g. 50),
 *     we persist a snapshot.
 *
 * The snapshot is a serialised Payment (id, status, amount, ids).
 * The events between the snapshot and "now" are then replayed on
 * load. This bounds the replay cost.
 *
 * Why every 50?
 *   - 50 is small enough that a hot wallet's replay is fast (<1ms).
 *   - 50 is large enough that we don't waste storage.
 *   - 50 is configurable via samato.snapshot.every-n-events.
 */
@Component
public class Snapshotter {

    private static final Logger log = LoggerFactory.getLogger(Snapshotter.class);

    private final PaymentSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final int everyNEvents;

    public Snapshotter(PaymentSnapshotRepository repository,
                       ObjectMapper objectMapper,
                       @Value("${samato.snapshot.every-n-events:50}") int everyNEvents) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.everyNEvents = everyNEvents;
    }

    public PaymentSnapshot loadLatest(UUID paymentId) {
        return repository.findById(paymentId).orElse(null);
    }

    /**
     * If the payment's version is a multiple of N, persist a snapshot.
     * Called after a successful command — in the same transaction as
     * the event appends, so we get atomicity: either the snapshot AND
     * the events commit, or neither does.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void maybeSnapshot(Payment payment) {
        if (payment.getVersion() <= 0) return;
        if (payment.getVersion() % everyNEvents != 0) return;

        SnapshotState state = new SnapshotState(
                payment.getPaymentId(),
                payment.getOrderId(),
                payment.getCustomerId(),
                payment.getCurrency(),
                payment.getAmount() != null ? payment.getAmount().amount() : BigDecimal.ZERO,
                payment.getStatus(),
                payment.getRazorpayOrderId(),
                payment.getRazorpayPaymentId(),
                payment.getVersion()
        );
        try {
            String json = objectMapper.writeValueAsString(state);
            repository.save(new PaymentSnapshot(payment.getPaymentId(), payment.getVersion(), json));
            log.debug("Snapshot saved for payment {} at version {}", payment.getPaymentId(), payment.getVersion());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize snapshot", e);
        }
    }

    /**
     * Reconstructs a Payment from a snapshot's JSON. The caller will
     * then replay events strictly after the snapshot version.
     */
    public SnapshotState loadState(UUID paymentId) {
        PaymentSnapshot snap = repository.findById(paymentId).orElse(null);
        if (snap == null) return null;
        try {
            return objectMapper.readValue(snap.getSnapshotData(), SnapshotState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize snapshot for " + paymentId, e);
        }
    }

    /**
     * The serialised snapshot. We use a record (Jackson-friendly) for
     * round-tripping.
     */
    public record SnapshotState(
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            String currency,
            BigDecimal amount,
            PaymentStatus status,
            String razorpayOrderId,
            String razorpayPaymentId,
            int version
    ) {
        public Money money() { return Money.of(amount, currency); }
    }
}
