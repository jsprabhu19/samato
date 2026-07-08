package com.samato.orderservice.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A line item in an order — a menu item and a quantity.
 *
 * The price is captured at order time (not looked up later). The restaurant
 * may change its prices tomorrow; this order is locked in at the price the
 * customer saw.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** ID of the menu item in restaurant-service. NOT a FK — cross-service. */
    @Column(name = "menu_item_id", nullable = false, columnDefinition = "uuid")
    private UUID menuItemId;

    // name and unitPrice are populated by the saga's VALIDATE_ITEMS step
    // (it fetches the server-side price from restaurant-service). They are
    // nullable until that step runs. We persist the order early so the
    // saga can find it in a fresh transaction; the row is "incomplete"
    // for the few hundred ms between persist and the first saga step.
    @Column(length = 200)
    private String name;

    @Column(nullable = false)
    private int quantity;

    /** Price per unit, captured at order time. */
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { this.createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public UUID getMenuItemId() { return menuItemId; }
    public void setMenuItemId(UUID menuItemId) { this.menuItemId = menuItemId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Instant getCreatedAt() { return createdAt; }
}
