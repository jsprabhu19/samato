package com.samato.paymentservice.query;

import com.samato.paymentservice.api.PaymentDtos.PaymentResponse;
import com.samato.paymentservice.domain.Money;
import com.samato.paymentservice.domain.Payment;
import com.samato.paymentservice.domain.PaymentRepository;
import com.samato.paymentservice.eventstore.PostgresEventStore;
import com.samato.paymentservice.projection.PaymentView;
import com.samato.paymentservice.projection.PaymentViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Read-side service.
 *
 * Two flavours of read:
 *
 *   1. {@link #findById(UUID)} — read from the CQRS read model
 *      ({@link PaymentView}). Fast, simple, eventually consistent
 *      with the event store by design (it's in the same txn in
 *      practice).
 *
 *   2. {@link #loadAggregate(UUID)} — read from the event store
 *      (full replay). Slow but always correct. Used for the
 *      time-travel endpoint, where we need the state at a specific
 *      version.
 */
@Service
public class PaymentQueryService {

    private final PaymentViewRepository viewRepository;
    private final PaymentRepository paymentRepository;
    private final PostgresEventStore eventStore;

    public PaymentQueryService(PaymentViewRepository viewRepository,
                               PaymentRepository paymentRepository,
                               PostgresEventStore eventStore) {
        this.viewRepository = viewRepository;
        this.paymentRepository = paymentRepository;
        this.eventStore = eventStore;
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(UUID paymentId) {
        PaymentView v = viewRepository.findById(paymentId)
                .orElseThrow(() -> new NoSuchElementException("No payment " + paymentId));
        return toResponse(v);
    }

    @Transactional(readOnly = true)
    public PaymentResponse findByOrderId(UUID orderId) {
        PaymentView v = viewRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NoSuchElementException("No payment for order " + orderId));
        return toResponse(v);
    }

    @Transactional(readOnly = true)
    public Payment loadAggregate(UUID paymentId) {
        return paymentRepository.load(paymentId)
                .orElseThrow(() -> new NoSuchElementException("No payment " + paymentId));
    }

    /**
     * Time-travel: replay events up to (and including) the given version.
     * Useful for "what was the state at version N?" audits.
     */
    @Transactional(readOnly = true)
    public Payment loadAtVersion(UUID paymentId, int version) {
        Payment p = loadAggregate(paymentId);
        // The repository's load already returns the *current* state.
        // For a strict time-travel, replay only events <= version.
        // We do this by reading the event stream and applying up to N.
        List<com.samato.paymentservice.domain.event.PaymentEvent> stream =
                eventStore.loadStream(paymentId);
        com.samato.paymentservice.domain.Payment replay =
                com.samato.paymentservice.domain.Payment.rehydrate(
                        stream.stream()
                              .filter(e -> e.version() <= version)
                              .toList());
        return replay;
    }

    /**
     * Time-travel "balance at version N". For a payment this is the
     * amount that was authorised/captured at that point in time.
     */
    @Transactional(readOnly = true)
    public Money balanceAt(UUID paymentId, int version) {
        Payment p = loadAtVersion(paymentId, version);
        return p.getAmount() != null ? p.getAmount() : Money.of(java.math.BigDecimal.ZERO, p.getCurrency());
    }

    @Transactional(readOnly = true)
    public List<com.samato.paymentservice.domain.event.PaymentEvent> loadEvents(UUID paymentId) {
        return eventStore.loadStream(paymentId);
    }

    private PaymentResponse toResponse(PaymentView v) {
        return new PaymentResponse(
                v.getPaymentId(),
                v.getRazorpayOrderId(),
                v.getRazorpayPaymentId(),
                v.getOrderId(),
                v.getCustomerId(),
                v.getAmount(),
                v.getCurrency(),
                v.getStatus(),
                v.getLastEventSeq(),
                v.getUpdatedAt()
        );
    }
}
