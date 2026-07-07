package com.samato.orderservice.outbox;

import com.samato.orderservice.domain.Order;
import com.samato.orderservice.domain.OutboxEvent;
import com.samato.orderservice.domain.OutboxEventRepository;
import com.samato.sharedkafka.events.OrderCancelledEvent;
import com.samato.sharedkafka.events.OrderConfirmedEvent;
import com.samato.sharedkafka.events.OrderPlacedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The transactional outbox publisher.
 *
 * Why outbox at all?
 *   A common failure mode: you save the order to PostgreSQL, then the
 *   app crashes before you publish OrderPlacedEvent to Kafka. The order
 *   exists but no downstream service knows. The outbox pattern solves
 *   this: in the SAME transaction as the order write, we INSERT a row
 *   into outbox_events. A separate poller reads those rows and publishes
 *   them. If the app crashes, the poller picks up the unpublished row
 *   on restart.
 *
 * Polling vs. CDC:
 *   - Phase 4: poll. Simple, works, slightly wasteful.
 *   - Phase 8: switch to Debezium CDC so outbox publishes are push-based.
 *
 * Idempotency on the consumer:
 *   We don't dedupe at the publisher. Downstream consumers should be
 *   idempotent (search-service upserts, so duplicate events are safe).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    public static final String TOPIC_ORDER_PLACED    = "samato.order.placed";
    public static final String TOPIC_ORDER_CONFIRMED = "samato.order.confirmed";
    public static final String TOPIC_ORDER_CANCELLED = "samato.order.cancelled";

    private final OutboxEventRepository repo;
    private final KafkaTemplate<String, byte[]> kafka;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository repo,
                           KafkaTemplate<String, byte[]> kafka,
                           ObjectMapper objectMapper) {
        this.repo = repo;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    // -- append (called from saga / order service within an active txn) -----

    public void appendOrderPlaced(Order order) {
        OrderPlacedEvent event = OrderPlacedEvent.newBuilder()
                .setOrderId(order.getId().toString())
                .setCustomerId(order.getCustomerId().toString())
                .setRestaurantId(order.getRestaurantId().toString())
                .setTotalAmount(order.getTotalAmount().toPlainString())
                .setCurrency(order.getCurrency())
                .setItemCount(order.getItems().size())
                .setOccurredAt(System.currentTimeMillis())
                .build();
        save(TOPIC_ORDER_PLACED, "OrderPlacedEvent", "Order", order.getId(), event);
    }

    public void appendOrderConfirmed(Order order) {
        OrderConfirmedEvent event = OrderConfirmedEvent.newBuilder()
                .setOrderId(order.getId().toString())
                .setCustomerId(order.getCustomerId().toString())
                .setRestaurantId(order.getRestaurantId().toString())
                .setTotalAmount(order.getTotalAmount().toPlainString())
                .setCurrency(order.getCurrency())
                .setOccurredAt(System.currentTimeMillis())
                .build();
        save(TOPIC_ORDER_CONFIRMED, "OrderConfirmedEvent", "Order", order.getId(), event);
    }

    public void appendOrderCancelled(Order order) {
        OrderCancelledEvent event = OrderCancelledEvent.newBuilder()
                .setOrderId(order.getId().toString())
                .setCustomerId(order.getCustomerId().toString())
                .setRestaurantId(order.getRestaurantId().toString())
                .setReason(order.getCancellationReason() != null
                        ? order.getCancellationReason() : "unspecified")
                .setOccurredAt(System.currentTimeMillis())
                .build();
        save(TOPIC_ORDER_CANCELLED, "OrderCancelledEvent", "Order", order.getId(), event);
    }

    private void save(String topic, String eventType, String aggregateType,
                      UUID aggregateId, Object avroEvent) {
        try {
            byte[] payload = avroEvent.toString().getBytes(StandardCharsets.UTF_8);
            OutboxEvent row = new OutboxEvent();
            row.setTopic(topic);
            row.setEventType(eventType);
            row.setAggregateType(aggregateType);
            row.setAggregateId(aggregateId);
            row.setPayload(payload);
            repo.save(row);
            log.debug("Outbox: queued {} for aggregate {}", eventType, aggregateId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enqueue outbox event", e);
        }
    }

    // -- poller --------------------------------------------------------------

    /**
     * Polls every 500ms. Each tick grabs up to 100 unsent events and
     * publishes them. Failed publishes are left unsent (sent_at stays
     * null) so the next tick will retry.
     */
    @Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> unsent = repo.findUnsent();
        if (unsent.isEmpty()) return;

        for (OutboxEvent event : unsent) {
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                        event.getTopic(),
                        event.getAggregateId().toString(),    // key = aggregate id (partition affinity)
                        event.getPayload()
                );
                kafka.send(record);
                event.setSentAt(java.time.Instant.now());
                repo.save(event);
                log.info("Outbox: published {} from {} to topic {}",
                        event.getEventType(), event.getAggregateId(), event.getTopic());
            } catch (Exception e) {
                log.error("Outbox: failed to publish event {} (will retry): {}",
                        event.getId(), e.getMessage());
                // Don't mark as sent — let the next tick retry.
            }
        }
    }
}
