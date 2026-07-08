package com.samato.authservice.security;

import com.samato.authservice.domain.OAuthClient;
import com.samato.authservice.domain.OAuthClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bridges our {@code oauth_clients} DB table to Spring Authorization Server's
 * {@link RegisteredClientRepository}. The AS calls this on every token
 * request to authenticate the client (client_id + client_secret).
 *
 * Why a DB-backed client store?
 *   - You can register new clients without redeploying.
 *   - You can disable a client immediately (set `enabled = false`).
 *   - Auditable: a row per client.
 *
 * Trade-off: every token request hits the DB. For high-volume service-to-service
 * tokens, cache the result with a short TTL (e.g. 30s). Out of scope for the bible.
 */
@Component
public class JpaRegisteredClientRepository implements RegisteredClientRepository {

    private final OAuthClientRepository repo;

    public JpaRegisteredClientRepository(OAuthClientRepository repo) {
        this.repo = repo;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        // Not used in the bible; the AS is configured via Flyway seed data.
    }

    @Override
    public RegisteredClient findById(String id) {
        try {
            return repo.findById(UUID.fromString(id))
                    .map(this::toRegisteredClient)
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return repo.findByClientId(clientId)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    private RegisteredClient toRegisteredClient(OAuthClient c) {
        RegisteredClient.Builder b = RegisteredClient.withId(c.getId().toString())
                .clientId(c.getClientId())
                // The DB column holds a real BCrypt hash ($2a$...). Pass it
                // verbatim — DelegatingPasswordEncoder detects the $2a$
                // prefix and uses BCryptPasswordEncoder to compare the
                // presented secret against the stored hash.
                //
                // (Earlier code prepended "{bcrypt}" which caused
                // double-encoding: the framework would call
                // BCryptPasswordEncoder.matches(rawInput, rawInput) which
                // fails the "looks like BCrypt" check on the raw side.)
                .clientSecret(c.getClientSecretHash())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.PASSWORD)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                // Scope strings — these end up in the JWT's `scope` claim.
                .scope("openid")
                .scope("profile")
                .scope("read")
                .scope("write");

        if (c.getRedirectUri() != null && !c.getRedirectUri().isBlank()) {
            b.redirectUri(c.getRedirectUri());
        }
        return b.build();
    }
}
