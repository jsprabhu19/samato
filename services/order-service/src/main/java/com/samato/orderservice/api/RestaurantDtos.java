package com.samato.orderservice.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Internal DTOs that mirror restaurant-service's API surface.
 *
 * Why not import the DTOs from restaurant-service?  Because that would
 * couple the two services at the build level (a single dependency line).
 * In a microservice system, the rule of thumb is:
 *
 *   - We OWN our DTOs.
 *   - We make HTTP calls against a documented external contract.
 *
 * The DTOs are deliberately minimal — we only model the fields we use.
 * If restaurant-service adds a new field, we don't care. If they remove
 * one we use, our parsing will fail (a deliberately loud failure).
 */
public final class RestaurantDtos {

    private RestaurantDtos() {}

    public record RestaurantSummary(
            UUID id,
            String name,
            boolean open,
            String ownerId
    ) {}

    public record MenuItem(
            UUID id,
            String name,
            BigDecimal price,
            boolean available,
            int stock
    ) {}

    public record MenuResponse(
            UUID restaurantId,
            List<MenuItem> items
    ) {}
}
