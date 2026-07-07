package com.samato.searchservice.config;

import org.apache.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenSearch client config.
 *
 * We use the legacy high-level client (RestHighLevelClient) for simplicity.
 * The new java client (org.opensearch.client.opensearch.*) is more flexible
 * and recommended for new projects; out of scope for the bible.
 *
 * Connection settings come from `samato.opensearch.host` and `.port`.
 * In a real product, you'd also configure:
 *   - TLS (https + truststore)
 *   - Auth (basic auth, API key, or AWS sigv4)
 *   - Connection pool size, timeouts
 */
@Configuration
public class OpenSearchConfig {

    @Value("${samato.opensearch.host:localhost}")
    private String host;

    @Value("${samato.opensearch.port:9200}")
    private int port;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient openSearchClient() {
        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, "http"));
        return new RestHighLevelClient(builder);
    }
}
