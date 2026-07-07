package com.samato.restaurantservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {
    List<Restaurant> findByCityIgnoreCaseAndActiveTrue(String city);
    List<Restaurant> findByOwnerId(UUID ownerId);
}
