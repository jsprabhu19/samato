package com.samato.searchservice.config;

import org.apache.http.HttpHost;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.CreateIndexResponse;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the OpenSearch index on startup if it doesn't exist.
 *
 * Why this is a startup hook (not a migration):
 *   - The index is schemaless in OpenSearch. We can create it with
 *     a sensible mapping and let it grow.
 *   - In a real product, the mapping would be a separate "schema"
 *     artifact (e.g. an OpenSearch template) so multiple services
 *     can use the same index.
 *   - This is idempotent: a no-op if the index already exists.
 *
 * The mapping defines:
 *   - `name` and `description` as full-text searchable
 *   - `cuisine` and `city` as keyword for filtering
 *   - `location` as geo_point for "near me" queries
 */
@Component
public class OpenSearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexInitializer.class);
    public static final String INDEX = "restaurants";

    private final RestHighLevelClient client;

    public OpenSearchIndexInitializer(RestHighLevelClient client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        try {
            boolean exists = client.indices().exists(
                    new GetIndexRequest(INDEX),
                    RequestOptions.DEFAULT);
            if (exists) {
                log.info("OpenSearch index '{}' already exists", INDEX);
                return;
            }

            String mapping = """
                {
                  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": {
                    "properties": {
                      "id":          { "type": "keyword" },
                      "name":        { "type": "text",    "fields": { "raw": { "type": "keyword" } } },
                      "description": { "type": "text" },
                      "cuisine":     { "type": "keyword" },
                      "city":        { "type": "keyword" },
                      "address":     { "type": "text" },
                      "location":    { "type": "geo_point" },
                      "createdAt":   { "type": "date", "format": "epoch_millis" },
                      "updatedAt":   { "type": "date", "format": "epoch_millis" }
                    }
                  }
                }
                """;

            CreateIndexRequest req = new CreateIndexRequest(INDEX).source(mapping, XContentType.JSON);
            CreateIndexResponse resp = client.indices().create(req, RequestOptions.DEFAULT);
            log.info("Created OpenSearch index '{}': acknowledged={}", INDEX, resp.isAcknowledged());
        } catch (Exception e) {
            // Don't fail startup if OpenSearch isn't reachable yet.
            // The consumer will retry, and the indexer can run on first write.
            log.warn("Could not initialize OpenSearch index '{}': {}", INDEX, e.getMessage());
        }
    }
}
