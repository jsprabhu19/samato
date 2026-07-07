package com.samato.orderservice.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Feign client to restaurant-service. Used by the saga to validate the
 * order's restaurant and menu items before charging the customer.
 *
 * Auth: this service propagates the JWT through (the gateway injects
 * X-User-Id, but for service-to-service we use a separate
 * client_credentials token — see the {@code AuthClient} helper that
 * fetches a service token at startup). For Phase 4 we use a simpler
 * model: a service-to-service token in the Feign request interceptor.
 *
 * Resilience4j: annotated with @CircuitBreaker at the call site, NOT
 * here, because the breaker's policy depends on the call's importance
 * (a quick health check is allowed to fail; a payment call is not).
 */
@FeignClient(
        name = "samato-restaurant-service",
        fallbackFactory = RestaurantClientFallback.class
)
public interface RestaurantClient {

    @GetMapping("/api/restaurants/{id}")
    RestaurantDtos.RestaurantSummary getRestaurant(@PathVariable("id") UUID id);

    @GetMapping("/api/restaurants/{id}/menu")
    RestaurantDtos.MenuResponse getMenu(@PathVariable("id") UUID id,
                                        @RequestParam("ids") String menuItemIds);
}
