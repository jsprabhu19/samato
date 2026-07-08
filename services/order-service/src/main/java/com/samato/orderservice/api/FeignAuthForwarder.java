package com.samato.orderservice.api;

import com.samato.shared.observability.FeignCorrelationIdInterceptor;
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
 * Two sources for the outgoing bearer:
 *   1. The user's JWT, captured by the request-thread filter.
 *   2. A long-lived service token, fetched at startup. Used by the
 *      saga poller and any other background thread that doesn't have
 *      a per-request user token. See {@link ServiceTokenProvider}.
 *
 * Why not fetch a service-to-service token via client_credentials?  In
 * Phase 4 we trust the inbound token (gateway-validated). Phase 5+
 * should add proper service tokens so the same JWT isn't replayed
 * downstream — for now this is a pragmatic shortcut.
 */
@Configuration
public class FeignAuthForwarder {

    private static final ThreadLocal<String> CURRENT_TOKEN = new ThreadLocal<>();

    private final ServiceTokenProvider serviceTokenProvider;

    public FeignAuthForwarder(ServiceTokenProvider serviceTokenProvider) {
        this.serviceTokenProvider = serviceTokenProvider;
    }

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
            if (token == null || token.isBlank()) {
                // No per-request token — fall back to the service token
                // (background threads, poller, scheduled jobs).
                token = serviceTokenProvider.getServiceToken();
            }
            if (token != null && !token.isBlank()) {
                template.header("Authorization", "Bearer " + token);
            }
        };
    }

    /**
     * Propagates the correlationId (and trace/span ids) from MDC onto
     * every outgoing call. Without this, restaurant-service would
     * generate its own correlationId and the saga would be uncorrelatable
     * across services. Lives in the shared module so any service that
     * depends on shared gets it for free when it adds a Feign client.
     */
    @Bean
    public FeignCorrelationIdInterceptor correlationIdInterceptor() {
        return new FeignCorrelationIdInterceptor();
    }
}
