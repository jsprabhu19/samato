package com.samato.userservice.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Security for the user service.
 *
 * What it does:
 *   1. Validates the JWT against auth-service's JWKS endpoint.
 *      → The public key is fetched from `http://localhost:9000/.well-known/jwks.json`
 *        and cached. No per-request call to auth-service.
 *   2. Maps the `roles` claim in the JWT to Spring's `ROLE_*` authorities.
 *      → `@PreAuthorize("hasRole('CUSTOMER')")` then "just works".
 *   3. Permits actuator endpoints; requires auth for everything else.
 *   4. Stateless — no sessions, no cookies.
 *
 * This is the same pattern every other service in the bible will follow.
 * Centralize the JWT decoder config in a small helper if you copy-paste it
 * 12 times — but understand each piece first.
 */
@Configuration
@EnableMethodSecurity   // enables @PreAuthorize
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(rs -> rs
                        .jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * JwtDecoder configured with the auth-service JWKS URI.
     * Spring fetches the JWKS lazily and caches it; on key rotation
     * (new `kid`), it'll be picked up automatically.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder
                .withJwkSetUri("http://localhost:9000/.well-known/jwks.json")
                .build();
    }

    /**
     * Maps the `roles` claim in the JWT to Spring's `ROLE_*` authorities.
     * (Default would use `scope` or `scp`; we customize for our claim.)
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
