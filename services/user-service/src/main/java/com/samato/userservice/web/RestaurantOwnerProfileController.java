package com.samato.userservice.web;

import com.samato.userservice.domain.RestaurantOwnerProfile;
import com.samato.userservice.domain.RestaurantOwnerProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Restaurant owner profile endpoints.
 *
 * Note: restaurants themselves (the entity: name, address, menu) live in
 * restaurant-service (Phase 3). This service stores the OWNER's business
 * info: business name, tax ID, contact details. Two separate aggregates.
 */
@RestController
@RequestMapping("/api/users/restaurant-owners")
public class RestaurantOwnerProfileController {

    private final RestaurantOwnerProfileRepository repo;

    public RestaurantOwnerProfileController(RestaurantOwnerProfileRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public RestaurantOwnerProfile getMine(@AuthenticationPrincipal Jwt jwt) {
        return getOrCreate(jwt);
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public RestaurantOwnerProfile updateMine(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody UpdateRequest req) {
        RestaurantOwnerProfile p = getOrCreate(jwt);
        if (req.businessName() != null) p.setBusinessName(req.businessName());
        if (req.contactEmail() != null) p.setContactEmail(req.contactEmail());
        if (req.contactPhone() != null) p.setContactPhone(req.contactPhone());
        if (req.taxId() != null) p.setTaxId(req.taxId());
        return repo.save(p);
    }

    private RestaurantOwnerProfile getOrCreate(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return repo.findById(id).orElseGet(() -> {
            RestaurantOwnerProfile p = new RestaurantOwnerProfile();
            p.setUserId(id);
            p.setBusinessName("My Business");
            p.setContactEmail(jwt.getClaimAsString("email"));
            return repo.save(p);
        });
    }

    public record UpdateRequest(
            @NotBlank String businessName,
            @Email String contactEmail,
            String contactPhone,
            String taxId) {}
}
