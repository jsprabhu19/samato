package com.samato.orderservice.api;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fallback when payment-service is unreachable.
 *
 * The saga treats this as a transient failure. The order stays in
 * PENDING_PAYMENT; the saga's retry policy will pick it up on the
 * next tick. If payment-service is down for a long time, an operator
 * can manually compensate.
 */
@Component
public class PaymentClientFallback implements FallbackFactory<PaymentClient> {

    @Override
    public PaymentClient create(Throwable cause) {
        return new PaymentClient() {
            @Override
            public PaymentDtos.PaymentResponse createOrder(String idempotencyKey,
                                                            PaymentDtos.CreatePaymentRequest request) {
                throw new PaymentUnavailableException("Payment service unavailable", cause);
            }

            @Override
            public PaymentDtos.PaymentResponse refund(UUID paymentId, String idempotencyKey,
                                                       PaymentDtos.RefundRequest request) {
                throw new PaymentUnavailableException("Payment service unavailable for refund", cause);
            }

            @Override
            public PaymentDtos.PaymentResponse get(UUID paymentId) {
                throw new PaymentUnavailableException("Payment service unavailable for lookup", cause);
            }
        };
    }

    /** Thrown when the payment-service is down. The saga catches this and retries. */
    public static class PaymentUnavailableException extends RuntimeException {
        public PaymentUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }
}
