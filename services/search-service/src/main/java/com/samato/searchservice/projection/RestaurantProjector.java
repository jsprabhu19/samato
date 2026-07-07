package com.samato.searchservice.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.events.RestaurantCreatedEvent;
import com.samato.events.RestaurantUpdatedEvent;
import com.samato.sharedkafka.events.DomainEvent;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * The actual projection: turns a DomainEvent into an OpenSearch document.
 *
 * Why a flat Map (not a strongly-typed document)?
 *   - OpenSearch is schemaless. We can add fields without a migration.
 *   - Decouples the consumer code from the OpenSearch mapping.
 *   - Easy to add a new event type by just mapping its fields.
 *
 * What ends up in the index:
 *   - `id`            — the restaurantId (also used as _id, so re-delivery is idempotent)
 *   - `name`          — searchable text + .keyword sub-field for exact match
 *   - `description`   — searchable text
 *   - `cuisine`       — keyword for filtering ("Italian", "Chinese", ...)
 *   - `city`          — keyword for filtering
 *   - `location`      — geo_point for "near me" queries
 *   - `updatedAt`     — for sort-by-recent
 */
@Component
public class RestaurantProjector {

    private static final Logger log = LoggerFactory.getLogger(RestaurantProjector.class);
    public static final String INDEX = "restaurants";

    private final RestHighLevelClient osClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestaurantProjector(RestHighLevelClient osClient) {
        this.osClient = osClient;
    }

    public void apply(DomainEvent ev) {
        try {
            Map<String, Object> doc = new HashMap<>();
            if (ev instanceof RestaurantCreatedEvent c) {
                doc.put("id", c.getRestaurantId().toString());
                doc.put("name", c.getName());
                doc.put("description", c.getDescription());
                doc.put("cuisine", c.getCuisine());
                doc.put("city", c.getCity());
                doc.put("address", c.getAddress());
                doc.put("location", Map.of("lat", c.getLatitude(), "lon", c.getLongitude()));
                doc.put("createdAt", c.getOccurredAt());
                doc.put("updatedAt", c.getOccurredAt());
            } else if (ev instanceof RestaurantUpdatedEvent u) {
                doc.put("id", u.getRestaurantId().toString());
                doc.put("name", u.getName());
                doc.put("description", u.getDescription());
                doc.put("cuisine", u.getCuisine());
                doc.put("city", u.getCity());
                doc.put("address", u.getAddress());
                doc.put("location", Map.of("lat", u.getLatitude(), "lon", u.getLongitude()));
                doc.put("updatedAt", u.getOccurredAt());
            } else {
                log.warn("Unknown event type, skipping: {}", ev.getClass().getName());
                return;
            }

            // Upsert: _id = restaurantId. Re-delivery is a no-op.
            IndexRequest req = new IndexRequest(INDEX)
                    .id(doc.get("id").toString())
                    .source(mapper.writeValueAsString(doc), XContentType.JSON);
            IndexResponse resp = osClient.index(req, RequestOptions.DEFAULT);
            log.debug("Projected {} -> version={}", doc.get("id"), resp.getVersion());
        } catch (Exception e) {
            throw new RuntimeException("OpenSearch projection failed: " + e.getMessage(), e);
        }
    }
}
