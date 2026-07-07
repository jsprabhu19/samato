package com.samato.userservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Driver profile.
 *
 * Stores data only a driver has: vehicle info, current geo, on-duty status.
 * `currentLatitude` / `currentLongitude` would be hot-path writes in a real
 * system — for the bible we use a single row updated every few seconds.
 * In production, this lives in Redis with a TTL, and Postgres only sees
 * a snapshot every N minutes. We'll cover that trade-off in Phase 6.
 */
@Entity
@Table(name = "driver_profiles")
public class DriverProfile {

    @Id
    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String vehicleType;     // "BIKE" | "SCOOTER" | "CAR"

    private String licensePlate;

    @Column(nullable = false)
    private boolean onDuty = false;

    private Double currentLatitude;
    private Double currentLongitude;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
    public String getLicensePlate() { return licensePlate; }
    public void setLicensePlate(String licensePlate) { this.licensePlate = licensePlate; }
    public boolean isOnDuty() { return onDuty; }
    public void setOnDuty(boolean onDuty) { this.onDuty = onDuty; }
    public Double getCurrentLatitude() { return currentLatitude; }
    public void setCurrentLatitude(Double currentLatitude) { this.currentLatitude = currentLatitude; }
    public Double getCurrentLongitude() { return currentLongitude; }
    public void setCurrentLongitude(Double currentLongitude) { this.currentLongitude = currentLongitude; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
