package com.samato.orderservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    List<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
    List<Order> findByRestaurantIdOrderByCreatedAtDesc(UUID restaurantId);
    List<Order> findByStatus(OrderStatus status);
    Optional<Order> findBySagaId(UUID sagaId);
}
