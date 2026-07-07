package com.samato.restaurantservice.web;

import com.samato.restaurantservice.domain.MenuItem;
import com.samato.restaurantservice.domain.Restaurant;
import com.samato.restaurantservice.service.RestaurantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Restaurant + menu-item endpoints.
 *
 * AuthZ model:
 *   - GET    (browse)             — any authenticated user
 *   - POST   (create)             — RESTAURANT_OWNER (and the caller's id becomes ownerId)
 *   - PUT    (update)             — the owner or ADMIN
 *   - DELETE (deactivate)         — the owner or ADMIN
 *   - POST   (add menu item)      — the owner or ADMIN
 *
 * Note: the @PreAuthorize expression `authentication.name == #id.toString()`
 * compares the JWT subject (user id) to the path variable. The owner
 * can update their own restaurant; ADMIN can update anyone's.
 */
@RestController
@RequestMapping("/api/restaurants")
public class RestaurantController {

    private final RestaurantService service;

    public RestaurantController(RestaurantService service) {
        this.service = service;
    }

    // ─── Browsing (read) ─────────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public Restaurant get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping(params = "city")
    @PreAuthorize("isAuthenticated()")
    public List<Restaurant> findByCity(@RequestParam String city) {
        return service.findByCity(city);
    }

    @GetMapping("/{id}/menu")
    @PreAuthorize("isAuthenticated()")
    public List<MenuItem> getMenu(@PathVariable UUID id) {
        return service.getMenu(id);
    }

    // ─── Owner operations (write) ────────────────────────────────
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public Restaurant create(@AuthenticationPrincipal Jwt jwt,
                             @Valid @RequestBody CreateRestaurantRequest req) {
        Restaurant r = new Restaurant();
        r.setName(req.name());
        r.setDescription(req.description());
        r.setCuisine(req.cuisine());
        r.setAddress(req.address());
        r.setCity(req.city());
        r.setLatitude(req.latitude());
        r.setLongitude(req.longitude());
        return service.create(UUID.fromString(jwt.getSubject()), r);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @restaurantOwnership.isOwner(#id, authentication)")
    public Restaurant update(@PathVariable UUID id, @Valid @RequestBody UpdateRestaurantRequest req) {
        Restaurant patch = new Restaurant();
        if (req.name() != null) patch.setName(req.name());
        if (req.description() != null) patch.setDescription(req.description());
        if (req.cuisine() != null) patch.setCuisine(req.cuisine());
        if (req.address() != null) patch.setAddress(req.address());
        if (req.city() != null) patch.setCity(req.city());
        return service.update(id, patch);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or @restaurantOwnership.isOwner(#id, authentication)")
    public void deactivate(@PathVariable UUID id) {
        service.deactivate(id);
    }

    @PostMapping("/{id}/menu")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or @restaurantOwnership.isOwner(#id, authentication)")
    public MenuItem addMenuItem(@PathVariable UUID id,
                                @Valid @RequestBody CreateMenuItemRequest req) {
        MenuItem item = new MenuItem();
        item.setName(req.name());
        item.setDescription(req.description());
        item.setPrice(req.price());
        return service.addMenuItem(id, item);
    }

    // ─── DTOs (records are concise + immutable) ──────────────────
    public record CreateRestaurantRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 50) String cuisine,
            @NotBlank @Size(max = 500) String address,
            @NotBlank @Size(max = 100) String city,
            double latitude,
            double longitude) {}

    public record UpdateRestaurantRequest(
            @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Size(max = 50) String cuisine,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            Double latitude,
            Double longitude) {}

    public record CreateMenuItemRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull @DecimalMin("0.00") BigDecimal price) {}
}
