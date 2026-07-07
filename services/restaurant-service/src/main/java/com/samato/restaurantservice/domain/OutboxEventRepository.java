package com.samato.restaurantservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Returns unsent events in insertion order.
     * `LIMIT 100` keeps each poll batch bounded.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.sentAt IS NULL ORDER BY e.createdAt ASC LIMIT 100")
    List<OutboxEvent> findUnsent();
}
