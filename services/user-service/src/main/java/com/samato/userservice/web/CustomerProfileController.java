package com.samato.userservice.web;

import com.samato.shared.errors.DomainException;
import com.samato.userservice.domain.CustomerProfile;
import com.samato.userservice.domain.CustomerProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Customer profile endpoints.
 *
 * AuthZ model:
 *   - GET /api/users/me              — any authenticated user can read their own profile
 *   - PUT /api/users/me              — any authenticated user can update their own profile
 *   - GET /api/users/{id}            — ADMIN or the user themselves
 *
 * Notice the dual authZ:
 *   - **Authentication** (token valid?): enforced by the security filter chain.
 *   - **Authorization** (can THIS user do THIS thing?): enforced by
 *     `@PreAuthorize` and the inline checks in the methods.
 *
 * This is a critical interview point: gateway authN ≠ service authZ.
 */
@RestController
@RequestMapping("/api/users")
public class CustomerProfileController {

    private final CustomerProfileRepository repo;

    public CustomerProfileController(CustomerProfileRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public CustomerProfile getMine(@AuthenticationPrincipal Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return repo.findByUserId(id)
                .orElseGet(() -> createSkeletonProfile(id, jwt.getClaimAsString("email")));
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public CustomerProfile updateMine(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody UpdateProfileRequest req) {
        UUID id = UUID.fromString(jwt.getSubject());
        CustomerProfile p = repo.findByUserId(id)
                .orElseGet(() -> createSkeletonProfile(id, jwt.getClaimAsString("email")));
        if (req.displayName() != null) p.setDisplayName(req.displayName());
        if (req.phone() != null) p.setPhone(req.phone());
        if (req.photoUrl() != null) p.setPhotoUrl(req.photoUrl());
        return repo.save(p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or authentication.name == #id.toString()")
    public CustomerProfile getById(@PathVariable UUID id) {
        return repo.findByUserId(id)
                .orElseThrow(() -> new DomainException("USER_NOT_FOUND", "User not found", 404));
    }

    private CustomerProfile createSkeletonProfile(UUID id, String email) {
        CustomerProfile p = new CustomerProfile();
        p.setUserId(id);
        // Default the display name to the local part of the email so the
        // profile is never empty when first accessed.
        p.setDisplayName(email != null && email.contains("@")
                ? email.substring(0, email.indexOf('@'))
                : "user-" + id.toString().substring(0, 8));
        return repo.save(p);
    }

    public record UpdateProfileRequest(
            @Size(min = 1, max = 100) String displayName,
            @Size(max = 30) String phone,
            @Size(max = 500) String photoUrl
    ) {}
}
