package com.samato.userservice.web;

import com.samato.userservice.domain.DriverProfile;
import com.samato.userservice.domain.DriverProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Driver profile endpoints.
 *
 * AuthZ:
 *   - GET /me, PUT /me, PUT /me/location — DRIVER (the driver themselves).
 *   - GET /on-duty                       — DRIVER (used by delivery-service to find
 *     available drivers; we'll add an admin override later).
 *
 * In Phase 6, delivery-service will call this via Feign to find the nearest
 * on-duty driver to a restaurant. The bible's location update endpoint is
 * intentionally simple — production would batch + write to Redis.
 */
@RestController
@RequestMapping("/api/users/drivers")
public class DriverProfileController {

    private final DriverProfileRepository repo;

    public DriverProfileController(DriverProfileRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverProfile getMine(@AuthenticationPrincipal Jwt jwt) {
        return getOrCreate(jwt);
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverProfile updateMine(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody UpdateRequest req) {
        DriverProfile p = getOrCreate(jwt);
        if (req.fullName() != null) p.setFullName(req.fullName());
        if (req.vehicleType() != null) p.setVehicleType(req.vehicleType());
        if (req.licensePlate() != null) p.setLicensePlate(req.licensePlate());
        if (req.onDuty() != null) p.setOnDuty(req.onDuty());
        return repo.save(p);
    }

    @PutMapping("/me/location")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverProfile updateLocation(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody LocationUpdate req) {
        DriverProfile p = getOrCreate(jwt);
        p.setCurrentLatitude(req.latitude());
        p.setCurrentLongitude(req.longitude());
        return repo.save(p);
    }

    @GetMapping("/on-duty")
    @PreAuthorize("hasRole('DRIVER') or hasRole('ADMIN')")
    public java.util.List<DriverProfile> listOnDuty() {
        return repo.findByOnDutyTrue();
    }

    private DriverProfile getOrCreate(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return repo.findById(id).orElseGet(() -> {
            DriverProfile p = new DriverProfile();
            p.setUserId(id);
            p.setFullName("driver-" + id.toString().substring(0, 8));
            p.setVehicleType("BIKE");
            return repo.save(p);
        });
    }

    public record UpdateRequest(
            String fullName,
            String vehicleType,
            String licensePlate,
            Boolean onDuty) {}

    public record LocationUpdate(
            @NotNull Double latitude,
            @NotNull Double longitude) {}
}
