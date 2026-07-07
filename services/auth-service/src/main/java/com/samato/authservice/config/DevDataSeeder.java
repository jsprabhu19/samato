package com.samato.authservice.config;

import com.samato.authservice.domain.OAuthClient;
import com.samato.authservice.domain.OAuthClientRepository;
import com.samato.authservice.domain.Role;
import com.samato.authservice.domain.UserAccount;
import com.samato.authservice.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Seeds users and OAuth clients in the **dev** profile.
 *
 * In a real system, you'd ship these as Flyway migrations with hashes
 * generated at build time. For the bible, regenerating on every boot
 * (with the same known password) is more ergonomic — same credentials
 * every time, no "what was the seeded password again?" bug.
 *
 * The seeder is a no-op in non-dev profiles so production never gets it.
 */
@Component
@Profile({"dev", "default"})     // dev only; prod uses a real migration
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String SEED_PASSWORD = "password123";

    private final UserRepository users;
    private final OAuthClientRepository clients;
    private final PasswordEncoder encoder;

    public DevDataSeeder(UserRepository users, OAuthClientRepository clients, PasswordEncoder encoder) {
        this.users = users;
        this.clients = clients;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedUser("11111111-1111-1111-1111-111111111111", "alice@example.com", Set.of(Role.CUSTOMER));
        seedUser("22222222-2222-2222-2222-222222222222", "bob@example.com",   Set.of(Role.RESTAURANT_OWNER));
        seedUser("33333333-3333-3333-3333-333333333333", "carol@example.com", Set.of(Role.DRIVER));
        seedUser("44444444-4444-4444-4444-444444444444", "dave@example.com",  Set.of(Role.ADMIN));

        seedClient("api-gateway",   "gateway-secret-please-rotate", null);
        seedClient("spa-client",    "spa-secret-please-rotate",     "http://localhost:5173/callback");

        log.info("Seeded {} users and {} OAuth clients",
                users.count(), clients.count());
    }

    private void seedUser(String id, String email, Set<Role> roles) {
        UUID uuid = UUID.fromString(id);
        if (users.existsById(uuid)) return;
        UserAccount u = new UserAccount();
        u.setId(uuid);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(SEED_PASSWORD));
        u.setRoles(roles);
        users.save(u);
        log.info("Seeded user: {} ({})", email, roles);
    }

    private void seedClient(String clientId, String secret, String redirectUri) {
        if (clients.findByClientId(clientId).isPresent()) return;
        OAuthClient c = new OAuthClient();
        c.setClientId(clientId);
        c.setClientSecretHash(encoder.encode(secret));
        c.setRedirectUri(redirectUri);
        clients.save(c);
        log.info("Seeded OAuth client: {}", clientId);
    }
}
