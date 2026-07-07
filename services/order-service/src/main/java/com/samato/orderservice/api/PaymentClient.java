package com.samato.orderservice.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * Feign client to payment-service.
 *
 * Two operations the saga needs:
 *
 *   1. {@link #createOrder} — saga's CHARGE_PAYMENT step. Creates a
 *      Razorpay order and returns the {@code razorpayOrderId} for
 *      reconciliation.
 *
 *   2. {@link #refund} — saga's CHARGE_PAYMENT compensation. Called
 *      when a later step in the saga fails; the customer gets their
 *      money back.
 *
 * Idempotency-Key:
 *   The saga's step id is the key. The payment-service persists
 *   (command_type, key) in the {@code processed_commands} table.
 *   Re-running the step on retry is a no-op server-side, and the
 *   prior result is replayed. This is what makes saga retries safe
 *   without a separate "outbox of payment commands".
 */
@FeignClient(
        name = "samato-payment-service",
        fallbackFactory = PaymentClientFallback.class
)
public interface PaymentClient {

    @PostMapping("/api/payments/orders")
    PaymentDtos.PaymentResponse createOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentDtos.CreatePaymentRequest request);

    @PostMapping("/api/payments/{id}/refunds")
    PaymentDtos.PaymentResponse refund(
            @PathVariable("id") UUID paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentDtos.RefundRequest request);

    @GetMapping("/api/payments/{id}")
    PaymentDtos.PaymentResponse get(@PathVariable("id") UUID paymentId);
}
