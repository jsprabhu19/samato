package com.samato.orderservice.api;

import com.samato.orderservice.domain.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API DTOs for the order endpoints.
 *
 * Why a separate dto package?
 *   - Decouples the wire format from the JPA entities.
 *   - Lets us add validation annotations without polluting the domain.
 *   - Allows the entity to evolve (e.g., add a hidden column) without
 *     breaking the API contract.
 */
public final class OrderDtos {

    private OrderDtos() {}

    public record OrderItemRequest(
            @NotNull UUID menuItemId,
            @Min(1) int quantity
    ) {}

    public record PlaceOrderRequest(
            @NotNull UUID restaurantId,
            @NotEmpty @Valid List<OrderItemRequest> items,
            @Size(max = 500) String notes
    ) {}

    public record OrderItemResponse(
            UUID id,
            UUID menuItemId,
            String name,
            int quantity,
            BigDecimal unitPrice
    ) {}

    public record OrderResponse(
            UUID id,
            UUID customerId,
            UUID restaurantId,
            OrderStatus status,
            BigDecimal totalAmount,
            String currency,
            String cancellationReason,
            List<OrderItemResponse> items,
            UUID sagaId,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
