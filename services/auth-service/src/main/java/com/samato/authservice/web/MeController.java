package com.samato.authservice.web;

import com.samato.authservice.domain.Role;
import com.samato.authservice.domain.UserAccount;
import com.samato.authservice.domain.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Returns info about the currently authenticated user.
 *
 * We read it from the JWT (validated locally via the public key from
 * auth-service's JWKS). No DB hit, no auth-service round-trip — the
 * whole point of JWT.
 *
 * Downstream services that want to do this same trick include
 * spring-boot-starter-oauth2-resource-server and configure
 * `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.
 */
@RestController
@RequestMapping("/api/auth")
public class MeController {

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        // Fetch fresh roles from DB so revocations take effect immediately
        // (a deleted user gets a 404 here, even if their token is still valid).
        UUID id = UUID.fromString(jwt.getSubject());
        UserAccount u = users.findById(id)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("user not found"));

        Set<String> roles = u.getRoles().stream().map(Role::name).collect(Collectors.toSet());
        return new MeResponse(u.getId().toString(), u.getEmail(), roles);
    }

    public record MeResponse(String id, String email, Set<String> roles) {}
}
