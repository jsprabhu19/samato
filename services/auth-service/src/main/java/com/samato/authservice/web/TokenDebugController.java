package com.samato.authservice.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Debug endpoint for the bible: shows the parsed JWT claims.
 * Useful when wiring up the gateway and downstream services — you can
 * curl this with a token to see exactly what's in it.
 *
 * Not exposed via the gateway (it's auth-service's internal debug). In
 * production, gate this behind an `actuator/info`-style access policy.
 */
@RestController
@RequestMapping("/api/auth")
public class TokenDebugController {

    @GetMapping("/debug/token")
    public Object debug(@AuthenticationPrincipal Jwt jwt) {
        return new DebugView(
                jwt.getSubject(),
                jwt.getIssuer().toString(),
                jwt.getAudience(),
                jwt.getExpiresAt(),
                jwt.getClaimAsString("user_id"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsStringList("roles") != null
                        ? Set.copyOf(jwt.getClaimAsStringList("roles"))
                        : Set.of()
        );
    }

    public record DebugView(
            String sub, Object iss, java.util.List<String> aud,
            java.time.Instant exp, String userId, String email, Set<String> roles) {}
}
