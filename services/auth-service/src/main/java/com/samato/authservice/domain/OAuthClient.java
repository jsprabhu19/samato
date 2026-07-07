package com.samato.authservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered OAuth2 client.
 *
 * In the bible we seed two clients from Flyway: the API gateway (machine-to-machine
 * for service-to-service calls, e.g. order-service asking auth-service to verify
 * a token) and a "spa" client for the future web app.
 *
 * `clientSecretHash` is BCrypt. We compare the presented secret on the
 * token endpoint with BCrypt.matches; that adds a constant-factor cost per
 * request but is fine for human-scale token issuance.
 */
@Entity
@Table(name = "oauth_clients", indexes = {
        @Index(name = "idx_oauth_clients_client_id", columnList = "client_id", unique = true)
})
public class OAuthClient {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false)
    private String clientSecretHash;

    @Column(name = "redirect_uri")
    private String redirectUri;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecretHash() { return clientSecretHash; }
    public void setClientSecretHash(String clientSecretHash) { this.clientSecretHash = clientSecretHash; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
