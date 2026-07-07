package com.samato.paymentservice.api;

import com.samato.paymentservice.domain.Money;
import com.samato.paymentservice.domain.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request/response DTOs for the payment-service REST API.
 *
 * DTOs are separate from the domain types so the wire format can
 * change without rippling into the event-sourced core.
 */
public final class PaymentDtos {

    private PaymentDtos() {}

    /**
     * Request to create a Razorpay order. The saga sends this; the
     * response contains the {@code razorpayOrderId} which is the
     * receipt we use to reconcile with Razorpay later (via webhook).
     */
    public record CreatePaymentRequest(
            @NotNull UUID orderId,
            @NotNull UUID customerId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency
    ) {}

    /**
     * Request to refund a captured payment. Admin-only.
     */
    public record RefundRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount
    ) {}

    /**
     * Response for both create and refund endpoints. Mirrors the
     * read model: denormalised for client convenience.
     */
    public record PaymentResponse(
            UUID paymentId,
            String razorpayOrderId,
            String razorpayPaymentId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            long lastEventSeq,
            OffsetDateTime updatedAt
    ) {}

    /**
     * Lightweight projection for a single event in the event stream.
     * Used by the {@code /events} endpoint.
     */
    public record EventResponse(
            long sequenceNumber,
            int version,
            String eventType,
            OffsetDateTime occurredAt
    ) {}

    /**
     * Response for the time-travel "balance at version N" endpoint.
     */
    public record BalanceAtResponse(
            UUID paymentId,
            int version,
            BigDecimal amount,
            String currency
    ) {
        public static BalanceAtResponse of(UUID paymentId, int version, Money m) {
            return new BalanceAtResponse(paymentId, version, m.amount(), m.currency());
        }
    }
}
