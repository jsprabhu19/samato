package com.samato.orderservice.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire DTOs for the payment-service Feign client.
 *
 * These mirror the payment-service's own {@code PaymentDtos} but
 * live here (in order-service) so the order-service doesn't depend
 * on payment-service's package layout. We could share via
 * shared-dtos, but for a bible with one consumer, that's overkill.
 */
public final class PaymentDtos {

    private PaymentDtos() {}

    public record CreatePaymentRequest(
            @NotNull UUID orderId,
            @NotNull UUID customerId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency
    ) {}

    public record RefundRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount
    ) {}

    public record PaymentResponse(
            UUID paymentId,
            String razorpayOrderId,
            String razorpayPaymentId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String currency,
            String status,            // payment-service returns enum; deserialised to String here
            long lastEventSeq,
            OffsetDateTime updatedAt
    ) {}
}
