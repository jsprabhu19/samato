package com.samato.paymentservice.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference implementation of a Razorpay-circuit-open fallback.
 *
 * We don't wire this as a Spring bean because having two
 * {@code RazorpayClient} beans (this + the real impl) breaks
 * autowiring. Instead, the {@link com.samato.paymentservice.api.RazorpayClientImpl}
 * wraps its calls in try/catch; on transient failures it propagates
 * the exception, and the saga's Feign fallback or the command
 * handler's exception path deals with it.
 *
 * Kept here as a pattern reference for what to do when the circuit
 * opens: throw a domain exception. Don't fake success.
 */
public class RazorpayClientFallback implements RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClientFallback.class);

    @Override
    public RazorpayOrderResult createOrder(long amountPaise, String currency, String receipt, String idempotencyKey) {
        log.warn("Razorpay createOrder FALLBACK (circuit open / timeout): receipt={}", receipt);
        throw new RazorpayClientImpl.PaymentGatewayException(
                "Razorpay unavailable (circuit breaker open)", null);
    }

    @Override
    public RazorpayRefundResult refund(String razorpayPaymentId, long amountPaise, String idempotencyKey) {
        log.warn("Razorpay refund FALLBACK (circuit open / timeout): paymentId={}", razorpayPaymentId);
        throw new RazorpayClientImpl.PaymentGatewayException(
                "Razorpay unavailable (circuit breaker open)", null);
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        // Signature verification has no remote dependency, so this
        // fallback should never be invoked. Be loud if it is.
        log.error("Razorpay verifyWebhookSignature FALLBACK — should not happen");
        return false;
    }
}
