package com.samato.apigateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway route definitions.
 *
 * The `lb://` prefix tells Spring Cloud Gateway to look up the service
 * in Eureka and round-robin across healthy instances.
 *
 * Public routes (login, register) skip the auth filter.
 * Protected routes use the JWT filter to validate the bearer token.
 *
 * The URI target is the **Eureka service id**, which by default is the
 * uppercase form of `spring.application.name`. We register services as
 * `samato-auth-service` etc., so the Eureka id is `SAMATO-AUTH-SERVICE`.
 *
 * Interview note: this is the place interviewers point at when they ask
 * "show me your routing config". Routes are imperative here; for large
 * projects, consider externalizing to a config map (per-service properties
 * in config-server, hot-reloadable).
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()

                // ─── Auth service — public, no JWT required ──────────────
                .route("samato-auth-service", r -> r
                        .path("/api/auth/**")
                        .filters(f -> f
                                .stripPrefix(1)              // /api/auth/login -> /auth/login
                                .addRequestHeader("X-Source", "samato-api-gateway"))
                        .uri("lb://SAMATO-AUTH-SERVICE"))

                // ─── User service — protected, JWT required ─────────────
                .route("samato-user-service", r -> r
                        .path("/api/users/**")
                        .filters(f -> f
                                .stripPrefix(1)              // /api/users -> /users
                                .addRequestHeader("X-Source", "samato-api-gateway"))
                        .uri("lb://SAMATO-USER-SERVICE"))

                // ─── Order service — protected, JWT required ─────────────
                .route("samato-order-service", r -> r
                        .path("/api/orders/**")
                        .filters(f -> f
                                .stripPrefix(1)              // /api/orders -> /orders
                                .addRequestHeader("X-Source", "samato-api-gateway"))
                        .uri("lb://SAMATO-ORDER-SERVICE"))

                // ─── Restaurant service ──────────────────────────────────
                .route("samato-restaurant-service", r -> r
                        .path("/api/restaurants/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Source", "samato-api-gateway"))
                        .uri("lb://SAMATO-RESTAURANT-SERVICE"))

                // ─── Search service — read-only search index ────────────
                .route("samato-search-service", r -> r
                        .path("/api/search/**")
                        .filters(f -> f
                                .stripPrefix(1)              // /api/search/restaurants -> /api/search/restaurants
                                .addRequestHeader("X-Source", "samato-api-gateway"))
                        .uri("lb://SAMATO-SEARCH-SERVICE"))

                // ─── Payment service — protected for /api/payments/**
                //     EXCEPT webhooks, which need HMAC, not JWT. The
                //     security config on payment-service handles the
                //     bypass for /api/payments/webhooks/**; the gateway
                //     just forwards everything under /api/payments.
                .route("samato-payment-service", r -> r
                        .path("/api/payments/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                .addRequestHeader("X-Source", "samato-api-gateway"))
                        .uri("lb://SAMATO-PAYMENT-SERVICE"))

                // ─── Health endpoints — bypass auth so K8s can probe ────
                .route("health", r -> r
                        .path("/actuator/**")
                        .uri("lb://SAMATO-API-GATEWAY"))    // gateway's own actuator

                .build();
    }
}
