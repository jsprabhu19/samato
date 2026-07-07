package com.samato.paymentservice.api;

import java.math.BigDecimal;

/**
 * Boundary to Razorpay. Defined as an interface so we can:
 *
 *   1. Test the service without a real Razorpay call.
 *   2. Apply Resilience4j (timeout, circuit breaker, retry) on the
 *      concrete impl.
 *   3. Swap to a fake for integration tests.
 *
 * The interface is intentionally narrow — only the three operations
 * the bible needs: create order, refund, verify webhook.
 *
 * Note: We do NOT put {@code @CircuitBreaker} on the interface —
 * that creates a JDK proxy, and with two implementations (real +
 * fallback) Spring can't disambiguate. Instead, the annotations are
 * on the impl class {@link RazorpayClientImpl} (a CGLIB proxy) and
 * the fallback is wired as a separate bean.
 */
public interface RazorpayClient {

    /**
     * Create a Razorpay order. Razorpay returns an order id like
     * "order_XXXXXXXXXXXXXXXX" which we store in our event store
     * as {@code razorpayOrderId}.
     *
     * @param amountPaise amount in paise (Razorpay's wire format)
     * @param currency    ISO 4217 code (always "INR" in this bible)
     * @param receipt     our internal order id — used for reconciliation
     * @param idempotencyKey the saga's step id; Razorpay uses it server-side
     *                       to make duplicate createOrder calls safe.
     */
    RazorpayOrderResult createOrder(long amountPaise, String currency, String receipt, String idempotencyKey);

    /**
     * Refund a captured payment. Returns a razorpay_refund_id that we
     * store as {@code razorpayRefundId}.
     */
    RazorpayRefundResult refund(String razorpayPaymentId, long amountPaise, String idempotencyKey);

    /**
     * Verify a Razorpay webhook signature. Razorpay signs the body
     * with HMAC-SHA256 using the webhook secret. We MUST verify
     * before trusting any payload.
     *
     * Signature verification has no remote dependency, so it does NOT
     * have a circuit breaker.
     */
    boolean verifyWebhookSignature(String payload, String signature);

    /**
     * Result of creating a Razorpay order. Only the bits we need.
     */
    record RazorpayOrderResult(
            String razorpayOrderId,
            String receipt,
            long amountPaise,
            String currency,
            String status
    ) {
        public BigDecimal amountInRupees() {
            return BigDecimal.valueOf(amountPaise).movePointLeft(2);
        }
    }

    /**
     * Result of refunding a payment.
     */
    record RazorpayRefundResult(
            String razorpayRefundId,
            String razorpayPaymentId,
            long amountPaise,
            String currency,
            String status
    ) {
        public BigDecimal amountInRupees() {
            return BigDecimal.valueOf(amountPaise).movePointLeft(2);
        }
    }
}
