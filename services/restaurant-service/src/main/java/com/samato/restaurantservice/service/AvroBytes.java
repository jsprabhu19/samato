package com.samato.restaurantservice.service;

import com.samato.sharedkafka.events.DomainEvent;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Encode/decode Avro records to/from byte arrays.
 *
 * Why we use Avro's **DataFile** format (header + blocks) for the outbox
 * payload, not raw binary:
 *   - Each payload carries the writer's schema. The consumer can
 *     deserialize even if the reader schema is different (as long as
 *     compatibility is maintained).
 *   - The header is small (~few hundred bytes) — cheap overhead.
 *
 * On the wire (Kafka), the Schema Registry handles this. Here in the
 * outbox, we just use Avro's native DataFileWriter/Reader.
 *
 * Note: the consumer side (search-service) will use the Kafka deserializer,
 * not this helper. This is outbox-specific.
 */
final class AvroBytes {

    private static final Map<String, Class<? extends SpecificRecord>> REGISTRY = Map.of(
            "com.samato.events.RestaurantCreatedEvent", com.samato.events.RestaurantCreatedEvent.class,
            "com.samato.events.RestaurantUpdatedEvent", com.samato.events.RestaurantUpdatedEvent.class
    );

    private AvroBytes() {}

    static byte[] encode(SpecificRecord record) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataFileWriter<SpecificRecord> w = new DataFileWriter<>(new SpecificDatumWriter<>())) {
            w.setCodec(CodecFactory.nullCodec());
            w.create(record.getSchema(), baos);
            w.append(record);
            w.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode Avro record", e);
        }
    }

    @SuppressWarnings("unchecked")
    static DomainEvent decode(String eventType, byte[] payload) {
        Class<? extends SpecificRecord> cls = REGISTRY.get(eventType);
        if (cls == null) {
            throw new IllegalStateException("No Avro class registered for event type: " + eventType);
        }
        DatumReader<SpecificRecord> reader = new SpecificDatumReader<>(cls);
        try (DataFileReader<SpecificRecord> r =
                     new DataFileReader<>(new ByteArrayInputStream(payload), reader)) {
            return (DomainEvent) r.next();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode Avro record of type " + eventType, e);
        }
    }
}
