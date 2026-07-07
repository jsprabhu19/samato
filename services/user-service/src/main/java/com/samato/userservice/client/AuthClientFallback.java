package com.samato.userservice.client;

import com.samato.shared.errors.DomainException;
import org.springframework.stereotype.Component;

/**
 * Fallback for AuthClient when auth-service is down or slow.
 *
 * Resilience4j (configured in Phase 7) routes failures through this.
 * For Phase 2 we throw a clear 503-ish error; Phase 7 turns it into
 * a proper circuit-broken fallback (e.g. serve from a cache).
 *
 * The fallback keeps the upstream's API surface so call sites don't
 * have to handle two different types.
 */
@Component
public class AuthClientFallback implements AuthClient {

    @Override
    public AuthMeResponse me(String bearer) {
        throw new DomainException(
                "AUTH_UNREACHABLE",
                "Auth service is unavailable; please retry shortly",
                503
        );
    }
}
