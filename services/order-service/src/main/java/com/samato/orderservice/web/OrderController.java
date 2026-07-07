package com.samato.orderservice.web;

import com.samato.orderservice.api.OrderDtos;
import com.samato.orderservice.api.OrderDtos.OrderResponse;
import com.samato.orderservice.domain.Order;
import com.samato.orderservice.domain.OrderItem;
import com.samato.orderservice.domain.SagaInstance;
import com.samato.orderservice.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for orders.
 *
 * Endpoints:
 *   POST   /api/orders                place a new order
 *   GET    /api/orders/{id}           fetch one
 *   GET    /api/orders                list the caller's orders
 *   GET    /api/orders/{id}/saga      inspect the saga for an order
 *   POST   /api/orders/{id}/cancel    cancel a non-terminal order
 *
 * Auth:
 *   - The customer is taken from the JWT subject (set by the gateway
 *     in the X-User-Id header — we re-parse the JWT for the truth).
 *   - @PreAuthorize enforces the "must be CUSTOMER" role rule.
 *   - Listing other people's orders is forbidden by the
 *     service-level check (a customer can only see their own).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderDtos.PlaceOrderRequest request) {

        UUID customerId = UUID.fromString(jwt.getSubject());
        var result = orderService.placeOrder(customerId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result.order()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Order order = orderService.get(id);
        if (!jwt.getSubject().equals(order.getCustomerId().toString())
                && !hasRole(jwt, "ADMIN")) {
            // Throwing 403 via DomainException keeps the error format
            // consistent with the rest of the API.
            throw new com.samato.shared.errors.DomainException(
                    "FORBIDDEN", "Cannot view another customer's order", 403);
        }
        return toResponse(order);
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<OrderResponse> list(@AuthenticationPrincipal Jwt jwt) {
        UUID customerId = UUID.fromString(jwt.getSubject());
        return orderService.byCustomer(customerId).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}/saga")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")
    public Map<String, Object> saga(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Order order = orderService.get(id);
        if (!jwt.getSubject().equals(order.getCustomerId().toString())
                && !hasRole(jwt, "ADMIN")) {
            throw new com.samato.shared.errors.DomainException(
                    "FORBIDDEN", "Cannot view another customer's saga", 403);
        }
        SagaInstance saga = orderService.sagaFor(id)
                .orElseThrow(() -> new com.samato.shared.errors.DomainException(
                        "SAGA_NOT_FOUND", "No saga for order " + id, 404));
        return Map.of(
                "sagaId", saga.getId(),
                "status", saga.getStatus(),
                "currentStepIndex", saga.getCurrentStepIndex(),
                "failureReason", saga.getFailureReason() == null ? "" : saga.getFailureReason(),
                "steps", saga.getSteps().stream().map(s -> Map.of(
                        "index", s.getStepIndex(),
                        "type", s.getStepType(),
                        "status", s.getStatus(),
                        "error", s.getErrorMessage() == null ? "" : s.getErrorMessage()
                )).toList()
        );
    }

    // -- helpers ------------------------------------------------------------

    private boolean hasRole(Jwt jwt, String role) {
        Object claim = jwt.getClaim("roles");
        if (claim instanceof List<?> roles) {
            return roles.contains(role);
        }
        return false;
    }

    private OrderResponse toResponse(Order order) {
        List<OrderDtos.OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getRestaurantId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getCancellationReason(),
                items,
                order.getSagaId(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private OrderDtos.OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderDtos.OrderItemResponse(
                item.getId(),
                item.getMenuItemId(),
                item.getName(),
                item.getQuantity(),
                item.getUnitPrice()
        );
    }
}
