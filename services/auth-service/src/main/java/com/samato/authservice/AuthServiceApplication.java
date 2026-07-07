package com.samato.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Auth Service — the OAuth2 / OIDC authorization server for the platform.
 *
 * What it owns:
 *   1. **User accounts** — passwords (BCrypt), roles, basic profile.
 *   2. **OAuth2 client registrations** — the API gateway and any service
 *      that needs a machine-to-machine token registers here.
 *   3. **JWT signing keys** — RS256, published as a JWKS at /.well-known/jwks.json.
 *   4. **Token issuance** — code exchange, password grant (we use it
 *      because the bible runs a custom login form), refresh tokens.
 *
 * Why a single auth service instead of using Keycloak/Auth0?
 *   - In the bible we want to *see* and *own* the code, not just configure.
 *   - For a real product I'd actually recommend Keycloak / Auth0 / Cognito.
 *     The point of the bible is: "here's what's happening under the hood."
 *
 * Security model in 30 seconds:
 *   - Client hits {@code POST /api/auth/login} with username/password.
 *   - We validate, mint a JWT signed with our private RSA key.
 *   - The JWT contains: sub (user id), iss (auth-service), aud (api-gateway),
 *     scope, roles, exp.
 *   - Other services fetch our public key from {@code /.well-known/jwks.json}
 *     and validate the signature locally. No per-request call to auth-service.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
