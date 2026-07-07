package com.samato.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JwtDecoder for the gateway, configured to fetch the public key from
 * auth-service's JWKS endpoint.
 *
 * Why at the gateway specifically?
 *   The gateway is the **edge** — it sees every request. Validating here
 *   means downstream services only see authenticated traffic. They can
 *   also re-validate (defense in depth) — see user-service's SecurityConfig.
 *
 * Spring caches the JWKS internally; on key rotation, a new `kid` causes
 * a refetch. No restart required.
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder
                .withJwkSetUri("http://localhost:9000/.well-known/jwks.json")
                .build();
    }
}
