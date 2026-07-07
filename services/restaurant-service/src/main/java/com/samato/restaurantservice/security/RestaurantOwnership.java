package com.samato.restaurantservice.security;

import com.samato.restaurantservice.domain.Restaurant;
import com.samato.restaurantservice.domain.RestaurantRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SpEL-callable helper used in @PreAuthorize.
 *
 * Why a separate bean?
 *   - SpEL has limits. Going to the DB from a SpEL expression is ugly
 *     and unhittable for tests. Spring lets you call a bean's method
 *     via `@beanName.methodName(args)`, so we use that.
 *   - Keeps the controller's @PreAuthorize short:
 *     `hasRole('ADMIN') or @restaurantOwnership.isOwner(#id, authentication)`
 *
 * Trade-off: this is a small extra read on every authorized write. Fine
 * for the bible; in prod you'd cache (in Caffeine) the owner->restaurant
 * mapping for a few seconds.
 */
@Component("restaurantOwnership")
public class RestaurantOwnership {

    private final RestaurantRepository repo;

    public RestaurantOwnership(RestaurantRepository repo) {
        this.repo = repo;
    }

    public boolean isOwner(UUID restaurantId, Authentication auth) {
        if (auth == null) return false;
        try {
            UUID userId = UUID.fromString(auth.getName());
            return repo.findById(restaurantId)
                    .map(r -> userId.equals(r.getOwnerId()))
                    .orElse(false);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
