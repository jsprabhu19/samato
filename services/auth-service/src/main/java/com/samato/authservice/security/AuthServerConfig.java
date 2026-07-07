package com.samato.authservice.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.http.MediaType;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Spring Authorization Server configuration.
 *
 * Three things wired up here:
 *   1. **Token endpoint** (the OAuth2 authorization server filter chain).
 *   2. **Default security** for /login, /logout, etc. (the Spring-provided
 *      forms; we don't use them but the AS needs them present to render).
 *   3. **RSA signing keys** for the JWT (RS256) + the JWKS endpoint that
 *      other services use to verify tokens.
 *
 * Interview talking points:
 *   Q: "Why RS256 not HS256?"
 *   A: Asymmetric. The auth-service signs with a private key; other services
 *      verify with the public key. They NEVER need the signing key, so the
 *      blast radius of a compromised service is much smaller. With HS256
 *      (symmetric) every verifier needs the same secret — every service
 *      becomes a potential leak point.
 *
 *   Q: "Why a fresh key pair per service start?"
 *   A: Convenient for dev (no external KMS needed). In production you'd
 *      load keys from a KMS/HSM/Vault and back them with a `kid` you
 *      rotate through (see {@link JwtKeyConfig}).
 *
 *   Q: "What if the signing key is rotated?"
 *   A: The JWKS endpoint can publish MULTIPLE keys, each with a different
 *      `kid`. Tokens are signed with the latest; verifiers fetch the JWKS
 *      and find the right key by `kid`. Phase 2 keeps it simple (one key);
 *      key rotation is a Phase 8 hardening item.
 */
@Configuration
public class AuthServerConfig {

    /**
     * Filter chain for the OAuth2 endpoints: /oauth2/token, /oauth2/jwks,
     * /.well-known/openid-configuration, etc.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(org.springframework.security.config.Customizer.withDefaults());

        http
                // Reject unauthenticated requests to AS endpoints with 401,
                // not the default HTML login page (REST convention).
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        ((request, response, ex) -> response.setStatus(401)),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(rs -> rs.jwt(org.springframework.security.config.Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Filter chain for the default Spring Security: everything else.
     * We permit /api/auth/login and /api/auth/register; protect /api/auth/me.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/register",
                                "/api/auth/.well-known/**", "/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * The password encoder used for both user passwords and OAuth client secrets.
     *
     * BCrypt cost 12: ~250ms per hash. Fine for login; would be a problem for
     * a per-request path. Cost factor is configurable via env if you need to
     * dial it down in dev (e.g. CI runners).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * RSA key pair used to sign JWTs.
     *
     * In production: load from KMS / vault. The pair here is generated once
     * at startup — fine for dev because tokens issued by this process are
     * only consumed by processes that fetch the JWKS (and the JWKS exposes
     * the public half). Never log or expose the private key.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair kp = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) kp.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) kp.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /**
     * JwtDecoder used by /api/auth/me (the auth service validating its own
     * tokens). Other services use their own decoder configured to fetch the
     * JWKS from auth-service's /.well-known/jwks.json.
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Authorization server settings — issuer URL.
     * In dev: http://localhost:9000 (this service's port)
     * In prod: set to the public issuer URL via env.
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer("http://localhost:9000")
                .build();
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate RSA key pair", e);
        }
    }
}
