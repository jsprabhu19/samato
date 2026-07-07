package com.samato.sharedkafka.events;

import org.apache.avro.specific.SpecificRecord;

/**
 * Marker for all Avro-generated event records.
 *
 * Why a marker interface?
 *   - Spring's KafkaTemplate is generic in the value type. By having all
 *     our events be {@link SpecificRecord} subclasses, we get one producer
 *     config + one KafkaTemplate that's used by every service.
 *   - It also gives us a single place to add cross-cutting methods
 *     later (e.g. {@code eventId()}, {@code correlationId()}).
 *
 * Concrete events (RestaurantCreated, MenuItemAdded, etc.) are generated
 * by the Avro Maven plugin from .avsc files in shared-kafka/src/main/avro/.
 * They are SpecificRecord subclasses. We use this marker interface as a
 * semantic tag — code that needs the event-id / aggregate-id accesses
 * the Avro-generated getter directly (e.g. {@code ev.getEventId()}).
 *
 * Note: we intentionally do NOT add {@code getEventId()} / {@code
 * getAggregateId()} as abstract methods here. Avro's code generator
 * does not let us declare a custom interface in the .avsc schema, so
 * forcing events to implement this marker would require either:
 *   (a) hand-editing the generated sources, or
 *   (b) a custom Avro plugin.
 * Both are too brittle for the bible. We rely on the Avro-generated
 * field names ({@code eventId} everywhere; the per-aggregate id field
 * is named after the aggregate, e.g. {@code restaurantId}, {@code
 * orderId}) and treat this interface as a pure tag for {@code
 * KafkaTemplate} typing.
 */
public interface DomainEvent extends SpecificRecord {
}
