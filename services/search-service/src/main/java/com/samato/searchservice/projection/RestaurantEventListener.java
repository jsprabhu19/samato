package com.samato.searchservice.projection;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The Kafka consumer that maintains the OpenSearch projection.
 *
 * What it does:
 *   - Listens to `samato.restaurant.created` and `samato.restaurant.updated`
 *   - For each event, projects the change into OpenSearch (upsert by id)
 *   - Acks the offset only after the projection succeeds
 *
 * Why a single listener for multiple topics?
 *   - All these events update the same projection. One consumer, one
 *     index. Simpler than separate consumers per topic.
 *
 * Idempotency:
 *   - Events for a given `restaurantId` are ordered per partition (the
 *     producer keys by id). The latest write wins.
 *   - We use `restaurantId` as the OpenSearch `_id` so re-delivery of the
 *     same event is just a no-op upsert.
 *
 * The actual projection logic lives in {@link RestaurantProjector}.
 * This class is just the Kafka plumbing.
 */
@Component
public class RestaurantEventListener {

    private static final Logger log = LoggerFactory.getLogger(RestaurantEventListener.class);

    private final RestaurantProjector projector;

    public RestaurantEventListener(RestaurantProjector projector) {
        this.projector = projector;
    }

    @KafkaListener(
            topics = {
                    "samato.restaurant.created",
                    "samato.restaurant.updated"
            },
            groupId = "samato-search-service"
    )
    public void onEvent(ConsumerRecord<String, SpecificRecord> record, Acknowledgment ack) {
        SpecificRecord ev = record.value();
        // Avro-generated records expose getEventId() because every event schema
        // defines an `eventId` field. We read the value via the schema, not a
        // marker interface, because the generated code doesn't implement ours.
        // SpecificRecord has two `get` overloads; cast to Object then String
        // to disambiguate from the int-positional overload.
        Object eventIdObj = ((org.apache.avro.generic.GenericRecord) ev).get("eventId");
        String eventId = eventIdObj == null ? "?" : eventIdObj.toString();
        log.info("Projecting event id={} type={} key={}", eventId, record.topic(), record.key());
        try {
            projector.apply(ev);
            ack.acknowledge();
        } catch (Exception ex) {
            // Don't ack — the consumer will redeliver.
            // (For poison messages, we'd send to a DLQ topic after N retries.
            //  Out of scope for the bible.)
            log.error("Projection failed for event {}: {}", eventId, ex.getMessage());
            throw ex;
        }
    }
}
