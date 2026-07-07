package com.samato.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API Gateway — the single entry point for all clients.
 *
 * Responsibilities (in order of how often they come up in interviews):
 *   1. **Routing** — translate `GET /api/orders/...` to a downstream service.
 *   2. **Authentication** — validate JWT once, attach user identity to the request.
 *   3. **Rate limiting** — protect downstreams from abuse.
 *   4. **Aggregation** (later) — combine responses from multiple services.
 *   5. **Protocol translation** (later) — REST in, gRPC out.
 *   6. **Cross-cutting** — tracing, CORS, request/response logging.
 *
 * Why Spring Cloud Gateway and not Zuul?
 *   - Built on WebFlux (reactive, non-blocking).
 *   - First-class Spring Cloud support; Zuul is maintenance mode.
 *
 * Service discovery is enabled so we can route by service name
 * (`lb://ORDER-SERVICE`) and the gateway looks up the URL from Eureka.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
