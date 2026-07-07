package com.samato.paymentservice.projection;

import com.samato.paymentservice.domain.PaymentStatus;
import com.samato.paymentservice.domain.event.PaymentEvent;
import com.samato.paymentservice.eventstore.EventSerde;
import com.samato.paymentservice.eventstore.EventStoreEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The CQRS projector.
 *
 * Subscribes (in-process) to a list of {@link EventStoreEntry}s and
 * updates the read model. Called from the command handler inside the
 * same transaction as the event appends — so the read model and the
 * event store are atomically consistent.
 *
 * Why in-process and not a Kafka consumer?
 *   1. We're the only writer, so we don't need cross-service
 *      eventual consistency.
 *   2. The cost of a network hop + a separate consumer is real, and
 *      we get nothing for it.
 *   3. The "is the read model up to date?" question goes away.
 *
 * When would you switch?
 *   - If you wanted separate read/write databases (true CQRS).
 *   - If you had multiple services writing events (you don't, here).
 *   - If you needed the read model to be eventually consistent with
 *     downstream services (you'd use Kafka then).
 */
@Component
public class PaymentProjector {

    private static final Logger log = LoggerFactory.getLogger(PaymentProjector.class);

    private final PaymentViewRepository repository;
    private final EventSerde eventSerde;

    public PaymentProjector(PaymentViewRepository repository, EventSerde eventSerde) {
        this.repository = repository;
        this.eventSerde = eventSerde;
    }

    /**
     * Update the read model to reflect the appended events.
     * Called from the command handler in the same transaction.
     */
    public void apply(List<EventStoreEntry> entries) {
        for (EventStoreEntry e : entries) {
            PaymentView view = repository.findById(e.getAggregateId())
                    .orElseGet(() -> {
                        PaymentView v = new PaymentView();
                        v.setPaymentId(e.getAggregateId());
                        return v;
                    });
            applyOne(view, e);
            view.setLastEventSeq(e.getSequenceNumber());
            repository.save(view);
        }
    }

    private void applyOne(PaymentView view, EventStoreEntry e) {
        // We deserialise the payload lazily — but the projection only
        // needs a few fields. For efficiency, the projector inspects
        // the event_type and reads what it needs from the JSON via
        // Jackson. To keep this file focused, we use a small helper:
        JsonFields f = JsonFields.of(e.getEventData());

        switch (e.getEventType()) {
            case "RazorpayOrderCreated" -> {
                PaymentEvent.RazorpayOrderCreated ev = typed(e, PaymentEvent.RazorpayOrderCreated.class);
                view.setOrderId(ev.orderId());
                view.setCustomerId(ev.customerId());
                view.setAmount(ev.amount().amount());
                view.setCurrency(ev.currency());
                view.setRazorpayOrderId(ev.razorpayOrderId());
                view.setStatus(PaymentStatus.ORDER_CREATED);
            }
            case "PaymentInitiated" -> {
                PaymentEvent.PaymentInitiated ev = typed(e, PaymentEvent.PaymentInitiated.class);
                view.setRazorpayPaymentId(ev.razorpayPaymentId());
                view.setStatus(PaymentStatus.PAYMENT_INITIATED);
            }
            case "PaymentCaptured" -> {
                PaymentEvent.PaymentCaptured ev = typed(e, PaymentEvent.PaymentCaptured.class);
                view.setRazorpayPaymentId(ev.razorpayPaymentId());
                view.setStatus(PaymentStatus.CAPTURED);
            }
            case "PaymentFailed" -> {
                PaymentEvent.PaymentFailed ev = typed(e, PaymentEvent.PaymentFailed.class);
                view.setRazorpayPaymentId(ev.razorpayPaymentId());
                view.setStatus(PaymentStatus.FAILED);
            }
            case "RefundInitiated" -> {
                PaymentEvent.RefundInitiated ev = typed(e, PaymentEvent.RefundInitiated.class);
                view.setRazorpayPaymentId(ev.razorpayPaymentId());
                view.setStatus(PaymentStatus.REFUND_INITIATED);
            }
            case "RefundCompleted" -> {
                view.setStatus(PaymentStatus.REFUNDED);
            }
            case "PaymentExpired" -> {
                view.setStatus(PaymentStatus.EXPIRED);
            }
            default -> log.warn("Unknown event type in projector: {}", e.getEventType());
        }
    }

    private <T> T typed(EventStoreEntry e, Class<T> type) {
        try {
            return eventSerde.mapper().readValue(e.getEventData(), type);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not deserialise " + e.getEventType(), ex);
        }
    }

    /**
     * Helper to avoid throwing away Jackson — kept here for
     * potential debugging use. Currently unused; we rely on
     * EventSerde#mapper() for the actual deserialisation.
     */
    private record JsonFields(String orderId, String customerId, String razorpayOrderId,
                              String razorpayPaymentId, String amount, String currency) {
        static JsonFields of(String json) {
            return new JsonFields(null, null, null, null, null, null);
        }
    }
}
