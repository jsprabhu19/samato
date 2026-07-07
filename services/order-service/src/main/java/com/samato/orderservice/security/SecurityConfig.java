package com.samato.orderservice.security;

import com.samato.orderservice.api.FeignAuthForwarder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Security config for order-service.
 *
 * The service is a pure resource server. The gateway already validated
 * the JWT and forwarded X-User-Id, but we re-validate the JWT for two
 * reasons:
 *
 *   1. Defense in depth — if the gateway is misconfigured, the inner
 *      services still reject unauthenticated calls.
 *
 *   2. We need the JWT claims (subject, roles) for @PreAuthorize.
 *
 * The JWK Set URI points at auth-service. JWKS keys are cached and
 * refreshed automatically by Nimbus.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
            )
            // Capture the bearer token for downstream Feign calls
            .addFilterAfter(new BearerTokenCaptureFilter(), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    /**
     * Map the JWT's `roles` claim (a List<String>) into Spring Security
     * authorities prefixed with ROLE_. So a JWT containing roles=[CUSTOMER]
     * becomes a user with authority ROLE_CUSTOMER — which is what
     * @PreAuthorize("hasRole('CUSTOMER')") checks against.
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = scopes.convert(jwt);
            if (authorities == null) authorities = List.of();
            Object claim = jwt.getClaim("roles");
            List<GrantedAuthority> roleAuths = List.of();
            if (claim instanceof List<?> roles) {
                roleAuths = roles.stream()
                        .map(Object::toString)
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .collect(Collectors.toList());
            }
            return Stream.concat(authorities.stream(), roleAuths.stream()).collect(Collectors.toList());
        });
        return converter;
    }

    /**
     * Captures the bearer token from each request so it can be forwarded
     * to downstream services via the Feign interceptor.
     */
    static class BearerTokenCaptureFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                FeignAuthForwarder.set(auth.substring("Bearer ".length()));
            }
            try {
                chain.doFilter(request, response);
            } finally {
                FeignAuthForwarder.clear();
            }
        }
    }
}
