package com.samato.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

/**
 * JwtDecoder for the gateway, configured to fetch the public key from
 * auth-service's JWKS endpoint.
 *
 * Why at the gateway specifically?
 *   The gateway is the **edge** — it sees every request. Validating here
 *   means downstream services only see authenticated traffic. They can
 *   also re-validate (defense in depth) — see user-service's SecurityConfig.
 *
 * Why a ReactiveJwtDecoder (not the servlet JwtDecoder)?
 *   The gateway is WebFlux-reactive. Spring Security's WebFlux filter
 *   chain needs a {@link ReactiveJwtDecoder}; the servlet {@code
 *   JwtDecoder} only works in a blocking servlet context. The two
 *   share a JWKS URI but are otherwise separate types.
 *
 * Spring caches the JWKS internally; on key rotation, a new `kid` causes
 * a refetch. No restart required.
 */
@Configuration
public class JwtConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }
}
