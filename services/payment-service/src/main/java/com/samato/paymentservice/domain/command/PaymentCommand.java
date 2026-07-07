package com.samato.paymentservice.domain.command;

import com.samato.paymentservice.domain.Money;

import java.util.UUID;

/**
 * Commands are *intentions*. They differ from events:
 *
 *   - A **command** is "I want this to happen." It can be rejected
 *     (insufficient funds, wallet not found, idempotency key reuse).
 *   - An **event** is "This already happened." It cannot be rejected.
 *
 * Every command carries an {@code idempotencyKey} (from the HTTP
 * Idempotency-Key header in the order-service case, or a saga step id
 * in compensation case). The {@code commandId} is a separate UUID that
 * we use to trace the command through the event store.
 */
public sealed interface PaymentCommand
        permits CreateRazorpayOrder,
                InitiatePayment,
                RefundPayment,
                MarkExpired {

    UUID commandId();
    UUID paymentId();

    record CreateRazorpayOrder(
            UUID commandId,
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            Money amount,
            String currency,
            String idempotencyKey
    ) implements PaymentCommand {}

    record InitiatePayment(
            UUID commandId,
            UUID paymentId,
            String razorpayPaymentId
    ) implements PaymentCommand {}

    record RefundPayment(
            UUID commandId,
            UUID paymentId,
            Money amount,
            String idempotencyKey
    ) implements PaymentCommand {}

    record MarkExpired(
            UUID commandId,
            UUID paymentId
    ) implements PaymentCommand {}
}
