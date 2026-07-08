package com.samato.orderservice.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Holds a long-lived service-to-service JWT used by the saga poller
 * (and any other background thread) to make authenticated Feign calls
 * to other services.
 *
 * Why this exists:
 *   The saga can be driven by the request thread (synchronous) or by
 *   the poller (asynchronous). The request thread has the user's JWT
 *   in {@link FeignAuthForwarder}; the poller thread does NOT. Without
 *   a service token, the poller's Feign calls return 401 and the saga
 *   fails.
 *
 * How it works:
 *   At startup, after the application is ready, we call
 *   {@code GET /api/auth/dev-token?email=service@system} once to obtain
 *   a JWT. The token is cached and reused for all background calls.
 *   For a real system you'd use OAuth2 client_credentials to fetch a
 *   scoped service token; the dev-token endpoint is the Phase 4 shim.
 *
 * Token lifetime:
 *   The dev-token returns a 1-hour token. For Phase 4 we don't refresh
 *   (would need a scheduler) — long enough to demonstrate the saga
 *   but not production-grade. Refresh is a Phase 8 item.
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    private final RestClient http = RestClient.create();

    @Value("${samato.auth.base-url:http://auth-service:9000}")
    private String authBaseUrl;

    @Value("${samato.service-token.email:service@system}")
    private String serviceEmail;

    private volatile String serviceToken;

    @EventListener(ApplicationReadyEvent.class)
    public void fetchAtStartup() {
        try {
            String body = http.get()
                    .uri(authBaseUrl + "/api/auth/dev-token?email=" + serviceEmail)
                    .retrieve()
                    .body(String.class);
            // Response: {"access_token":"...","token_type":"Bearer",...}
            // Crude parsing — works for the dev endpoint. For prod use Jackson.
            String marker = "\"access_token\":\"";
            int i = body.indexOf(marker);
            if (i < 0) throw new IllegalStateException("No access_token in dev-token response: " + body);
            int start = i + marker.length();
            int end = body.indexOf("\"", start);
            this.serviceToken = body.substring(start, end);
            log.info("Fetched service token for {} ({} chars)", serviceEmail, serviceToken.length());
        } catch (Exception e) {
            log.warn("Could not fetch service token at startup ({}). Feign calls from background threads will be unauthenticated.", e.getMessage());
        }
    }

    /**
     * Called by the Feign interceptor when there's no per-request token
     * (e.g. the call is from a scheduler or poller thread). Returns the
     * service token, or null if it couldn't be fetched.
     */
    public String getServiceToken() {
        return serviceToken;
    }
}
