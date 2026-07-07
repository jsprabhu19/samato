package com.samato.userservice.client;

import com.samato.shared.errors.DomainException;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Set;
import java.util.UUID;

/**
 * Talks to the auth-service to look up a user.
 *
 * Why Feign and not RestTemplate/WebClient?
 *   - Feign gives us a typed interface; the URL, headers, serialization
 *     are all derived from the method signature.
 *   - Plays nicely with Resilience4j (`@CircuitBreaker`, `@Retry` on the
 *     method) — we add that in Phase 7.
 *   - Plays nicely with Eureka: the `name` is the service id registered
 *     in Eureka, not a host.
 *
 * Interview tip: prefer the **service name** in `name=` over a hard URL —
 * the load balancer + Eureka handle discovery.
 */
@FeignClient(name = "samato-auth-service", fallback = AuthClientFallback.class)
public interface AuthClient {

    @GetMapping("/api/auth/me")
    AuthMeResponse me(@RequestHeader("Authorization") String bearer);

    record AuthMeResponse(String id, String email, Set<String> roles) {}
}
