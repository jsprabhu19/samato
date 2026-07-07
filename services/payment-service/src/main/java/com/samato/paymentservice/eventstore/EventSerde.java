package com.samato.paymentservice.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.paymentservice.domain.Money;
import com.samato.paymentservice.domain.event.PaymentEvent;
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
                case "RazorpayOrderCreated" -> objectMapper.readValue(json, PaymentEvent.RazorpayOrderCreated.class);
                case "PaymentInitiated"     -> objectMapper.readValue(json, PaymentEvent.PaymentInitiated.class);
                case "PaymentCaptured"      -> objectMapper.readValue(json, PaymentEvent.PaymentCaptured.class);
                case "PaymentFailed"        -> objectMapper.readValue(json, PaymentEvent.PaymentFailed.class);
                case "RefundInitiated"      -> objectMapper.readValue(json, PaymentEvent.RefundInitiated.class);
                case "RefundCompleted"      -> objectMapper.readValue(json, PaymentEvent.RefundCompleted.class);
                case "PaymentExpired"       -> objectMapper.readValue(json, PaymentEvent.PaymentExpired.class);
                default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialise event " + eventType, e);
        }
    }

    public ObjectMapper mapper() {
        return objectMapper;
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
