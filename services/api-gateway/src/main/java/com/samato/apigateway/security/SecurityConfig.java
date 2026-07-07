package com.samato.apigateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Spring Security for the reactive gateway.
 *
 * What this does (and what it deliberately DOESN'T do):
 *   - Permits `/api/auth/**` (login, register) without a token.
 *   - Requires authentication for everything else.
 *   - Returns 401 (not 403) for unauthenticated requests — REST convention.
 *   - Disables CSRF — gateways are server-to-server; CSRF is for browser apps.
 *   - Leaves the actual JWT *validation* to a GlobalFilter (see JwtAuthFilter).
 *
 * Why split Security config and JwtAuthFilter?
 *   - Security config answers: "is auth required for this path?"
 *   - JwtAuthFilter answers: "is this token valid, and who is the user?"
 *   Splitting them makes each one testable and interview-clarifiable.
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
                        .anyExchange().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .build();
    }
}
