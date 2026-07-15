package com.samato.apigateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;

/**
 * Spring Security for the reactive gateway.
 *
 * What this does:
 * - Permits `/api/auth/**` (login, register) and the gateway's own
 * actuator without a token.
 * - Wires the {@link JwtDecoder} into Spring Security's reactive
 * resource-server filter chain. The decoder fetches the public
 * key from auth-service's `/.well-known/jwks.json` and caches it.
 * - On valid JWT, the request passes through; on missing/invalid
 * token, returns 401.
 *
 * Why does the security chain do JWT validation, not {@link JwtAuthFilter}?
 * In WebFlux, Spring Security installs itself as a {@code WebFilter}
 * that runs BEFORE the Spring Cloud Gateway filter chain. A
 * {@code GlobalFilter} like {@code JwtAuthFilter} runs AFTER — so
 * without wiring JWT validation into Spring Security here, every
 * request to a protected route is rejected with an empty 401 before
 * the gateway even sees it. The previous version of this class
 * delegated JWT validation entirely to {@code JwtAuthFilter}, which
 * is unreachable in practice.
 *
 * {@code JwtAuthFilter} still runs and does useful work: it injects
 * {@code X-User-Id} and {@code X-User-Roles} headers into the
 * forwarded request so downstream services don't have to re-parse
 * the JWT.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

        @Bean
        public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
                return http
                                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                                .authorizeExchange(exchange -> exchange
                                                .pathMatchers("/api/auth/**").permitAll()
                                                .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                                                .anyExchange().authenticated())
                                // Resource-server JWT validation. Spring Security picks up
                                // the JwtDecoder bean (configured in JwtConfig to fetch
                                // auth-service's /.well-known/jwks.json) and verifies each
                                // bearer token. The chain caches the JWKS internally — key
                                // rotation triggers a refetch on a `kid` miss without restart.
                                .oauth2ResourceServer(oauth2 -> oauth2
                                                .jwt(org.springframework.security.config.Customizer.withDefaults()))
                                .exceptionHandling(eh -> eh
                                                .authenticationEntryPoint(new HttpStatusServerEntryPoint(
                                                                HttpStatus.UNAUTHORIZED)))
                                .build();
        }
}
