package com.samato.apigateway.security;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Key resolver for the gateway's built-in rate limiter.
 *
 * Different strategies for different scenarios:
 *   - **Per user** (preferred when JWT is present): X-User-Id from the JWT.
 *   - **Per IP** (fallback for unauthenticated routes): remote IP.
 *
 * Interview note: rate limit decisions often come up. Frame your answer as:
 *   "Per-user for authenticated endpoints, per-IP for public, per-API-key
 *   for partner integrations." Always give a reason, not just the choice.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            String ip = Objects.requireNonNullElse(
                    exchange.getRequest().getRemoteAddress(),
                    exchange.getRequest().getLocalAddress()
            ).getAddress().getHostAddress();
            return Mono.just("ip:" + ip);
        };
    }
}
