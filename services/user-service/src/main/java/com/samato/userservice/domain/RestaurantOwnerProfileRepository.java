package com.samato.userservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RestaurantOwnerProfileRepository extends JpaRepository<RestaurantOwnerProfile, UUID> {
}
