package com.samato.orderservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.orderservice.api.OrderDtos;
import com.samato.orderservice.domain.*;
import com.samato.orderservice.outbox.OutboxPublisher;
import com.samato.orderservice.saga.SagaEngine;
import com.samato.shared.errors.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The order service.
 *
 *   - Persists the order (status = PLACED)
 *   - Records the idempotency key (if provided)
 *   - Starts the saga
 *   - Drives the saga forward (synchronously for Phase 4 — the request
 *     thread waits for the saga to either complete or compensate)
 *
 * In a higher-scale system the controller would 202-Accept the request,
 * return the orderId, and the saga would be driven by an async worker
 * (Kafka consumer or @Scheduled).  For the interview-level demo we
 * keep it simple — and the controller can always move to async later.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final OutboxPublisher outbox;
    private final SagaEngine sagaEngine;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orderRepository,
                        IdempotencyRepository idempotencyRepository,
                        OutboxPublisher outbox,
                        SagaEngine sagaEngine,
                        ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outbox = outbox;
        this.sagaEngine = sagaEngine;
        this.objectMapper = objectMapper;
    }

    // -- place order ---------------------------------------------------------

    public record PlaceOrderResult(Order order, boolean replayed) {}

    /**
     * Top-level entry point. NOT @Transactional — we need to commit the
     * order BEFORE driving the saga, otherwise the saga's
     * sagaRepository.findById(...) can't see the row in a fresh
     * transaction.
     */
    public PlaceOrderResult placeOrder(UUID customerId,
                                       String idempotencyKey,
                                       OrderDtos.PlaceOrderRequest request) {

        // 1. Idempotency replay short-circuit (no writes happen on replay)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> existing =
                    idempotencyRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                IdempotencyRecord rec = existing.get();
                String requestHash = hash(request);
                if (!rec.getRequestHash().equals(requestHash)) {
                    throw new DomainException(
                            "IDEMPOTENCY_KEY_REUSED",
                            "Idempotency-Key reused with a different request body",
                            422);
                }
                Order original = orderRepository.findById(rec.getOrderId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency record references missing order"));
                log.info("Replaying order {} for customer {} (idempotency-key={})",
                        original.getId(), customerId, idempotencyKey);
                return new PlaceOrderResult(original, true);
            }
        }

        // 2. Persist the order, outbox event, and saga bootstrap in ONE
        //    transaction. After this method returns, all of those rows
        //    are committed and visible to other transactions.
        UUID sagaId = persistAndStartSaga(customerId, idempotencyKey, request);

        // 3. Drive the saga in a fresh transaction context so its reads
        //    see the freshly-committed rows. The poller will pick this up
        //    too if the synchronous drive is interrupted.
        try {
            sagaEngine.drive(sagaId);
        } catch (Exception ex) {
            log.warn("Saga for sagaId {} failed: {}", sagaId, ex.getMessage());
        }
        Order finalOrder = orderRepository.findById(findOrderIdBySagaId(sagaId)).orElseThrow();
        return new PlaceOrderResult(finalOrder, false);
    }

    /**
     * Persists the order, appends the OrderPlaced outbox event, starts
     * the saga, and stores the idempotency record — all in a single
     * transaction. Returns the sagaId so the caller can drive the saga
     * after this method commits.
     */
    @Transactional
    public UUID persistAndStartSaga(UUID customerId,
                                    String idempotencyKey,
                                    OrderDtos.PlaceOrderRequest request) {
        Order order = new Order();
        order.setCustomerId(customerId);
        order.setRestaurantId(request.restaurantId());
        order.setStatus(OrderStatus.PLACED);
        for (OrderDtos.OrderItemRequest item : request.items()) {
            OrderItem oi = new OrderItem();
            oi.setMenuItemId(item.menuItemId());
            oi.setQuantity(item.quantity());
            // We do NOT set the price here — the saga VALIDATE_ITEMS step
            // pulls the server-side price from restaurant-service. Setting
            // it client-side would let a malicious caller fake the total.
            order.addItem(oi);
        }
        // Don't recomputeTotal() here — items have no unit price yet. The
        // saga's VALIDATE_ITEMS step pulls the server-side price from
        // restaurant-service and sets order.totalAmount from the result.
        // Calling recomputeTotal() now would NPE on getUnitPrice().
        Order saved = orderRepository.save(order);
        outbox.appendOrderPlaced(saved);

        SagaInstance saga = sagaEngine.start(saved.getId());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotencyRecord rec = new IdempotencyRecord();
            rec.setCustomerId(customerId);
            rec.setIdempotencyKey(idempotencyKey);
            rec.setRequestHash(hash(request));
            rec.setResponseStatus(201);
            rec.setOrderId(saved.getId());
            idempotencyRepository.save(rec);
        }
        return saga.getId();
    }

    /**
     * Helper: given a sagaId, find the orderId it manages.
     */
    private java.util.UUID findOrderIdBySagaId(UUID sagaId) {
        return orderRepository.findBySagaId(sagaId)
                .map(Order::getId)
                .orElseThrow(() -> new IllegalStateException("No order for saga " + sagaId));
    }

    // -- queries -------------------------------------------------------------

    public Order get(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new DomainException(
                        "ORDER_NOT_FOUND", "Order not found: " + id, 404));
    }

    public List<Order> byCustomer(UUID customerId) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    public List<Order> byRestaurant(UUID restaurantId) {
        return orderRepository.findByRestaurantIdOrderByCreatedAtDesc(restaurantId);
    }

    public Optional<SagaInstance> sagaFor(UUID orderId) {
        return sagaEngine.findByOrderId(orderId);
    }

    // -- helpers -------------------------------------------------------------

    private String hash(Object o) {
        try {
            String json = objectMapper.writeValueAsString(o);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash request", e);
        }
    }
}
