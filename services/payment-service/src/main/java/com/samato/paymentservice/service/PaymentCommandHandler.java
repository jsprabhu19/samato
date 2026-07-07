package com.samato.paymentservice.service;

import com.samato.paymentservice.api.PaymentDtos.PaymentResponse;
import com.samato.paymentservice.api.RazorpayClient;
import com.samato.paymentservice.api.RazorpayClientImpl;
import com.samato.paymentservice.domain.Money;
import com.samato.paymentservice.domain.Payment;
import com.samato.paymentservice.domain.PaymentRepository;
import com.samato.paymentservice.domain.command.PaymentCommand;
import com.samato.paymentservice.domain.event.PaymentEvent;
import com.samato.paymentservice.eventstore.EventStoreEntry;
import com.samato.paymentservice.eventstore.PostgresEventStore;
import com.samato.paymentservice.eventstore.snapshot.Snapshotter;
import com.samato.paymentservice.outbox.OutboxEvent;
import com.samato.paymentservice.outbox.OutboxEventRepository;
import com.samato.paymentservice.projection.PaymentProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The command-side handler. This is where CQRS writes happen:
 *
 *   1. Load the aggregate (replay events from event store).
 *   2. Run a decide method on the aggregate (validates + produces events).
 *   3. Append the new events to the event store.
 *   4. Project them into the read model (in the same txn).
 *   5. Maybe snapshot (every N events).
 *   6. Write to the outbox (so they get published to Kafka).
 *
 * Steps 3, 4, 5, 6 all happen in the same DB transaction. Either
 * all of it commits, or none of it does. That's how we avoid the
 * "event was written but read model wasn't updated" inconsistency.
 */
@Component
public class PaymentCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandHandler.class);

    private final PaymentRepository paymentRepository;
    private final PostgresEventStore eventStore;
    private final PaymentProjector projector;
    private final Snapshotter snapshotter;
    private final RazorpayClient razorpay;
    private final OutboxEventRepository outboxRepository;

    public PaymentCommandHandler(PaymentRepository paymentRepository,
                                 PostgresEventStore eventStore,
                                 PaymentProjector projector,
                                 Snapshotter snapshotter,
                                 RazorpayClient razorpay,
                                 OutboxEventRepository outboxRepository) {
        this.paymentRepository = paymentRepository;
        this.eventStore = eventStore;
        this.projector = projector;
        this.snapshotter = snapshotter;
        this.razorpay = razorpay;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Step 1 of the saga: create a Razorpay order for this payment.
     *
     *   - Allocates a new {@code paymentId} (= {@code cmd.paymentId()}, derived
     *     from the saga's step id for stable idempotency).
     *   - Calls Razorpay to create the order.
     *   - Persists the resulting {@code RazorpayOrderCreated} event.
     */
    @Transactional
    public PaymentResponse handleCreateRazorpayOrder(PaymentCommand.CreateRazorpayOrder cmd,
                                                     String idempotencyKey) {
        // Make the Razorpay call OUTSIDE the DB transaction if we cared
        // about latency, but Razorpay's createOrder is idempotent on
        // receipt, so a retry is safe. We keep it simple and do it
        // inside the txn; the saga's idempotency key still gates us.
        RazorpayClient.RazorpayOrderResult result = razorpay.createOrder(
                RazorpayClientImpl.toPaise(cmd.amount().amount()),
                cmd.currency(),
                cmd.orderId().toString(),
                idempotencyKey
        );

        Optional<Payment> existing = paymentRepository.load(cmd.paymentId());
        if (existing.isPresent()) {
            // Idempotent replay: the saga's step id is the same, so
            // the paymentId is the same. Just return the current state.
            log.info("Idempotent replay for payment {} (Razorpay order already created)", cmd.paymentId());
            return toResponse(existing.get());
        }

        // Decide → produces the first event.
        Payment p = new Payment();  // package-private constructor
        p.createOrder(cmd, result.razorpayOrderId(), 1);

        // Persist.
        List<PaymentEvent> uncommitted = p.drainUncommitted();
        List<EventStoreEntry> entries = eventStore.append(
                p.getPaymentId(), cmd.commandId(), uncommitted);

        // Project.
        projector.apply(entries);

        // Outbox for Kafka.
        for (EventStoreEntry e : entries) {
            enqueueOutbox(e);
        }

        // First event, no snapshot yet.
        return toResponse(p);
    }

    /**
     * Step 2 (optional, from Razorpay's hosted checkout): mark a
     * payment as initiated. Usually this is followed by a webhook
     * that marks it captured.
     */
    @Transactional
    public PaymentResponse handleInitiatePayment(PaymentCommand.InitiatePayment cmd) {
        return mutate(cmd.paymentId(), cmd.commandId(), p ->
                p.markPaymentInitiated(cmd, nextVersion(p, 1))
        );
    }

    /**
     * From the {@code payment.captured} webhook: mark captured.
     */
    @Transactional
    public PaymentResponse handleMarkCaptured(UUID paymentId, UUID commandId, String razorpayPaymentId) {
        return mutate(paymentId, commandId, p ->
                p.markCaptured(razorpayPaymentId, nextVersion(p, 1))
        );
    }

    /**
     * From the {@code payment.failed} webhook: mark failed.
     */
    @Transactional
    public PaymentResponse handleMarkFailed(UUID paymentId, UUID commandId,
                                            String razorpayPaymentId, String code, String desc) {
        return mutate(paymentId, commandId, p ->
                p.markFailed(razorpayPaymentId, code, desc, nextVersion(p, 1))
        );
    }

    /**
     * Saga compensation: refund a captured payment.
     */
    @Transactional
    public PaymentResponse handleRefund(PaymentCommand.RefundPayment cmd, String idempotencyKey) {
        Payment p = paymentRepository.load(cmd.paymentId())
                .orElseThrow(() -> new IllegalStateException("No payment " + cmd.paymentId()));

        // Call Razorpay to get the refund id.
        RazorpayClient.RazorpayRefundResult result = razorpay.refund(
                p.getRazorpayPaymentId(),
                RazorpayClientImpl.toPaise(cmd.amount().amount()),
                idempotencyKey
        );

        return mutate(cmd.paymentId(), cmd.commandId(), payment ->
                payment.initiateRefund(cmd, result.razorpayRefundId(), nextVersion(payment, 1))
        );
    }

    /**
     * From the {@code refund.processed} webhook: mark refund completed.
     */
    @Transactional
    public PaymentResponse handleMarkRefundCompleted(UUID paymentId, UUID commandId,
                                                    String razorpayRefundId, Money amount) {
        return mutate(paymentId, commandId, p ->
                p.completeRefund(razorpayRefundId, amount, nextVersion(p, 1))
        );
    }

    /**
     * From the {@code order.paid} (timeout) scheduled job: mark expired.
     */
    @Transactional
    public PaymentResponse handleMarkExpired(UUID paymentId, UUID commandId) {
        return mutate(paymentId, commandId, p ->
                p.markExpired(nextVersion(p, 1))
        );
    }

    // -- internal helpers ----------------------------------------------

    private PaymentResponse mutate(UUID paymentId, UUID commandId,
                                   java.util.function.Function<Payment, PaymentEvent> decider) {
        Payment p = paymentRepository.load(paymentId)
                .orElseThrow(() -> new IllegalStateException("No payment " + paymentId));

        decider.apply(p);

        List<PaymentEvent> uncommitted = p.drainUncommitted();
        if (uncommitted.isEmpty()) {
            return toResponse(p);
        }
        List<EventStoreEntry> entries = eventStore.append(paymentId, commandId, uncommitted);
        projector.apply(entries);
        for (EventStoreEntry e : entries) enqueueOutbox(e);
        snapshotter.maybeSnapshot(p);
        return toResponse(p);
    }

    private int nextVersion(Payment p, int add) {
        return p.getVersion() + add;
    }

    private void enqueueOutbox(EventStoreEntry e) {
        // We use the raw JSONB payload as the Kafka wire format. The
        // shared-kafka Avro schemas are used by order-service-side
        // consumers; here we just forward the JSON.
        byte[] body = e.getEventData() == null ? new byte[0] : e.getEventData().getBytes();
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(),
                "Payment",
                e.getAggregateId(),
                topicFor(e.getEventType()),
                e.getEventType(),
                body
        ));
    }

    private String topicFor(String eventType) {
        return switch (eventType) {
            case "RazorpayOrderCreated" -> "samato.payment.created";
            case "PaymentCaptured"      -> "samato.payment.charged";
            case "PaymentFailed"        -> "samato.payment.failed";
            case "RefundInitiated"      -> "samato.payment.refund.initiated";
            case "RefundCompleted"      -> "samato.payment.refunded";
            case "PaymentExpired"       -> "samato.payment.expired";
            default                    -> "samato.payment.unknown";
        };
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getPaymentId(),
                p.getRazorpayOrderId(),
                p.getRazorpayPaymentId(),
                p.getOrderId(),
                p.getCustomerId(),
                p.getAmount() != null ? p.getAmount().amount() : null,
                p.getCurrency(),
                p.getStatus(),
                p.getVersion(),
                null
        );
    }
}
