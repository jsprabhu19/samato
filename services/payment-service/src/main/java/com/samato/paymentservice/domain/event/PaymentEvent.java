package com.samato.paymentservice.domain.event;

import com.samato.paymentservice.domain.Money;

import java.util.UUID;

/**
 * The payment event sealed interface.
 *
 * Every state change in the Payment aggregate is represented as an
 * event. The aggregate's state is reconstructed by replaying events;
 * the *current* state is just a cache of the latest replay.
 *
 * Conventions:
 *   - All events are immutable Java records.
 *   - Every event carries the {@code paymentId} it relates to and the
 *     {@code version} it assigns (the per-aggregate monotonic counter).
 *   - Events are stored in JSONB. Field names match record components
 *     so Jackson serialisation is predictable.
 *   - Events are deserialised by {@code PostgresEventStore} which uses
 *     the {@code eventType} discriminator.
 */
public sealed interface PaymentEvent
        permits RazorpayOrderCreated,
                PaymentInitiated,
                PaymentCaptured,
                PaymentFailed,
                RefundInitiated,
                RefundCompleted,
                PaymentExpired {

    UUID paymentId();

    int version();

    record RazorpayOrderCreated(
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            String razorpayOrderId,
            Money amount,
            String currency,
            int version
    ) implements PaymentEvent {}

    record PaymentInitiated(
            UUID paymentId,
            String razorpayOrderId,
            String razorpayPaymentId,
            int version
    ) implements PaymentEvent {}

    record PaymentCaptured(
            UUID paymentId,
            String razorpayOrderId,
            String razorpayPaymentId,
            Money amount,
            int version
    ) implements PaymentEvent {}

    record PaymentFailed(
            UUID paymentId,
            String razorpayOrderId,
            String razorpayPaymentId,
            String errorCode,
            String errorDescription,
            int version
    ) implements PaymentEvent {}

    record RefundInitiated(
            UUID paymentId,
            String razorpayOrderId,
            String razorpayPaymentId,
            String razorpayRefundId,
            Money amount,
            int version
    ) implements PaymentEvent {}

    record RefundCompleted(
            UUID paymentId,
            String razorpayRefundId,
            Money amount,
            int version
    ) implements PaymentEvent {}

    record PaymentExpired(
            UUID paymentId,
            String razorpayOrderId,
            int version
    ) implements PaymentEvent {}
}
