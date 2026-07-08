package com.samato.authservice.web;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.samato.authservice.domain.UserAccount;
import com.samato.authservice.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dev-only token endpoint.
 *
 *   - Active only in the `dev` profile (or any non-prod profile). Disabled
 *     in production via the {@code @Profile} guard.
 *   - Issues a real RS256 JWT for any seeded user, bypassing the OAuth2
 *     grant-type flow. Same shape as a token issued by Spring AS would
 *     have produced: subject = user UUID, claims = user_id, email, roles.
 *   - We need this because Spring Authorization Server 1.3.x dropped
 *     the resource-owner password grant entirely, but the bible's design
 *     depends on it. This is a workaround for local testing only; it
 *     is NOT a substitute for the real OAuth flow.
 *
 * Trade-offs:
 *   - Anyone with network access to the dev profile can mint a token
 *     for any seeded user. The dev seeder uses public passwords
 *     (alice/password123), so this is acceptable for local dev. In
 *     staging/prod, the profile guard ensures this is dead code.
 *   - No client_id / audience validation: the token is signed but not
 *     bound to a client. The gateway and downstream services validate
 *     the signature, expiration, and claims — that's all we need for
 *     the saga to run.
 *
 * If you're reading this in prod — the @Profile guard means it
 * shouldn't be there. If you see it, the profile config is wrong.
 */
@RestController
@RequestMapping("/api/auth")
@Profile({"dev", "default"})
public class DevTokenController {

    private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);
    private static final long TOKEN_TTL_MINUTES = 60;

    private final UserRepository users;
    private final JwtEncoder jwtEncoder;

    public DevTokenController(UserRepository users, JWKSource<SecurityContext> jwkSource) {
        this.users = users;
        // Build an encoder that signs with the same RSA key the
        // authorization server publishes in its JWKS endpoint. That
        // way the gateway's signature verification (which fetches
        // /.well-known/jwks.json) accepts tokens issued by this
        // endpoint transparently.
        //
        // The jwkSource bean (see AuthServerConfig#jwkSource) returns
        // an ImmutableJWKSet holding a single RSAKey that already has
        // BOTH the public and private parts. NimbusJwtEncoder takes a
        // JWKSource directly — no need to pull keys out and re-wrap.
        this.jwtEncoder = new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Issue a dev JWT.
     *
     *   GET /api/auth/dev-token?email=alice@example.com
     *
     * Returns:
     *   { "access_token": "...", "token_type": "Bearer", "expires_in": 3600 }
     */
    @GetMapping("/dev-token")
    public ResponseEntity<?> devToken(@RequestParam("email") String email) {
        UserAccount user = users.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "USER_NOT_FOUND",
                                 "message", "No seeded user with email " + email));
        }

        Instant now = Instant.now();
        Instant exp = now.plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES);
        List<String> roles = user.getRoles().stream()
                .map(Enum::name)
                .collect(Collectors.toList());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://localhost:9000")
                .subject(user.getId().toString())
                .audience(List.of("samato-api-gateway"))
                .issuedAt(now)
                .expiresAt(exp)
                .claim("user_id", user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .build();

        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();

        log.info("Issued dev token for {} (sub={}, roles={})", email, user.getId(), roles);

        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", TOKEN_TTL_MINUTES * 60,
                "scope", "openid profile read write"
        ));
    }
}
