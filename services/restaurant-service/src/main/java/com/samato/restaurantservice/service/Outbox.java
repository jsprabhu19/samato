package com.samato.restaurantservice.service;

import com.samato.events.RestaurantCreatedEvent;
import com.samato.events.RestaurantUpdatedEvent;
import com.samato.restaurantservice.domain.OutboxEvent;
import com.samato.restaurantservice.domain.OutboxEventRepository;
import com.samato.restaurantservice.domain.Restaurant;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The Outbox — the *write* side and the *publish* side of the pattern.
 *
 *   Write side  (enqueueRestaurant*): called inside the same transaction
 *               as the business write. The Avro record is encoded to bytes
 *               and a row is inserted into `outbox_events`.
 *
 *   Publish side (@Scheduled poller): runs every 1s. Reads up to 100
 *               unsent events, sends each to Kafka, marks `sent_at`.
 *
 * Why encode to bytes here instead of letting the publisher do it?
 *   - The publisher is a **different** transaction; if it re-serializes
 *     and the schema changes, you can get a different payload than the
 *     one that was committed.
 *   - Encoding at write time pins the bytes to the schema version that
 *     was current when the event was created. Better for replay.
 *
 * The poller:
 *   1. Reads `findUnsent()` (LIMIT 100, ORDER BY created_at).
 *   2. For each event, calls KafkaTemplate.send().
 *   3. Awaits the send completion (synchronously, not via .whenComplete)
 *      because we want to know if it succeeded before marking sent.
 *   4. On success, sets `sentAt = now()` and saves the row.
 *   5. On failure, leaves the row unsent; the next poll retries.
 *
 * Trade-offs:
 *   - Polling instead of a CDC tool (Debezium) is simpler and self-contained
 *     but is N-second latency floor. Fine for a few services; for very
 *     high throughput, switch to Debezium reading the WAL.
 *   - Single-instance poller works for the bible. In prod, use a
 *     `SELECT ... FOR UPDATE SKIP LOCKED` so multiple instances don't
 *     race. Out of scope here.
 */
@Component
public class Outbox {

    public static final String TOPIC_RESTAURANT_CREATED = "samato.restaurant.created";
    public static final String TOPIC_RESTAURANT_UPDATED = "samato.restaurant.updated";

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, org.apache.avro.specific.SpecificRecord> kafkaTemplate;

    public Outbox(OutboxEventRepository outboxRepo,
                  @Autowired KafkaTemplate<String, org.apache.avro.specific.SpecificRecord> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    // ─── Write side (called from the same transaction as the business write) ─

    public void enqueueRestaurantCreated(Restaurant r) {
        RestaurantCreatedEvent ev = RestaurantCreatedEvent.newBuilder()
                .setEventId(java.util.UUID.randomUUID().toString())
                .setOccurredAt(System.currentTimeMillis())
                .setRestaurantId(r.getId().toString())
                .setOwnerId(r.getOwnerId().toString())
                .setName(r.getName())
                .setDescription(r.getDescription())
                .setCuisine(r.getCuisine())
                .setAddress(r.getAddress())
                .setCity(r.getCity())
                .setLatitude(r.getLatitude())
                .setLongitude(r.getLongitude())
                .build();
        enqueue(r.getId(), TOPIC_RESTAURANT_CREATED,
                "com.samato.events.RestaurantCreatedEvent", ev);
    }

    public void enqueueRestaurantUpdated(Restaurant r) {
        RestaurantUpdatedEvent ev = RestaurantUpdatedEvent.newBuilder()
                .setEventId(java.util.UUID.randomUUID().toString())
                .setOccurredAt(System.currentTimeMillis())
                .setRestaurantId(r.getId().toString())
                .setName(r.getName())
                .setDescription(r.getDescription())
                .setCuisine(r.getCuisine())
                .setAddress(r.getAddress())
                .setCity(r.getCity())
                .setLatitude(r.getLatitude())
                .setLongitude(r.getLongitude())
                .build();
        enqueue(r.getId(), TOPIC_RESTAURANT_UPDATED,
                "com.samato.events.RestaurantUpdatedEvent", ev);
    }

    private <T extends org.apache.avro.specific.SpecificRecord> void enqueue(
            java.util.UUID aggregateId, String topic, String eventType, T event) {
        // Encode the Avro record to bytes using the same serializer Kafka uses,
        // so the bytes in the outbox are exactly what the producer would send.
        // We piggyback on the producer's serializer via a helper.
        byte[] payload = AvroBytes.encode(event);
        OutboxEvent row = new OutboxEvent();
        row.setAggregateType("Restaurant");
        row.setAggregateId(aggregateId);
        row.setTopic(topic);
        row.setEventType(eventType);
        row.setPayload(payload);
        outboxRepo.save(row);
    }

    // ─── Publish side (scheduled poller) ───────────────────────────

    @Scheduled(fixedDelay = 1000L)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepo.findUnsent();
        for (OutboxEvent e : pending) {
            try {
                org.apache.avro.specific.SpecificRecord decoded = AvroBytes.decode(e.getEventType(), e.getPayload());
                ProducerRecord<String, org.apache.avro.specific.SpecificRecord> record =
                        new ProducerRecord<>(e.getTopic(), e.getAggregateId().toString(), decoded);
                // Sync send — we want to know it succeeded before marking sent.
                kafkaTemplate.send(record).get(5, java.util.concurrent.TimeUnit.SECONDS);
                e.setSentAt(java.time.Instant.now());
                outboxRepo.save(e);
            } catch (Exception ex) {
                // Log and let the next poll retry. A real system would add
                // exponential backoff and a poison-message table after N failures.
                org.slf4j.LoggerFactory.getLogger(Outbox.class)
                        .warn("Outbox publish failed for {} id={}: {}",
                                e.getTopic(), e.getId(), ex.getMessage());
            }
        }
    }
}
