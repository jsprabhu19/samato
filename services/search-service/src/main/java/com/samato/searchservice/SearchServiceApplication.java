package com.samato.searchservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Search Service — the read side of CQRS for restaurants.
 *
 * Owns:
 *   - The OpenSearch index of restaurants (the projection).
 *   - The Kafka consumer that updates the index from restaurant events.
 *   - The /api/search endpoint that runs user queries against the index.
 *
 * Key patterns:
 *   1. **Event-driven projection** — never reads from restaurant-service.
 *      Just consumes Kafka events and reflects them into the index.
 *   2. **CQRS read model** — different storage, different query language.
 *      Searches like "Italian restaurants near me with free delivery" are
 *      trivial in OpenSearch and painful in Postgres.
 *   3. **Idempotent consumer** — the same event may arrive twice (at-least-once).
 *      We dedup by eventId or by `restaurantId` (since events are ordered per
 *      partition, the latest version always wins).
 *   4. **Schema-on-read** — we can change the index mapping without changing
 *      the service code, as long as the JSON is compatible.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableKafka
@ComponentScan(basePackages = {
        "com.samato.searchservice",
        "com.samato.shared",
        "com.samato.sharedkafka"
})
public class SearchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
