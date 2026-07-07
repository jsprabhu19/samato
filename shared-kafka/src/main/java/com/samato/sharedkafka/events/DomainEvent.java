package com.samato.sharedkafka.events;

import org.apache.avro.specific.SpecificRecord;

/**
 * Marker for all Avro-generated event records.
 *
 * Why a marker interface?
 *   - Spring's KafkaTemplate is generic in the value type. By having all
 *     our events implement this interface, we get one producer config +
 *     one KafkaTemplate that's used by every service.
 *   - It also gives us a single place to add cross-cutting methods
 *     later (e.g. {@code eventId()}, {@code correlationId()}).
 *
 * Concrete events (RestaurantCreated, MenuItemAdded, etc.) are generated
 * by the Avro Maven plugin from .avsc files in shared-kafka/src/main/avro/.
 * They are SpecificRecord subclasses and implement this marker.
 */
public interface DomainEvent extends SpecificRecord {
    /** Stable id of the event for idempotent consumers. */
    String getEventId();
    /** Aggregate id (restaurantId, orderId, etc.) for partitioning. */
    String getAggregateId();
}
