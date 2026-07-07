package com.samato.sharedkafka.config;

import com.samato.sharedkafka.events.DomainEvent;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer config shared across all services.
 *
 * Why a shared module?
 *   - Every service that publishes events uses the **same** serializer,
 *     schema registry URL, and idempotence config. Duplicating this in
 *     12 services is a recipe for "works on my machine" bugs.
 *   - Adding a new service just means depending on `shared-kafka` and
 *     getting the right defaults for free.
 *
 * The key design decisions baked in here:
 *   1. **String key** — events are partitioned by aggregate id (e.g. restaurantId)
 *      so all events for one restaurant land on the same partition and stay
 *      ordered. A JSON-encoded key would also work; we use a plain string
 *      for simplicity.
 *   2. **Avro value + Schema Registry** — strong typing across services,
 *      schema evolution via compatibility rules (BACKWARD by default).
 *   3. **Idempotent producer + acks=all** — at-least-once with no duplicates
 *      from producer retries. Combined with the outbox pattern in the
 *      publisher, we get effectively-once delivery.
 *   4. **Compression=lz4** — saves bandwidth, costs almost no CPU.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url:http://localhost:8085}")
    private String schemaRegistryUrl;

    @Bean
    public ProducerFactory<String, DomainEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);

        // Reliability
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Performance
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024);

        // Schema evolution policy — producers can write new schemas as long
        // as consumers can read old data with the new schema. Conservative.
        props.put("auto.register.schemas", true);
        props.put("use.latest.version", true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, DomainEvent> kafkaTemplate(ProducerFactory<String, DomainEvent> pf) {
        return new KafkaTemplate<>(pf);
    }
}
