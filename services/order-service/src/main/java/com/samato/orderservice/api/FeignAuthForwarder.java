package com.samato.orderservice.api;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Propagates the inbound JWT into outbound Feign calls. The restaurant
 * service uses spring-security oauth2-resource-server and will reject
 * any call without a valid token.
 *
 * Why not fetch a service-to-service token via client_credentials?  In
 * Phase 4 we trust the inbound token (gateway-validated). Phase 5+
 * should add proper service tokens so the same JWT isn't replayed
 * downstream — for now this is a pragmatic shortcut.
 */
@Configuration
public class FeignAuthForwarder {

    private static final ThreadLocal<String> CURRENT_TOKEN = new ThreadLocal<>();

    /**
     * Used by the security filter chain to capture the incoming
     * Bearer token for the lifetime of the request. Feign calls made
     * during that request will pick it up below.
     */
    public static void set(String token) { CURRENT_TOKEN.set(token); }
    public static void clear()          { CURRENT_TOKEN.remove(); }
    public static String current()      { return CURRENT_TOKEN.get(); }

    @Bean
    public RequestInterceptor bearerTokenInterceptor() {
        return (RequestTemplate template) -> {
            String token = CURRENT_TOKEN.get();
            if (token != null && !token.isBlank()) {
                template.header("Authorization", "Bearer " + token);
            }
        };
    }
}
