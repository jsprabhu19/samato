package com.samato.restaurantservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Restaurant Service — the catalog side of Samato.
 *
 * Owns:
 *   - Restaurants (the entity: name, address, cuisine, geo)
 *   - Menus (a restaurant has one current menu)
 *   - Menu items (the actual products: a Pad Thai, a Coke, etc.)
 *
 * Key patterns demonstrated:
 *   1. **CQRS-lite** — the write model lives in Postgres; a Redis cache
 *      is the read model for hot reads (single restaurant by id,
 *      menu by restaurant id). The cache is invalidated on every write
 *      (cache-aside pattern).
 *   2. **Transactional Outbox** — when a restaurant is created, the
 *      "RestaurantCreated" event is written to an `outbox` table in the
 *      SAME transaction as the business write. A scheduled publisher
 *      reads the outbox and ships to Kafka. No dual-write problem.
 *   3. **Event-driven projection** — search-service consumes the events
 *      and projects them into OpenSearch.
 *
 * Interview point: the **read model** and **write model** are not separate
 * databases here (we just use a cache for the read). The "C" in CQRS
 * comes from the fact that writes go through one path (DB + outbox) and
 * reads go through another (Redis with DB fallback). The full split
 * (Postgres for writes, OpenSearch for reads) is what search-service does.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableCaching
@EnableScheduling
@ComponentScan(basePackages = {
        "com.samato.restaurantservice",
        "com.samato.shared",
        "com.samato.sharedkafka"
})
public class RestaurantServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RestaurantServiceApplication.class, args);
    }
}
