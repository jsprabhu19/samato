package com.samato.orderservice.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samato.orderservice.api.PaymentClient;
import com.samato.orderservice.api.PaymentDtos;
import com.samato.orderservice.api.RestaurantClient;
import com.samato.orderservice.api.RestaurantDtos;
import com.samato.orderservice.domain.*;
import com.samato.orderservice.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The saga engine.
 *
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │  drive(sagaId)                                                   │
 *   │    while there is a PENDING step:                                │
 *   │      try:  execute the step, mark COMPLETED                      │
 *   │      on failure: mark FAILED, switch saga to COMPENSATING,       │
 *   │                  walk completed steps in REVERSE and undo them.  │
 *   └──────────────────────────────────────────────────────────────────┘
 *
 * Persistence model:
 *   - SagaInstance + SagaStep rows are the source of truth.
 *   - The engine reads the saga, picks the next PENDING step, runs it,
 *     writes the result, and commits. Each step runs in its OWN
 *     transaction (REQUIRES_NEW) so a step's failure doesn't roll back
 *     a prior step's state.
 *
 * Why each step in REQUIRES_NEW?
 *   Consider RESERVE_INVENTORY: we want this to be COMMITTED even if
 *   the next step (CHARGE_PAYMENT) fails — otherwise we can't refund
 *   something we don't have a record of. Splitting into nested txns
 *   gives us a reliable audit trail.
 *
 *   Trade-off: more transactions = more work. For Phase 4 that's fine;
 *   at higher scale we'd batch them or use 2PC.
 */
@Service
public class SagaEngine {

    private static final Logger log = LoggerFactory.getLogger(SagaEngine.class);

    /** The canonical workflow. Order matters! */
    private static final List<SagaStepType> WORKFLOW = List.of(
            SagaStepType.VALIDATE_RESTAURANT,
            SagaStepType.VALIDATE_ITEMS,
            SagaStepType.RESERVE_INVENTORY,
            SagaStepType.CHARGE_PAYMENT,
            SagaStepType.CONFIRM_ORDER
    );

    private final SagaRepository sagaRepository;
    private final OrderRepository orderRepository;
    private final RestaurantClient restaurantClient;
    private final PaymentClient paymentClient;
    private final StepHandlers stepHandlers;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;
    /**
     * Self-reference via Spring's proxy. We inject ourselves to make sure
     * the @Transactional annotation on {@link #runStep} and
     * {@link #compensateStep} is honored.  A plain {@code this.method()}
     * call bypasses the proxy and the annotation is ignored.
     */
    private final SagaEngine self;

    public SagaEngine(SagaRepository sagaRepository,
                      OrderRepository orderRepository,
                      RestaurantClient restaurantClient,
                      PaymentClient paymentClient,
                      StepHandlers stepHandlers,
                      OutboxPublisher outbox,
                      ObjectMapper objectMapper,
                      @Lazy SagaEngine self) {
        this.sagaRepository = sagaRepository;
        this.orderRepository = orderRepository;
        this.restaurantClient = restaurantClient;
        this.paymentClient = paymentClient;
        this.stepHandlers = stepHandlers;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    /**
     * Bootstraps a saga for a freshly placed order.  Creates the
     * SagaInstance with all steps in PENDING state, then drives it.
     */
    @Transactional
    public SagaInstance start(UUID orderId) {
        SagaInstance saga = new SagaInstance();
        saga.setOrderId(orderId);
        saga.setStatus(SagaStatus.RUNNING);

        for (int i = 0; i < WORKFLOW.size(); i++) {
            SagaStep step = new SagaStep();
            step.setStepIndex(i);
            step.setStepType(WORKFLOW.get(i));
            step.setStatus(SagaStepStatus.PENDING);
            saga.addStep(step);
        }

        SagaInstance saved = sagaRepository.save(saga);
        // Link the order to the saga
        orderRepository.findById(orderId).ifPresent(o -> {
            o.setSagaId(saved.getId());
            orderRepository.save(o);
        });

        log.info("Saga {} started for order {} with {} steps", saved.getId(), orderId, WORKFLOW.size());
        return saved;
    }

    /**
     * Drives a saga forward. Idempotent — call it any number of times
     * for the same saga; completed steps are skipped.
     */
    @Transactional
    public void drive(UUID sagaId) {
        SagaInstance saga = sagaRepository.findById(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga not found: " + sagaId));

        if (saga.getStatus() == SagaStatus.COMPLETED || saga.getStatus() == SagaStatus.FAILED) {
            log.debug("Saga {} already in terminal state {}", sagaId, saga.getStatus());
            return;
        }

        if (saga.getStatus() == SagaStatus.COMPENSATING) {
            compensate(saga);
            return;
        }

        // RUNNING: pick the next PENDING step and run it
        SagaStep step = saga.nextPendingStep();
        if (step == null) {
            // All steps done — saga succeeds
            saga.setStatus(SagaStatus.COMPLETED);
            sagaRepository.save(saga);
            return;
        }

        try {
            self.runStep(saga, step);
        } catch (Exception ex) {
            log.error("Step {} ({}) of saga {} failed", step.getStepIndex(), step.getStepType(), sagaId, ex);
            // The step has already been marked FAILED inside runStep.
            saga.setStatus(SagaStatus.COMPENSATING);
            saga.setFailureReason(ex.getMessage());
            sagaRepository.save(saga);
            compensate(saga);
        }
    }

    /**
     * Executes a single step in its own transaction.  Returns the
     * payload to persist, or throws on failure (after marking FAILED
     * in a new transaction).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runStep(SagaInstance saga, SagaStep step) {
        step.markRunning();
        sagaRepository.save(saga);

        Order order = orderRepository.findById(saga.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order missing: " + saga.getOrderId()));

        String payload = dispatch(saga, step, order);
        step.markCompleted(payload);
        saga.setCurrentStepIndex(step.getStepIndex() + 1);
        sagaRepository.save(saga);
        log.info("Saga {} step {} ({}) COMPLETED", saga.getId(), step.getStepIndex(), step.getStepType());
    }

    /**
     * The dispatcher — what each step actually does.
     */
    private String dispatch(SagaInstance saga, SagaStep step, Order order) {
        return switch (step.getStepType()) {
            case VALIDATE_RESTAURANT -> validateRestaurant(order);
            case VALIDATE_ITEMS      -> validateItems(order);
            case RESERVE_INVENTORY   -> reserveInventory(order);
            case CHARGE_PAYMENT      -> chargePayment(order);
            case CONFIRM_ORDER       -> confirmOrder(order);
        };
    }

    // -- step implementations ------------------------------------------------

    private String validateRestaurant(Order order) {
        RestaurantDtos.RestaurantSummary restaurant = restaurantClient.getRestaurant(order.getRestaurantId());
        if (restaurant == null) {
            throw new IllegalStateException("Restaurant not found: " + order.getRestaurantId());
        }
        // The restaurant-service exposes `active` (deactivated vs not).
        // The saga treats active=true as "open for orders" — there is no
        // separate open/closed flag in Phase 4; that's a Phase 8 feature
        // (business hours, holidays, etc.).
        if (!restaurant.active()) {
            throw new IllegalStateException("Restaurant is closed: " + restaurant.name());
        }
        return writeJson(Map.of(
                "restaurantId", restaurant.id().toString(),
                "name", restaurant.name()
        ));
    }

    private String validateItems(Order order) {
        // Build comma-separated id list
        String ids = order.getItems().stream()
                .map(i -> i.getMenuItemId().toString())
                .collect(Collectors.joining(","));

        RestaurantDtos.MenuResponse menu = restaurantClient.getMenu(order.getRestaurantId(), ids);
        Map<UUID, RestaurantDtos.MenuItem> byId = menu.items().stream()
                .collect(Collectors.toMap(RestaurantDtos.MenuItem::id, m -> m));

        BigDecimal computedTotal = BigDecimal.ZERO;
        for (OrderItem ordered : order.getItems()) {
            RestaurantDtos.MenuItem menuItem = byId.get(ordered.getMenuItemId());
            if (menuItem == null) {
                throw new IllegalStateException("Menu item not on menu: " + ordered.getMenuItemId());
            }
            if (!menuItem.available()) {
                throw new IllegalStateException("Menu item unavailable: " + menuItem.name());
            }
            // Trust the SERVER's price, not the client's. Catch price drift.
            ordered.setName(menuItem.name());
            ordered.setUnitPrice(menuItem.price());
            computedTotal = computedTotal
                    .add(menuItem.price().multiply(BigDecimal.valueOf(ordered.getQuantity())));
        }
        order.setTotalAmount(computedTotal);
        orderRepository.save(order);

        return writeJson(Map.of(
                "itemCount", order.getItems().size(),
                "total", computedTotal.toPlainString()
        ));
    }

    private String reserveInventory(Order order) {
        // Phase 4: stub. The real implementation would call restaurant-service
        // to decrement stock atomically. We emit a "would reserve" payload.
        int units = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        return writeJson(Map.of(
                "stub", true,
                "units", units,
                "reservationId", UUID.randomUUID().toString()
        ));
    }

    private String chargePayment(Order order) {
        // Phase 5: real call to payment-service. The saga's step id
        // is the Idempotency-Key. If the saga retries, the same key
        // hits payment-service's processed_commands table and we
        // replay the prior response — no double-charge.
        UUID stepId = UUID.randomUUID();
        log.info("Charging payment for order {}: amount={} {}",
                order.getId(), order.getTotalAmount(), order.getCurrency());

        PaymentDtos.PaymentResponse resp = paymentClient.createOrder(
                stepId.toString(),
                new PaymentDtos.CreatePaymentRequest(
                        order.getId(),
                        order.getCustomerId(),
                        order.getTotalAmount(),
                        order.getCurrency()
                )
        );

        return writeJson(Map.of(
                "paymentId",       resp.paymentId().toString(),
                "razorpayOrderId", resp.razorpayOrderId() != null ? resp.razorpayOrderId() : "",
                "amount",          order.getTotalAmount().toPlainString(),
                "currency",        order.getCurrency(),
                "status",          resp.status() != null ? resp.status() : "ORDER_CREATED"
        ));
    }

    private String confirmOrder(Order order) {
        // The actual state transition + outbox event
        order.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        outbox.appendOrderConfirmed(order);
        return writeJson(Map.of("confirmed", true));
    }

    // -- compensation --------------------------------------------------------

    /**
     * Walks the completed steps in REVERSE order and undoes them.
     * Stops at the first step whose compensation fails — manual
     * intervention is required past that point.
     */
    @Transactional
    public void compensate(SagaInstance saga) {
        log.warn("Compensating saga {} (reason: {})", saga.getId(), saga.getFailureReason());
        // We re-load steps from the latest saga state to get a clean view
        SagaInstance fresh = sagaRepository.findById(saga.getId()).orElse(saga);

        boolean anyFailed = false;
        // Walk backwards. Skip non-completed steps. For each COMPLETED step,
        // run the matching compensation.
        List<SagaStep> completed = new ArrayList<>();
        for (SagaStep s : fresh.getSteps()) {
            if (s.getStatus() == SagaStepStatus.COMPLETED) completed.add(s);
        }
        Collections.reverse(completed);

        for (SagaStep step : completed) {
            try {
                self.compensateStep(fresh, step);
            } catch (Exception ex) {
                log.error("Compensation for step {} of saga {} FAILED",
                        step.getStepType(), saga.getId(), ex);
                step.markFailedCompensation(ex.getMessage());
                anyFailed = true;
                break; // stop and surface to operator
            }
        }

        // The order moves to CANCELLED
        orderRepository.findById(fresh.getOrderId()).ifPresent(o -> {
            if (!o.getStatus().isTerminal()) {
                try {
                    o.transitionTo(OrderStatus.CANCELLED);
                    o.setCancellationReason(saga.getFailureReason() != null
                            ? saga.getFailureReason()
                            : "Saga compensation");
                } catch (IllegalStateException ignored) {
                    // Already terminal — fine.
                }
                orderRepository.save(o);
                outbox.appendOrderCancelled(o);
            }
        });

        sagaRepository.save(fresh);
        if (anyFailed || !completed.isEmpty()) {
            // We did at least one compensation
            saga.setStatus(anyFailed ? SagaStatus.FAILED : SagaStatus.COMPENSATING);
            // If every compensation succeeded AND no step's compensation failed, terminal = COMPLETED
            // but actually COMPENSATING with all COMPENSATED means "saga is done in failure mode"
            // We mark COMPLETED here to mean "orchestration finished"; the ORDER status is CANCELLED.
            if (!anyFailed) {
                saga.setStatus(SagaStatus.COMPLETED);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateStep(SagaInstance saga, SagaStep step) {
        Order order = orderRepository.findById(saga.getOrderId()).orElse(null);
        step.markCompensating();
        sagaRepository.save(saga);

        switch (step.getStepType()) {
            case CHARGE_PAYMENT -> {
                // Real refund through payment-service. We need the
                // paymentId from the step's payload (set when
                // chargePayment originally ran). If it's missing
                // (e.g. charge never returned a paymentId), this
                // saga's compensation has nothing to undo.
                if (order == null) {
                    log.warn("CHARGE_PAYMENT compensation: order missing for saga {}", saga.getId());
                    break;
                }
                UUID paymentId = readPaymentIdFromPayload(step.getPayload());
                if (paymentId == null) {
                    log.warn("CHARGE_PAYMENT compensation: no paymentId in step payload (charge never returned?)");
                    break;
                }
                log.info("REFUND payment {} for order {}: amount={} {}",
                        paymentId, order.getId(), order.getTotalAmount(), order.getCurrency());
                paymentClient.refund(
                        paymentId,
                        UUID.randomUUID().toString(),
                        new PaymentDtos.RefundRequest(order.getTotalAmount())
                );
            }
            case RESERVE_INVENTORY -> {
                // Release the (stubbed) reservation
                log.info("RELEASE inventory for order {} (stub)", order != null ? order.getId() : "?");
            }
            case CONFIRM_ORDER, VALIDATE_ITEMS, VALIDATE_RESTAURANT -> {
                // No-ops — these steps don't make external state changes
                // that need rolling back.
            }
        }
        step.markCompensated();
        sagaRepository.save(saga);
    }

    /**
     * Parse the {@code paymentId} from a CHARGE_PAYMENT step's payload
     * JSON. Returns null if the payload is missing the field.
     */
    private UUID readPaymentIdFromPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            var node = objectMapper.readTree(payloadJson);
            String pid = node.path("paymentId").asText(null);
            return pid == null ? null : UUID.fromString(pid);
        } catch (Exception e) {
            log.warn("Could not parse paymentId from step payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Used by the orchestrator to re-find a saga by orderId — the
     * web controller exposes this so a client can poll.
     */
    public Optional<SagaInstance> findByOrderId(UUID orderId) {
        return sagaRepository.findByOrderId(orderId);
    }

    public List<SagaInstance> findInProgress() {
        return sagaRepository.findByStatus(SagaStatus.RUNNING);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize step payload", e);
        }
    }
}
