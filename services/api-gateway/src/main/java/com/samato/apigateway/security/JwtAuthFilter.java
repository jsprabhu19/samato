package com.samato.apigateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Real JWT validation against auth-service's JWKS.
 *
 * Flow:
 *   1. Read the `Authorization: Bearer <token>` header.
 *   2. Try to validate the JWT using the {@link JwtDecoder} (which fetches
 *      the public key from auth-service's `/.well-known/jwks.json` and
 *      caches it).
 *   3. If valid, forward the request to the downstream with two
 *      additional headers:
 *        - `X-User-Id`    — the user's UUID (from `sub` or `user_id` claim)
 *        - `X-User-Roles` — comma-separated role list
 *   4. If invalid, return a 401 JSON body.
 *
 * Why inject `X-User-Id` and `X-User-Roles`?
 *   Downstream services don't have to parse the JWT themselves; they read
 *   the headers and trust them because the gateway has already validated
 *   the signature. (In a paranoid setup, downstream services ALSO validate
 *   the JWT themselves — defense in depth. We do this in user-service.)
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLES_HEADER = "X-User-Roles";

    private final ReactiveJwtDecoder jwtDecoder;

    public JwtAuthFilter(ReactiveJwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Public routes — auth-service login/register and the gateway's own actuator.
        if (path.startsWith("/api/auth/") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, "Missing or malformed Authorization header");
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return reject(exchange, "Empty bearer token");
        }

        return jwtDecoder.decode(token)
                .flatMap(jwt -> {
                    final String userId;
                    String userIdClaim = jwt.getClaimAsString("user_id");
                    if (userIdClaim == null) userIdClaim = jwt.getSubject();
                    userId = userIdClaim == null ? "" : userIdClaim;
                    final var roles = jwt.getClaimAsStringList("roles");
                    final String rolesHeader = roles == null ? "" : String.join(",", roles);

                    return chain.filter(exchange.mutate()
                            .request(r -> r
                                    .header(USER_ID_HEADER, userId)
                                    .header(ROLES_HEADER, rolesHeader))
                            .build());
                })
                .onErrorResume(JwtException.class, ex -> {
                    log.debug("JWT rejected: {}", ex.getMessage());
                    return reject(exchange, "Invalid token: " + ex.getMessage());
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"status":401,"code":"UNAUTHORIZED","message":"%s"}""".formatted(reason);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // Run early so we reject before any other filter burns cycles.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
