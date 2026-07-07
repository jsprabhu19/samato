package com.samato.paymentservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.paymentservice.api.PaymentDtos.PaymentResponse;
import com.samato.paymentservice.domain.Money;
import com.samato.paymentservice.domain.Payment;
import com.samato.paymentservice.domain.command.PaymentCommand;
import com.samato.paymentservice.eventstore.PostgresEventStore;
import com.samato.paymentservice.idempotency.IdempotencyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The top-level orchestrator that the controllers and webhooks call.
 *
 * Wraps the command handler in idempotency:
 *
 *   - For HTTP commands (create order, refund), the caller supplies
 *     an {@code Idempotency-Key} header. We dedup on
 *     (command_type, key).
 *
 *   - For webhooks, the dedup key is the Razorpay event id (a UUID
 *     in the payload). Same table, different command_type.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentCommandHandler commandHandler;
    private final IdempotencyGuard idempotency;
    private final PostgresEventStore eventStore;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentCommandHandler commandHandler,
                          IdempotencyGuard idempotency,
                          PostgresEventStore eventStore,
                          ObjectMapper objectMapper) {
        this.commandHandler = commandHandler;
        this.idempotency = idempotency;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a Razorpay order. Idempotent on {@code Idempotency-Key}.
     */
    public PaymentResponse createRazorpayOrder(PaymentCommand.CreateRazorpayOrder cmd,
                                                String idempotencyKey) {
        return idempotency.executeOnce(
                "CreateRazorpayOrder",
                idempotencyKey,
                cmd.paymentId(),
                PaymentResponse.class,
                () -> commandHandler.handleCreateRazorpayOrder(cmd, idempotencyKey)
        );
    }

    /**
     * Refund a captured payment. Idempotent on {@code Idempotency-Key}.
     */
    public PaymentResponse refund(PaymentCommand.RefundPayment cmd, String idempotencyKey) {
        return idempotency.executeOnce(
                "RefundPayment",
                idempotencyKey,
                cmd.paymentId(),
                PaymentResponse.class,
                () -> commandHandler.handleRefund(cmd, idempotencyKey)
        );
    }

    /**
     * Handle a Razorpay webhook event. Dedup key is the event id.
     *
     * @return true if the event was processed, false if it was a replay.
     */
    public boolean handleWebhook(String rawBody, JsonNode event) {
        String eventId = event.path("id").asText(null);
        String eventType = event.path("event").asText(null);
        if (eventId == null || eventType == null) {
            log.warn("Webhook missing id or event field, ignoring");
            return false;
        }
        Optional<String> replay = idempotency.findReplay("RazorpayWebhook", eventId);
        if (replay.isPresent()) {
            log.info("Razorpay webhook {} is a replay — skipping", eventId);
            return false;
        }

        try {
            dispatchWebhook(eventType, event);
        } catch (Exception e) {
            // We DON'T mark the webhook processed on failure — Razorpay
            // will retry. This is the standard webhook pattern.
            log.error("Webhook {} ({}) failed: {}", eventId, eventType, e.getMessage());
            throw e;
        }

        // Mark as processed AFTER successful dispatch.
        idempotency.recordResult("RazorpayWebhook", eventId,
                null /* aggregate resolved per-event */, 200,
                "{\"ok\":true}");
        return true;
    }

    private void dispatchWebhook(String eventType, JsonNode event) {
        // Razorpay webhooks put the resource under payload.payment.entity
        // (for payment.*) or payload.refund.entity (for refund.*).
        switch (eventType) {
            case "payment.captured" -> {
                String razorpayOrderId   = event.at("/payload/payment/entity/order_id").asText();
                String razorpayPaymentId = event.at("/payload/payment/entity/id").asText();
                UUID paymentId = lookupPaymentId(razorpayOrderId);
                commandHandler.handleMarkCaptured(paymentId, UUID.randomUUID(), razorpayPaymentId);
            }
            case "payment.failed" -> {
                String razorpayOrderId   = event.at("/payload/payment/entity/order_id").asText();
                String razorpayPaymentId = event.at("/payload/payment/entity/id").asText();
                String code = event.at("/payload/payment/entity/error_code").asText("UNKNOWN");
                String desc = event.at("/payload/payment/entity/error_description").asText("");
                UUID paymentId = lookupPaymentId(razorpayOrderId);
                commandHandler.handleMarkFailed(paymentId, UUID.randomUUID(),
                        razorpayPaymentId, code, desc);
            }
            case "refund.processed" -> {
                String razorpayPaymentId = event.at("/payload/refund/entity/payment_id").asText();
                String razorpayRefundId  = event.at("/payload/refund/entity/id").asText();
                long amountPaise         = event.at("/payload/refund/entity/amount").asLong(0);
                String currency          = event.at("/payload/refund/entity/currency").asText("INR");
                Money amount = Money.of(BigDecimal.valueOf(amountPaise).movePointLeft(2), currency);
                UUID paymentId = lookupPaymentIdByRazorpayPaymentId(razorpayPaymentId);
                commandHandler.handleMarkRefundCompleted(paymentId, UUID.randomUUID(),
                        razorpayRefundId, amount);
            }
            default -> log.warn("Unknown Razorpay webhook event type: {}", eventType);
        }
    }

    private UUID lookupPaymentId(String razorpayOrderId) {
        return eventStore.findAggregateIdByRazorpayOrderId(razorpayOrderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment found for razorpay_order_id=" + razorpayOrderId));
    }

    private UUID lookupPaymentIdByRazorpayPaymentId(String razorpayPaymentId) {
        // Walk the event stream looking for the first event that set this id.
        return eventStore.findAggregateIdByRazorpayPaymentId(razorpayPaymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "No payment found for razorpay_payment_id=" + razorpayPaymentId));
    }
}
