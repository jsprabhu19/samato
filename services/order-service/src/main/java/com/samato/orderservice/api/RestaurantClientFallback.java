package com.samato.orderservice.api;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fallback when restaurant-service is down or slow. We deliberately
 * raise a domain exception — the saga will treat this as a transient
 * failure and either retry (via the resilience4j @Retry) or abort the
 * order with a clear "service unavailable" reason.
 *
 * Why FallbackFactory over Fallback?  The factory receives the cause,
 * which is critical for distinguishing "service is down" from
 * "restaurant not found" from "menu item doesn't exist". A simple
 * Fallback would hide that and cause us to over-retry.
 */
@Component
public class RestaurantClientFallback implements FallbackFactory<RestaurantClient> {

    @Override
    public RestaurantClient create(Throwable cause) {
        return new RestaurantClient() {
            @Override
            public RestaurantDtos.RestaurantSummary getRestaurant(UUID id) {
                throw new RestaurantUnavailableException("Restaurant service unavailable", cause);
            }

            @Override
            public RestaurantDtos.MenuResponse getMenu(UUID id, String menuItemIds) {
                throw new RestaurantUnavailableException("Menu lookup failed", cause);
            }
        };
    }
}
