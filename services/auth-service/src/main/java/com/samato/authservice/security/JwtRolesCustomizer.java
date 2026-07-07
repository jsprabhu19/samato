package com.samato.authservice.security;

import com.samato.authservice.domain.UserAccount;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Customizes the JWT we issue so it contains what downstream services need.
 *
 * Claims we add:
 *   - `roles`         — list of role names, e.g. ["CUSTOMER"]. Services use this
 *                       for fine-grained authorization (Spring maps it via
 *                       JwtAuthenticationConverter → SimpleGrantedAuthority).
 *   - `user_id`       — the internal UUID, so services can identify the caller
 *                       without an extra DB lookup.
 *   - `email`         — handy for logs / audit. PII; consider hashing for logs.
 *
 * Why customize at issuance time and not at consumption time?
 *   - Putting roles in the token means services can do authz **without**
 *     calling auth-service. That's the whole point of JWT.
 *   - If a role changes, the user has to re-login (or you maintain a revocation
 *     list). Trade-off: freshness vs. latency. The bible accepts the trade-off
 *     and uses short access-token lifetimes (15 minutes) + refresh tokens.
 */
@Component
public class JwtRolesCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    @Override
    public void customize(JwtEncodingContext context) {
        // Only customize the access token (skip refresh + id token variants).
        if (!"access_token".equals(context.getTokenType().getValue())) {
            return;
        }

        Authentication principal = context.getPrincipal();
        if (principal == null) {
            return;
        }

        // During password grant, the principal is UsernamePasswordAuthenticationToken
        // with the UserAccount as its `principal`.
        Object p = principal.getPrincipal();
        if (!(p instanceof UserAccount u)) {
            return;
        }

        Set<String> roles = u.getRoles().stream().map(Enum::name).collect(Collectors.toSet());
        context.getClaims().claim("roles", roles);
        context.getClaims().claim("user_id", u.getId().toString());
        context.getClaims().claim("email", u.getEmail());
    }
}
