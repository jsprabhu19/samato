package com.samato.paymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment Service — the heart of Samato's money flow.
 *
 * Two jobs, both critical:
 *
 *   1. **Talk to Razorpay.** When the order-service saga runs its
 *      CHARGE_PAYMENT step, it calls us. We translate the call into
 *      Razorpay's API: create an order, capture a payment, issue a
 *      refund. Money moves there. We are the messenger.
 *
 *   2. **Remember what happened.** Every Razorpay state change
 *      (order created, payment captured, refund processed) becomes an
 *      event in our PostgreSQL event store. This is the
 *      **reconciliation ledger** — the audit trail, the time-travel
 *      query source, the read-model materializer.
 *
 * The architecture is "Razorpay first" (Razorpay is the source of
 * truth for money) with our event store as the source of truth for
 * "what did we think Razorpay did?". We reconcile by listening to
 * Razorpay's webhooks and appending events to match.
 *
 * Why event sourcing?
 *   - Audit: every rupee that moves is a row, append-only, with a
 *     timestamp, a sequence number, and the source of truth.
 *   - Time travel: GET /payments/{id}/balance-at/{version} reconstructs
 *     the wallet state at any historical point.
 *   - Projections: the read model (payment_view) is materialised from
 *     the event stream. If we want a different view (e.g. a daily
 *     revenue report), we just add a new projector.
 *   - Compensation: refunds are first-class events, not "rollback
 *     markers". The audit log shows the full history.
 *
 * Why a separate `Idempotency-Key`?
 *   The saga in order-service retries. The payment-service must not
 *   create two Razorpay orders for the same logical request. The
 *   `processed_commands` table catches the duplicate by
 *   (command_type, key) and returns the cached response.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@ComponentScan(basePackages = {
        "com.samato.paymentservice",
        "com.samato.shared",
        "com.samato.sharedkafka"
})
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
