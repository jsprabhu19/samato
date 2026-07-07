package com.samato.paymentservice.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.paymentservice.domain.event.PaymentEvent;
import com.samato.paymentservice.domain.event.PaymentExpired;
import com.samato.paymentservice.domain.event.PaymentFailed;
import com.samato.paymentservice.domain.event.PaymentInitiated;
import com.samato.paymentservice.domain.event.RazorpayOrderCreated;
import com.samato.paymentservice.domain.event.RefundCompleted;
import com.samato.paymentservice.domain.event.RefundInitiated;
import com.samato.paymentservice.domain.event.PaymentCaptured;
import com.samato.paymentservice.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Serialise and deserialise {@link PaymentEvent}s to/from JSON.
 *
 * Why a hand-rolled (de)serialiser instead of Jackson polymorphism?
 *   Jackson's polymorphic types (e.g. @JsonTypeInfo) work, but they
 *   embed a discriminator in the JSON. We want the event store to
 *   have a separate {@code event_type} column (for indexing) AND a
 *   payload column. Splitting the concern makes the SQL clean.
 *
 * The mapping is also case-insensitive on the discriminator so the
 * snake_case in the database matches the PascalCase record class names.
 */
@Component
public class EventSerde {

    private final ObjectMapper objectMapper;

    public EventSerde(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(PaymentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise event " + event.getClass().getSimpleName(), e);
        }
    }

    public PaymentEvent fromJson(String eventType, String json) {
        try {
            return switch (eventType) {
                case "RazorpayOrderCreated" -> objectMapper.readValue(json, RazorpayOrderCreated.class);
                case "PaymentInitiated"     -> objectMapper.readValue(json, PaymentInitiated.class);
                case "PaymentCaptured"      -> objectMapper.readValue(json, PaymentCaptured.class);
                case "PaymentFailed"        -> objectMapper.readValue(json, PaymentFailed.class);
                case "RefundInitiated"      -> objectMapper.readValue(json, RefundInitiated.class);
                case "RefundCompleted"      -> objectMapper.readValue(json, RefundCompleted.class);
                case "PaymentExpired"       -> objectMapper.readValue(json, PaymentExpired.class);
                default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialise event " + eventType, e);
        }
    }

    /**
     * Money serialises as {"amount": "...", "currency": "INR"} in JSON
     * but we use BigDecimal in the DB; this helper converts at the
     * boundary. The default Jackson serialisation of Money is fine
     * for the event payload (we use it via the @JsonAutoDetect path).
     * This helper is used by the projection layer when reading.
     */
    public static Money toMoney(BigDecimal amount, String currency) {
        return Money.of(amount, currency);
    }
}
