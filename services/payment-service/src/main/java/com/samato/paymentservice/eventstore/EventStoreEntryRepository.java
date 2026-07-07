package com.samato.paymentservice.eventstore;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface EventStoreEntryRepository extends JpaRepository<EventStoreEntry, Long> {

    /**
     * Load the entire event stream for an aggregate, in order.
     * Used by the repository to rebuild state.
     */
    @Query("SELECT e FROM EventStoreEntry e WHERE e.aggregateId = :aggregateId ORDER BY e.version ASC")
    List<EventStoreEntry> findStreamByAggregateId(UUID aggregateId);

    /**
     * Find the latest event for a Razorpay order id. Used by the
     * webhook handler to look up the aggregate by razorpay_order_id.
     */
    @Query(value = "SELECT * FROM events WHERE event_data->>'razorpayOrderId' = :razorpayOrderId ORDER BY sequence_number ASC", nativeQuery = true)
    List<EventStoreEntry> findByRazorpayOrderId(String razorpayOrderId);

    /**
     * Find the latest event for a Razorpay payment id. Used by the
     * refund-completed webhook to look up the aggregate by
     * razorpay_payment_id.
     */
    @Query(value = "SELECT * FROM events WHERE event_data->>'razorpayPaymentId' = :razorpayPaymentId ORDER BY sequence_number ASC", nativeQuery = true)
    List<EventStoreEntry> findByRazorpayPaymentId(String razorpayPaymentId);

    /**
     * The latest version of the aggregate. Used to compute the next
     * version on append (for optimistic concurrency).
     */
    @Query("SELECT COALESCE(MAX(e.version), -1) FROM EventStoreEntry e WHERE e.aggregateId = :aggregateId")
    int findLatestVersion(UUID aggregateId);
}
