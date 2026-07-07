package com.samato.paymentservice.domain;

/**
 * Payment lifecycle (the aggregate's state machine).
 *
 *   ORDER_CREATED ──► PAYMENT_INITIATED ──► CAPTURED
 *         │                  │                  │
 *         │                  ▼                  ▼
 *         │              FAILED           REFUND_INITIATED
 *         │                                     │
 *         ▼                                     ▼
 *     EXPIRED                               REFUNDED
 *
 * The states map to Razorpay's state model:
 *   ORDER_CREATED  ← after Razorpay.Orders.create() returns successfully
 *   PAYMENT_INITIATED ← after the customer opens Razorpay's checkout
 *   CAPTURED       ← after Razorpay sends payment.captured webhook
 *   FAILED         ← after Razorpay sends payment.failed webhook
 *   REFUND_INITIATED ← after we call Razorpay.Payments.refund()
 *   REFUNDED       ← after Razorpay sends refund.processed webhook
 *   EXPIRED        ← Razorpay order auto-expired (24h in test mode)
 *
 * State transitions are recorded by events (RazorpayOrderCreated,
 * PaymentCaptured, etc.). There are NO setters on the Payment
 * aggregate — the only path is through {@code apply(event)}.
 */
public enum PaymentStatus {
    ORDER_CREATED,
    PAYMENT_INITIATED,
    CAPTURED,
    FAILED,
    REFUND_INITIATED,
    REFUNDED,
    EXPIRED;

    public boolean isTerminal() {
        return this == CAPTURED
            || this == FAILED
            || this == REFUNDED
            || this == EXPIRED;
    }
}
