package com.samato.orderservice.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Order aggregate.
 *
 * What you should know:
 *   1. **Items are an owned collection** (one-to-many, cascade = ALL).
 *      They are NOT a separate aggregate; you don't fetch them independently.
 *   2. **Status transitions are explicit** via {@link #transitionTo(OrderStatus)}.
 *      Code that says `order.setStatus(CONFIRMED)` is wrong — the only path
 *      is through the saga. This makes the state machine auditable.
 *   3. **No FK to users / restaurants** — the IDs are stored, but we don't
 *      validate them. The saga validates them by calling other services.
 *   4. **Idempotency-Key is optional** — see {@link IdempotencyRecord}.
 *      A request with the same key is treated as a repeat and returns the
 *      original order.
 *   5. **Optimistic locking** via @Version — concurrent updates to the
 *      same order are detected and retried.
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_customer", columnList = "customer_id"),
        @Index(name = "idx_orders_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_orders_status", columnList = "status")
})
public class Order {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    private UUID customerId;

    @Column(name = "restaurant_id", nullable = false, columnDefinition = "uuid")
    private UUID restaurantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PLACED;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(length = 500)
    private String cancellationReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "saga_id", columnDefinition = "uuid")
    private UUID sagaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    /**
     * Validates the transition before applying it. Throws if illegal.
     *
     * Allowed transitions:
     *   PLACED     -> VALIDATED, CANCELLED
     *   VALIDATED  -> RESERVED,  CANCELLED
     *   RESERVED   -> PAID,      CANCELLED
     *   PAID       -> CONFIRMED, CANCELLED
     *   CONFIRMED  -> (terminal)
     *   CANCELLED  -> (terminal)
     */
    public void transitionTo(OrderStatus next) {
        if (!isLegalTransition(this.status, next)) {
            throw new IllegalStateException(
                    "Illegal order status transition: " + this.status + " -> " + next);
        }
        this.status = next;
    }

    private static boolean isLegalTransition(OrderStatus from, OrderStatus to) {
        if (from == to) return false;
        if (from == OrderStatus.CONFIRMED || from == OrderStatus.CANCELLED) return false;
        if (to == OrderStatus.CANCELLED) return true;     // any non-terminal can be cancelled
        // Forward transitions only
        return switch (from) {
            case PLACED    -> to == OrderStatus.VALIDATED;
            case VALIDATED -> to == OrderStatus.RESERVED;
            case RESERVED  -> to == OrderStatus.PAID;
            case PAID      -> to == OrderStatus.CONFIRMED;
            default        -> false;
        };
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        this.items.add(item);
    }

    public void recomputeTotal() {
        this.totalAmount = items.stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // getters / setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getRestaurantId() { return restaurantId; }
    public void setRestaurantId(UUID restaurantId) { this.restaurantId = restaurantId; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
    public UUID getSagaId() { return sagaId; }
    public void setSagaId(UUID sagaId) { this.sagaId = sagaId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
