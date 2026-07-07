package com.samato.restaurantservice.service;

import com.samato.restaurantservice.domain.*;
import com.samato.shared.errors.DomainException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Restaurant service — the business logic + transaction boundary.
 *
 * What you should notice in this file:
 *
 *   1. **`@Transactional`** — every write method is transactional. The
 *      business write + the outbox write are in the SAME transaction.
 *      This is the cornerstone of the outbox pattern.
 *
 *   2. **`@Cacheable` / `@CacheEvict`** — cache-aside pattern:
 *      - On read: try cache, fall back to DB.
 *      - On write: invalidate the cache.
 *      We evict on every write because the cache key is restaurantId;
 *      a read-modify-write is fine here because we read first inside
 *      the transactional method.
 *
 *   3. **`Outbox.enqueue(...)`** — a thin helper that converts the
 *      Avro event to bytes and writes the outbox row. Notice we pass
 *      the bytes, NOT the Kafka send call. The actual Kafka send happens
 *      in OutboxPublisher (separate thread, separate transaction).
 *
 *   4. **`UUID.fromString(...)` for ownerId** — ownerId is a UUID from
 *      the JWT. We don't validate it exists in user-service here; the
 *      JWT is the trust anchor.
 */
@Service
public class RestaurantService {

    public static final String CACHE = "restaurants";

    private final RestaurantRepository restaurants;
    private final MenuItemRepository menuItems;
    private final Outbox outbox;

    public RestaurantService(RestaurantRepository restaurants,
                             MenuItemRepository menuItems,
                             Outbox outbox) {
        this.restaurants = restaurants;
        this.menuItems = menuItems;
        this.outbox = outbox;
    }

    // ─── Reads (cached) ──────────────────────────────────────────
    @Cacheable(value = CACHE, key = "#id")
    @Transactional(readOnly = true)
    public Restaurant get(UUID id) {
        return restaurants.findById(id)
                .orElseThrow(() -> new DomainException("RESTAURANT_NOT_FOUND", "Restaurant not found", 404));
    }

    @Transactional(readOnly = true)
    public List<Restaurant> findByCity(String city) {
        return restaurants.findByCityIgnoreCaseAndActiveTrue(city);
    }

    @Transactional(readOnly = true)
    public List<Restaurant> findByOwner(UUID ownerId) {
        return restaurants.findByOwnerId(ownerId);
    }

    @Transactional(readOnly = true)
    public List<MenuItem> getMenu(UUID restaurantId) {
        return menuItems.findByRestaurantId(restaurantId);
    }

    // ─── Writes (cache-invalidated) ──────────────────────────────
    @Caching(evict = {
            @CacheEvict(value = CACHE, key = "#result.id")
    })
    @Transactional
    public Restaurant create(UUID ownerId, Restaurant draft) {
        draft.setOwnerId(ownerId);
        if (draft.getId() == null) draft.setId(UUID.randomUUID());
        Restaurant saved = restaurants.save(draft);

        // Outbox — the event is in the SAME transaction as the business write.
        outbox.enqueueRestaurantCreated(saved);
        return saved;
    }

    @Caching(evict = {
            @CacheEvict(value = CACHE, key = "#id")
    })
    @Transactional
    public Restaurant update(UUID id, Restaurant patch) {
        Restaurant r = restaurants.findById(id)
                .orElseThrow(() -> new DomainException("RESTAURANT_NOT_FOUND", "Restaurant not found", 404));
        if (patch.getName() != null) r.setName(patch.getName());
        if (patch.getDescription() != null) r.setDescription(patch.getDescription());
        if (patch.getCuisine() != null) r.setCuisine(patch.getCuisine());
        if (patch.getAddress() != null) r.setAddress(patch.getAddress());
        if (patch.getCity() != null) r.setCity(patch.getCity());
        Restaurant saved = restaurants.save(r);

        outbox.enqueueRestaurantUpdated(saved);
        return saved;
    }

    @Caching(evict = {
            @CacheEvict(value = CACHE, key = "#id")
    })
    @Transactional
    public void deactivate(UUID id) {
        Restaurant r = restaurants.findById(id)
                .orElseThrow(() -> new DomainException("RESTAURANT_NOT_FOUND", "Restaurant not found", 404));
        r.setActive(false);
        restaurants.save(r);
        outbox.enqueueRestaurantUpdated(r);
    }

    @Transactional
    public MenuItem addMenuItem(UUID restaurantId, MenuItem item) {
        // Validate the restaurant exists
        if (!restaurants.existsById(restaurantId)) {
            throw new DomainException("RESTAURANT_NOT_FOUND", "Restaurant not found", 404);
        }
        item.setRestaurantId(restaurantId);
        return menuItems.save(item);
    }
}
