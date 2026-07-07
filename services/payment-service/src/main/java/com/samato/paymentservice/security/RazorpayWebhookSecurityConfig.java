package com.samato.paymentservice.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Separate, higher-precedence security chain for Razorpay webhooks.
 *
 *   - Matches only the webhook path.
 *   - PermitAll at the Spring layer — the controller does the real
 *     check (HMAC signature verification) so we have a clear
 *     "401 means signature bad" path.
 *
 * This bean is annotated {@code @Order(1)} so it runs BEFORE the
 * default JWT chain. The default chain's matcher doesn't include
 * the webhook path, so it never sees webhook requests.
 */
@Configuration
@EnableWebSecurity
public class RazorpayWebhookSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/payments/webhooks/**")
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
