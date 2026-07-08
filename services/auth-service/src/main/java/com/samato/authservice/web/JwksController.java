package com.samato.authservice.web;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the JWKS at the OIDC-standard {@code /.well-known/jwks.json}
 * path. Spring Authorization Server publishes the same set at
 * {@code /oauth2/jwks} by default, but most of our services and the
 * gateway are configured to fetch from the .well-known path — that's
 * the path the OIDC discovery doc advertises for clients that don't
 * pin a specific URL.
 *
 * Why this controller exists:
 *   The bible's service configs use {@code /.well-known/jwks.json} as
 *   the JWKS URL (the canonical OIDC location). Spring AS's default
 *   filter chain (authServerFilterChain, order 1) handles the actual
 *   {@code /oauth2/jwks} but does NOT cover {@code /.well-known/**}.
 *   The default security chain (order 2) requires authentication by
 *   default, so the .well-known path returns 403 — which is why every
 *   downstream service has been failing JWT verification.
 *
 * Trade-off:
 *   The set of keys published here is exactly the same as what
 *   {@code /oauth2/jwks} returns. The two are interchangeable.
 *   The .well-known path is the OIDC-canonical one; the /oauth2 path
 *   is Spring AS's default. We support both.
 */
@RestController
@RequestMapping("/.well-known")
public class JwksController {

    private final JWKSource<SecurityContext> jwkSource;

    public JwksController(JWKSource<SecurityContext> jwkSource) {
        this.jwkSource = jwkSource;
    }

    @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String jwks() {
        try {
            // Select all keys regardless of type. The matcher's `publicOnly`
            // doesn't matter here because we filter to public below with
            // JWKSet.toString(true).
            JWKMatcher matcher = new JWKMatcher.Builder()
                    .publicOnly(false)
                    .build();
            List<JWK> keys = jwkSource.get(new JWKSelector(matcher), null);
            JWKSet publicSet = new JWKSet(keys);
            return publicSet.toString(true /* publicOnly */);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load JWKS", e);
        }
    }
}
