package com.samato.paymentservice.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The next batch of unsent events. The partial index
     * {@code idx_outbox_unsent} keeps this fast even with millions of
     * sent rows.
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.sentAt IS NULL ORDER BY o.createdAt ASC")
    List<OutboxEvent> findUnsent(org.springframework.data.domain.Pageable page);
}
