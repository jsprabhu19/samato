# Use case: Refund a captured payment

> Alice paid ₹499 for a butter chicken and a naan from a local restaurant. The order was delivered. The food arrived cold. Alice opens the Samato app, taps "Request refund", and within seconds sees a "Refund initiated" badge. The order-service saga runs the compensation path; the payment-service calls Razorpay to issue a refund; Razorpay sends a `refund.processed` webhook back; the event-sourced ledger appends a `RefundCompleted` event; and Alice's payment view flips to `REFUNDED`. The whole thing is auditable to the last paise because **the events table is the source of truth**, not the row in `payment_view`. This use case is the textbook walkthrough of that idea.

This document is the beginner-friendly trace of that flow. It assumes you have read [`../00-glossary.md`](../00-glossary.md) and [`../01-architecture-guide.md`](../01-architecture-guide.md), and that you have already followed [Alice placing her order](./01-place-an-order.md) end-to-end.

---

## 1. The story

| Time (relative) | What Alice sees | What the system is doing |
|---|---|---|
| **T+0 ms** | Alice taps **"Request refund"** on the order details page. The app fires `POST /api/orders/{id}/cancel` with her bearer JWT. | The api-gateway stamps the request with `X-User-Id`, `X-User-Roles`, and a fresh `X-Correlation-Id`; the request hits the order-service JWT chain. |
| **T+50 ms** | Spinner. | The order-service starts saga compensation (the saga is `COMPENSATING` in `saga_instances`). The CHARGE_PAYMENT step is reversed by calling `PaymentClient.refund(...)` over Feign. |
| **T+150 ms** | Spinner. | The order-service Feign call lands on `payment-service`'s `PaymentController.refund`. `IdempotencyGuard.executeOnce` checks the `processed_commands` table; on first sight of the key, `PaymentCommandHandler.handleRefund` runs. |
| **T+250 ms** | Spinner. | `payment-service` replays the `Payment` aggregate from the event store (it was at version N with status `CAPTURED`). The handler calls `RazorpayClient.refund` over HTTPS. |
| **T+450 ms** | "Refund initiated" badge. | The handler appends a `RefundInitiated` event to `events` (version N+1), projects the read model to `REFUND_INITIATED`, writes an outbox row for `samato.payment.refund.initiated`. The HTTP response goes back to order-service, then to Alice. |
| **T+500 ms** (next outbox tick) | — | `OutboxPublisher.publishPending` picks up the outbox row and ships it to Kafka. |
| **T+3-10 s** (Razorpay processing) | — | Razorpay moves the money. |
| **T+10 s** (webhook arrival, asynchronous) | — | Razorpay POSTs to `/api/payments/webhooks/razorpay` with the `refund.processed` event, signed with HMAC-SHA256. The webhook filter chain runs (no JWT). The controller verifies the signature, dispatches the event, and the handler appends a `RefundCompleted` event. Status → `REFUNDED`. The outbox publishes `samato.payment.refunded`. |
| **T+10.5 s** | The badge text changes to **"Refunded"**. | The `payment_view` is updated inside the same DB transaction as the event append. Alice's next `GET /api/payments/{id}` (or the order endpoint, via the cross-service lookup) sees the new state. |

The realistic timeline is: **refund initiation is fast (sub-second)**, **Razorpay processing takes seconds to a minute**, **the webhook is asynchronous and can arrive minutes later** (or be retried by Razorpay if the service is briefly down). The user-facing "refund initiated" badge is honest; the "refunded" badge is a webhook-driven update.

---

## 2. Prerequisites

- **The order must be in `CONFIRMED` state** (the equivalent of `CAPTURED` in payment-service). In the order-service state machine, `Order.PAID → CONFIRMED` is set in `SagaEngine.confirmOrder` once `CHARGE_PAYMENT` succeeded. You can't refund a `PLACED` or `CANCELLED` order. See [the order state machine in the glossary](../00-glossary.md#orderstatus) and [Order.transitionTo in `Order.java`](../../services/order-service/src/main/java/com/samato/orderservice/domain/Order.java).
- **The payment must be in `CAPTURED` state** in `payment_view` — meaning a `PaymentCaptured` event has been appended to the `events` table. The aggregate enforces this: `Payment.initiateRefund` throws `IllegalStateException("Cannot refund non-captured payment: " + status)` if the current state is anything else. See [`Payment.java:147-161`](../../services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java).
- **Alice must be authenticated.** Her JWT (issued by [`auth-service`](../services/auth-service.md)) is validated twice — once at the gateway, once at order-service. The gateway injects `X-User-Id` and `X-User-Roles`; the service re-parses the token via `NimbusJwtDecoder` (defence in depth).
- **The order must belong to Alice.** A customer can't refund another customer's order. The `OrderController` runs an in-method ownership check: `if (!jwt.getSubject().equals(order.getCustomerId().toString()) && !hasRole(jwt, "ADMIN"))` → 403.
- **An `Idempotency-Key` must be supplied.** Both order-service (`POST /api/orders/{id}/cancel` in javadoc; in practice, the saga-step UUID is used) and payment-service (`POST /api/payments/{id}/refunds`) require a non-blank `Idempotency-Key` header. A retry with the same key returns the cached result without re-running the work. See [Idempotency](../00-glossary.md#idempotency).
- **Razorpay must be reachable from payment-service.** If the gateway is down, `RazorpayClientImpl.refund` throws `PaymentGatewayException`. See §11 below.
- **A Razorpay payment must exist.** Without a `razorpay_payment_id` in the `Payment` aggregate, the refund call cannot be made. This id is set by the `payment.captured` webhook and stored in the `PaymentCaptured` event.

> **Where the payment came from.** The order that Alice is refunding was placed and charged in [Use case 01: Place an order](./01-place-an-order.md). The place-order walkthrough traces `RazorpayOrderCreated → PaymentInitiated → PaymentCaptured` and ends with the order in `CONFIRMED` and the payment in `CAPTURED`. That is the starting point of this use case.

---

## 3. The cast of characters

```
            Alice              api-gateway           order-service         payment-service         Razorpay
              |                    |                       |                       |                     |
              |  POST /api/orders/ |                       |                       |                     |
              |  {id}/cancel       |                       |                       |                     |
              |  Bearer JWT        |                       |                       |                     |
              |------------------->|                       |                       |                     |
              |                    | 1. JWT verify, inject |                       |                     |
              |                    |    X-User-Id/         |                       |                     |
              |                    |    X-Correlation-Id   |                       |                     |
              |                    | 2. Route to order-svc |                       |                     |
              |                    |---------------------->|                       |                     |
              |                    |                       | 3. OrderService.cancel triggers           |
              |                    |                       |    SagaEngine.compensate (in reverse)      |
              |                    |                       | 4. SagaStep CHARGE_PAYMENT compensation:   |
              |                    |                       |    Feign POST /api/payments/{id}/refunds    |
              |                    |                       |    Idempotency-Key: <saga-step-uuid>       |
              |                    |                       |    FeignAuthForwarder: forward JWT         |
              |                    |                       |---------------------->|                     |
              |                    |                       |                       | 5. PaymentService.refund  |
              |                    |                       |                       | 6. IdempotencyGuard:      |
              |                    |                       |                       |    check processed_commands|
              |                    |                       |                       | 7. PaymentCommandHandler:  |
              |                    |                       |                       |    load aggregate (replay)|
              |                    |                       |                       |    initiateRefund()       |
              |                    |                       |                       | 8. RazorpayClient.refund  |
              |                    |                       |                       |---HTTPS POST /v1/payments/{id}/refund--->
              |                    |                       |                       |                     |
              |                    |                       |                       |<--razorpay_refund_id--|
              |                    |                       |                       | 9. Append RefundInitiated|
              |                    |                       |                       |    to events (v=N+1)    |
              |                    |                       |                       | 10. Project to payment_view (REFUND_INITIATED)
              |                    |                       |                       | 11. Outbox: enqueue samato.payment.refund.initiated
              |                    |                       |                       | 12. IdempotencyGuard.recordResult
              |                    |                       |                       | 13. Response: PaymentResponse (200)
              |                    |                       |<----------------------|                     |
              |                    |                       | 14. SagaStep markCompensated                  |
              |                    |                       | 15. Order.transitionTo(CANCELLED)            |
              |                    |                       | 16. Outbox: samato.order.cancelled            |
              |                    |<----------------------|                                             |
              |<---200 OK (badge:  |                                                             |
              |  Refund initiated) |                                                             |
              |                    |                                                             |
              |  ---- ~3-10s later, Razorpay moves money -----                                  |
              |                    |                                                             |
              |                    |                Razorpay sends webhook (asynchronous)        |
              |                    |                       |                                       |
              |                    |                       |  <--- POST /api/payments/webhooks/razorpay
              |                    |                       |       X-Razorpay-Signature: <hmac>
              |                    |                       |       (webhook filter chain, NO JWT)    |
              |                    |                       |                                       |
              |                    |                       | 17. WebhookController.handle:           |
              |                    |                       |    - Verify HMAC via SDK               |
              |                    |                       |    - PaymentService.handleWebhook      |
              |                    |                       | 18. Dispatch "refund.processed"        |
              |                    |                       |    commandHandler.handleMarkRefundCompleted()
              |                    |                       | 19. Append RefundCompleted to events (v=N+2)
              |                    |                       | 20. Project to payment_view (REFUNDED)  |
              |                    |                       | 21. Outbox: samato.payment.refunded     |
              |                    |                       | 22. IdempotencyGuard.recordResult       |
              |                    |                       |                                       |
              |                    |                       |  ---- Outbox tick (500ms) ---->          |
              |                    |                       |       publish samato.payment.refund.initiated
              |                    |                       |       publish samato.payment.refunded  |
              |                    |                       |       (via KafkaTemplate<byte[]>)     |
              |                    |                       |                                       v
              |                    |                       |                                   Kafka
              |                    |                       |                                   (samato.payment.refunded)
              |                    |                       |                                   (no consumer in this repo)
              |                    |                       |
              |  ---- Alice reloads the order page ----     |
              |  GET /api/payments/{id}    -----> api-gateway -> payment-service -> PaymentView
              |<----- 200 OK status: REFUNDED ------------- |
```

The key actors:
- **Alice** — the customer with a JWT.
- **api-gateway** — terminates the JWT validation, injects headers, routes.
- **order-service** — runs the saga, calls payment-service over Feign, owns `orders`, `saga_instances`, `saga_steps`, `outbox_events`.
- **payment-service** — owns the event-sourced ledger, calls Razorpay over HTTPS, handles webhooks.
- **Postgres** (two databases: `order_service` and `payment_service`) — one per service, no cross-service joins ([database-per-service](../00-glossary.md#database-per-service)).
- **Razorpay** — the source of truth for whether the money actually moved back to Alice's card (ADR-13: [Razorpay is the source of truth for money](../../ARCHITECTURE.md#key-architectural-decisions-adrs)).
- **Kafka** — the event backbone; in this flow it carries the `samato.payment.*` events to **no one** (no consumers in the monorepo — see §14).

---

## 4. Step 1: Order-service receives the refund request

> **Beginner-friendly framing.** A "refund" in Samato is **saga compensation**. There is no direct "refund this payment" button that hits `POST /api/payments/{id}/refunds` from a user — that endpoint is only called as a downstream step of compensation. The user-facing entry point is `POST /api/orders/{id}/cancel` (per the `OrderController` javadoc — note that in the current source the cancel endpoint is documented in the class javadoc but the controller itself does not declare a `@PostMapping("/cancel")` handler; cancellation is performed as compensation by the saga engine when downstream steps fail, or by an `ADMIN` action through the saga tooling). When the user taps "Cancel" or "Request refund", the request flows through the saga, and the saga's `compensateStep` invokes the payment-service refund Feign client.

The HTTP request, in the canonical form:

```http
POST /api/orders/{id}/cancel HTTP/1.1
Host: api.samato.com
Authorization: Bearer <jwt-with-CUSTOMER-role>
X-Correlation-Id: 7f3b2a1d-...
```

The controller method (logical, based on the saga trigger; the cancel mapping is the documented entry):

```java
@PostMapping("/{id}/cancel")
@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")
public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    Order order = orderService.get(id);
    if (!jwt.getSubject().equals(order.getCustomerId().toString())
            && !hasRole(jwt, "ADMIN")) {
        throw new DomainException("FORBIDDEN", "Cannot cancel another customer's order", 403);
    }
    // Mark the order CANCELLED; the saga's compensation step handles the refund.
    return orderService.cancel(order);
}
```

- **Source:** the documented endpoint lives in [`OrderController.java:29`](../../services/order-service/src/main/java/com/samato/orderservice/web/OrderController.java) (javadoc) and the compensation logic is in [`SagaEngine.compensateStep`](../../services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java).
- **What guards the call:** Spring Security's `@PreAuthorize` runs first (the request must carry a `CUSTOMER` or `ADMIN` role). The in-method ownership check then throws a 403 if Alice is not the order's customer and not an admin. The result is rendered as a uniform JSON envelope by `GlobalExceptionHandler` in the `shared` module — see [DomainException](../00-glossary.md#restcontrolleradvice).
- **The order must be in a non-terminal state.** `Order.transitionTo(OrderStatus.CANCELLED)` rejects transitions from `CONFIRMED` and `CANCELLED` — once the order is `CONFIRMED`, cancellation is performed by a separate compensation path (the saga's `compensateStep` is the only writer of `CANCELLED` after `CONFIRMED`). For a `CAPTURED` order, the saga walks backwards and calls the payment-service refund.
- **What changes in the system:** A `CANCELLED` order, a `COMPENSATING` saga, a `CHARGE_PAYMENT` step in `COMPENSATING → COMPENSATED` state, an outbound Feign call to payment-service.

> **Note on a real-world gap.** The order-service has no `@PostMapping("/cancel")` in the current source — only the javadoc declares it. The actual cancel path today is "saga compensation" (e.g. if `CONFIRM_ORDER` fails after `CHARGE_PAYMENT` succeeded, the saga walks back and issues the refund). A user-driven cancel endpoint is Phase 6 work. This use case documents the **compensation** path that today *is* the refund flow.

---

## 5. Step 2: Order-service calls payment-service via Feign

The saga's `compensateStep` walks the completed steps in **reverse order**. For the `CHARGE_PAYMENT` step it invokes `PaymentClient.refund(...)`. The relevant code path is in [`SagaEngine.java:370-390`](../../services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java):

```java
// In SagaEngine.compensateStep, CHARGE_PAYMENT branch:
case CHARGE_PAYMENT -> {
    String paymentId = loadPaymentIdFromStep(sagaStep);
    PaymentDtos.RefundRequest body = new PaymentDtos.RefundRequest(amount);
    paymentClient.refund(paymentId, idempotencyKey, body);
    sagaStep.markCompensated();
}
```

The Feign interface ([`PaymentClient.java`](../../services/order-service/src/main/java/com/samato/orderservice/api/PaymentClient.java)):

```java
@FeignClient(
    name = "samato-payment-service",
    fallbackFactory = PaymentClientFallback.class)
public interface PaymentClient {
    @PostMapping("/api/payments/{id}/refunds")
    PaymentResponse refund(
        @PathVariable("id") UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody RefundRequest body);
}
```

- **Feign target:** the Eureka service id `samato-payment-service`. Discovery resolves to `http://payment-service:8084` in the docker network.
- **Headers:** the `Idempotency-Key` is the saga-step UUID (per-saga uniqueness, see [Idempotency](../00-glossary.md#idempotency)). The customer's bearer JWT is forwarded by the [`FeignAuthForwarder`](../00-glossary.md#feignclient) bean (a `RequestInterceptor` registered as a `@Configuration` in `order-service`).
- **JWT propagation:** the interceptor plumbs the inbound token onto every outbound Feign call. The thread-local is set by `BearerTokenCaptureFilter` (an inner class of `SecurityConfig`) and cleared in a `finally` block to avoid thread-pool leaks. On background poller threads (where there is no inbound token), the interceptor falls back to a long-lived service token fetched at startup by `ServiceTokenProvider` via `GET /api/auth/dev-token?email=service@system`. See [`ServiceTokenProvider.java`](../../services/order-service/src/main/java/com/samato/orderservice/api/ServiceTokenProvider.java).
- **Failure handling:** if the Feign call fails, the `PaymentClientFallback` (a `FallbackFactory<PaymentClient>`) returns a stub whose `refund(...)` throws `PaymentUnavailableException`. The saga treats this as transient and the `SagaPoller` (1-second tick) re-drives the compensation on the next pass. The Spring Cloud OpenFeign call has no `@CircuitBreaker` annotation — the saga's own retry semantics are the resilience boundary, by design (see [ARCHITECTURE.md Failure semantics](../../ARCHITECTURE.md#failure-semantics)).

> **Anomaly to keep in mind.** Resilience4j policies for `payment-client` are **declared in `application.yml` but not annotated on the Feign interface**. The `fallbackFactory` is the actual fault-isolation mechanism. See [payment-service.md §3.4](../services/payment-service.md#34-comsamatoorderserviceapiapipaymentclientjava) (a mirror doc on the order side) and [ARCHITECTURE.md Bring-up summary](../../ARCHITECTURE.md#bring-up-summary-2026-07-08).

---

## 6. Step 3: Payment-service initiates the refund

The Feign call lands on [`PaymentController.refund`](../../services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java) in payment-service:

```java
@PostMapping("/{id}/refunds")
public PaymentResponse refund(@PathVariable UUID id,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @Valid @RequestBody RefundRequest req) {
    Payment p = queryService.loadAggregate(id);
    PaymentCommand.RefundPayment cmd = new PaymentCommand.RefundPayment(
        UUID.randomUUID(), id, Money.of(req.amount(), p.getCurrency()), idempotencyKey);
    return paymentService.refund(cmd, idempotencyKey);
}
```

Then the call chain inside payment-service:

1. **`PaymentService.refund`** ([`PaymentService.java:68-76`](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentService.java)) wraps the work in `idempotency.executeOnce("RefundPayment", idempotencyKey, ...)`.
2. **`IdempotencyGuard.executeOnce`** ([`IdempotencyGuard.java:46-66`](../../services/payment-service/src/main/java/com/samato/paymentservice/idempotency/IdempotencyGuard.java)) does a `processed_commands` lookup by `(command_type, key)`. If found, returns the cached result. If not, runs the supplier.
3. **`PaymentCommandHandler.handleRefund`** ([`PaymentCommandHandler.java:153-168`](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java)) runs inside `@Transactional`:
   ```java
   @Transactional
   public PaymentResponse handleRefund(PaymentCommand.RefundPayment cmd, String idempotencyKey) {
       Payment p = paymentRepository.load(cmd.paymentId())
               .orElseThrow(() -> new IllegalStateException("No payment " + cmd.paymentId()));

       // Call Razorpay to get the refund id.
       RazorpayClient.RazorpayRefundResult result = razorpay.refund(
           p.getRazorpayPaymentId(),
           RazorpayClientImpl.toPaise(cmd.amount().amount()),
           idempotencyKey);

       return mutate(cmd.paymentId(), cmd.commandId(), payment ->
           payment.initiateRefund(cmd, result.razorpayRefundId(), nextVersion(payment, 1)));
   }
   ```

The `mutate(...)` helper ([`PaymentCommandHandler.java:193-209`](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java)) runs the decider and **then** persists, projects, and enqueues — all in the same DB transaction:

```java
private PaymentResponse mutate(UUID paymentId, UUID commandId, Function<Payment, PaymentEvent> decider) {
    Payment p = paymentRepository.load(paymentId)
            .orElseThrow(() -> new IllegalStateException("No payment " + paymentId));

    decider.apply(p);                                          // (a) decide

    List<PaymentEvent> uncommitted = p.drainUncommitted();     // (b) take the new events
    if (uncommitted.isEmpty()) return toResponse(p);
    List<EventStoreEntry> entries = eventStore.append(paymentId, commandId, uncommitted);  // (c) append
    projector.apply(entries);                                  // (d) project
    for (EventStoreEntry e : entries) enqueueOutbox(e);        // (e) enqueue outbox
    snapshotter.maybeSnapshot(p);                              // (f) maybe snapshot
    return toResponse(p);
}
```

### The aggregate decides

`Payment.initiateRefund(cmd, razorpayRefundId, nextVersion)` ([`Payment.java:147-161`](../../services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java)) validates the state machine:

```java
public PaymentEvent.RefundInitiated initiateRefund(PaymentCommand.RefundPayment cmd,
                                      String razorpayRefundId, int nextVersion) {
    if (status != PaymentStatus.CAPTURED) {
        throw new IllegalStateException("Cannot refund non-captured payment: " + status);
    }
    if (!cmd.amount().currency().equals(currency)) {
        throw new IllegalStateException("Refund currency mismatch: " + cmd.amount().currency());
    }
    PaymentEvent.RefundInitiated event = new PaymentEvent.RefundInitiated(
        paymentId, razorpayOrderId, razorpayPaymentId,
        razorpayRefundId, cmd.amount(), nextVersion);
    apply(event);
    return event;
}
```

- **Invariant:** the only way state changes is through `apply(PaymentEvent)` (the aggregate has **no setters** — the compiler enforces this). The `RefundInitiated` event is added to `uncommitted` and then drained by the handler.
- **Status transition:** `CAPTURED → REFUND_INITIATED`. The next event (when the webhook arrives) will be `RefundCompleted` → `REFUNDED`.

### The event store append

`PostgresEventStore.append` ([`PostgresEventStore.java:55-79`](../../services/payment-service/src/main/java/com/samato/paymentservice/eventstore/PostgresEventStore.java)) runs in `REQUIRES_NEW` so a half-committed batch doesn't pollute the store:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public List<EventStoreEntry> append(UUID aggregateId, UUID commandId, List<PaymentEvent> events) {
    int currentVersion = repository.findLatestVersion(aggregateId);
    for (PaymentEvent e : events) {
        int expected = ++currentVersion;
        if (e.version() != expected) {
            throw new IllegalStateException("Event version " + e.version()
                + " does not match expected " + expected + " for aggregate " + aggregateId);
        }
    }
    try {
        List<EventStoreEntry> entries = events.stream()
                .map(e -> EventStoreEntry.from(aggregateId, "Payment", e, commandId, eventSerde))
                .toList();
        return repository.saveAll(entries);
    } catch (DataIntegrityViolationException dup) {
        int actual = repository.findLatestVersion(aggregateId);
        throw new OptimisticLockException(aggregateId, currentVersion, actual);
    }
}
```

The `UNIQUE(aggregate_id, version)` constraint is the **optimistic-concurrency safety net**: a concurrent writer that loses the race gets `DataIntegrityViolationException`, which is translated to `OptimisticLockException`. For refunds this is unlikely (the saga's compensation is serial), but the guarantee is the same as for any event-sourced append.

### The projector updates the read model

`PaymentProjector.apply` ([`PaymentProjector.java:51-110`](../../services/payment-service/src/main/java/com/samato/paymentservice/projection/PaymentProjector.java)) runs **in the same transaction** as the event append. For the `RefundInitiated` event:

```java
case "RefundInitiated" -> {
    PaymentEvent.RefundInitiated ev = typed(e, PaymentEvent.RefundInitiated.class);
    view.setRazorpayPaymentId(ev.razorpayPaymentId());
    view.setStatus(PaymentStatus.REFUND_INITIATED);
}
```

This is the **CQRS read model** — a denormalised row in `payment_view` that the REST API queries. Because the projector runs in the same transaction as the event append, the read model and the events table are **atomically consistent** — no "is the view up to date?" question (see [CQRS](../00-glossary.md#cqrs)).

### The Razorpay call

`RazorpayClientImpl.refund` ([`RazorpayClientImpl.java:86-105`](../../services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java)) wraps the official `com.razorpay:razorpay-java` SDK:

```java
@Override
public RazorpayRefundResult refund(String razorpayPaymentId, long amountPaise, String idempotencyKey) {
    validateAmount(amountPaise);
    try {
        JSONObject request = new JSONObject();
        request.put("amount", amountPaise);

        log.info("Refunding Razorpay payment {}: amountPaise={}", razorpayPaymentId, amountPaise);
        Refund refund = razorpay.payments.refund(razorpayPaymentId, request);

        return new RazorpayRefundResult(
            refund.get("id"),
            razorpayPaymentId,
            refund.get("amount"),
            refund.get("currency"),
            refund.get("status")
        );
    } catch (RazorpayException e) {
        throw new PaymentGatewayException("Razorpay refund failed for " + razorpayPaymentId, e);
    }
}
```

- **Money is in paise on the wire.** `RazorpayClientImpl.toPaise(BigDecimal rupees)` ([`RazorpayClientImpl.java:128-132`](../../services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java)) uses `RoundingMode.HALF_EVEN` (banker's rounding) to convert `BigDecimal` rupees to `long` paise. Domain is always in rupees; the boundary converts in one place.
- **The `razorpay_refund_id` is what we save.** The `refund.get("id")` (e.g. `rfnd_XXXXXXXXXXXXXXXX`) is stored in the `RefundInitiated` event. The `refund.processed` webhook will echo this id back; the webhook handler uses it to find the right aggregate.
- **RazorpayClient is NOT a Feign client.** Despite the inventory's earlier confusion, this is a hand-rolled wrapper around the `com.razorpay:razorpay-java` SDK. The `RazorpayClient` interface exists for testability (mock it in unit tests) and is implemented by `RazorpayClientImpl`. There is no `@FeignClient` annotation on the interface.
- **RazorpayClientFallback is dead code.** The class [`RazorpayClientFallback.java`](../../services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientFallback.java) implements `RazorpayClient` and throws `PaymentGatewayException` on every call, but it is **not a Spring bean** (no `@Component`). The real failure path is the `try/catch` inside `RazorpayClientImpl`, which throws `PaymentGatewayException` on `RazorpayException`. See the comment in `RazorpayClient.java:16-20` for the design rationale.

### The idempotency record

`IdempotencyGuard.recordResult` ([`IdempotencyGuard.java:78-93`](../../services/payment-service/src/main/java/com/samato/paymentservice/idempotency/IdempotencyGuard.java)) writes the result to `processed_commands` in a `REQUIRES_NEW` transaction so the supplier's transaction (which has already committed) doesn't double-wrap:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordResult(String commandType, String key, UUID aggregateId,
                         int httpStatus, Object result) {
    String body = objectMapper.writeValueAsString(result);
    UUID aid = aggregateId != null ? aggregateId : deriveAggregateId(key);
    repository.save(new ProcessedCommand(
        UUID.randomUUID(), commandType, key, aid, httpStatus, body));
}
```

The `UNIQUE(command_type, key)` constraint on `processed_commands` is the dedup mechanism. A saga retry with the same `Idempotency-Key` returns the cached response without re-running the work — and without calling Razorpay a second time.

---

## 7. Step 4: The outbox publishes a `RefundInitiated` event

`PaymentCommandHandler.enqueueOutbox` ([`PaymentCommandHandler.java:215-228`](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java)) writes a row to `outbox_events` **in the same transaction** as the event append. The outbox row is the bridge between the database and Kafka without 2PC — see [Transactional Outbox](../00-glossary.md#transactional-outbox).

```java
private void enqueueOutbox(EventStoreEntry e) {
    // We use the raw JSONB payload as the Kafka wire format. The
    // shared-kafka Avro schemas are used by order-service-side
    // consumers; here we just forward the JSON.
    byte[] body = e.getEventData() == null ? new byte[0] : e.getEventData().getBytes();
    outboxRepository.save(new OutboxEvent(
        UUID.randomUUID(),
        "Payment",
        e.getAggregateId(),
        topicFor(e.getEventType()),
        e.getEventType(),
        body
    ));
}

private String topicFor(String eventType) {
    return switch (eventType) {
        case "RazorpayOrderCreated" -> "samato.payment.created";
        case "PaymentCaptured"      -> "samato.payment.charged";
        case "PaymentFailed"        -> "samato.payment.failed";
        case "RefundInitiated"      -> "samato.payment.refund.initiated";
        case "RefundCompleted"      -> "samato.payment.refunded";
        case "PaymentExpired"       -> "samato.payment.expired";
        default                    -> "samato.payment.unknown";
    };
}
```

`OutboxPublisher.publishPending` ([`OutboxPublisher.java:45-67`](../../services/payment-service/src/main/java/com/samato/paymentservice/outbox/OutboxPublisher.java)) drains the outbox:

```java
@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")
@Transactional
public void publishPending() {
    List<OutboxEvent> batch = repository.findUnsent(PageRequest.of(0, BATCH_SIZE));
    if (batch.isEmpty()) return;

    for (OutboxEvent e : batch) {
        try {
            kafkaTemplate.send(e.getTopic(), e.getAggregateId().toString(), e.getPayload())
                    .get(5, TimeUnit.SECONDS);
            e.setSentAt(OffsetDateTime.now());
            log.debug("Published outbox event {} to {}", e.getId(), e.getTopic());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Outbox publish interrupted");
            return;
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("Outbox publish failed for {} (will retry): {}", e.getId(), ex.getMessage());
            return;
        }
    }
}
```

- **Poll interval:** `samato.outbox.poll-ms` (default **500 ms**). Configurable in `application.yml`.
- **Batch size:** hardcoded to `BATCH_SIZE = 50`.
- **Send mode:** **synchronous** with `kafkaTemplate.send(...).get(5, TimeUnit.SECONDS)`. This is deliberate: the row stays unsent (and is retried next tick) if the broker is unreachable. The `OutboxEvent.sent_at` is only set after the broker acks.
- **Kafka template:** `KafkaTemplate<String, byte[]>` from the local [`KafkaByteArrayConfig`](../../services/payment-service/src/main/java/com/samato/paymentservice/config/KafkaByteArrayConfig.java) — **NOT** the `KafkaTemplate<String, SpecificRecord>` from `shared-kafka`. The payload is the raw `event_data` JSON bytes from the event store (the column is `JSONB` in Postgres, written as a `String` via `@Type(JsonType.class)`).

### The `samato.payment.refund.initiated` anomaly

This topic has **no `.avsc` schema** in `shared-kafka/src/main/avro/`. The Avro class `PaymentRefundedEvent` exists in the project (see [`PaymentRefundedEvent.avsc`](../../shared-kafka/src/main/avro/PaymentRefundedEvent.avsc)) but it is for the `samato.payment.refunded` topic, not `refund.initiated`. The `refund.initiated` topic is documented in [`shared-and-kafka.md` §4.6](../services/shared-and-kafka.md#46-avro-schemas-sharedkafkasrcmainavroavsc) as "raw JSON bytes from event store; no Schema-Registry encoding."

> **What this means for a future consumer.** A service that wants to subscribe to `samato.payment.refund.initiated` must use `ByteArrayDeserializer` + Jackson, not `KafkaAvroDeserializer`. The shape of the JSON is the `RefundInitiated` record from [`PaymentEvent.java:62-70`](../../services/payment-service/src/main/java/com/samato/paymentservice/domain/event/PaymentEvent.java): `{ paymentId, razorpayOrderId, razorpayPaymentId, razorpayRefundId, amount: { amount, currency }, version }`.

The key (used as the partition key) is `paymentId.toString()`, so all events for one payment go to the same partition — see [Kafka key](../00-glossary.md#key-kafka).

---

## 8. Step 5: Razorpay processes the refund and sends a webhook

Razorpay's processing time is **seconds to a minute** (in test mode it's instant; in production with the customer's bank it can take longer). When the refund is settled, Razorpay POSTs the result to our webhook URL.

The webhook path is on a **separate Spring Security filter chain** ([`RazorpayWebhookSecurityConfig.java`](../../services/payment-service/src/main/java/com/samato/paymentservice/security/RazorpayWebhookSecurityConfig.java)):

```java
@Configuration
@EnableWebSecurity
public class RazorpayWebhookSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain webhookFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/payments/webhooks/**")
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
```

- **Why a separate chain?** Razorpay doesn't have a JWT. We need a path that doesn't go through the JWT filter, so we put it on a higher-precedence chain that matches `/api/payments/webhooks/**` and uses `permitAll`. The controller does the real auth (HMAC signature verification).
- **Order matters:** `@Order(Ordered.HIGHEST_PRECEDENCE)` ensures this chain is matched **before** the default JWT chain. The default chain's matcher doesn't include the webhook path, so it never sees webhook requests.

### WebhookController

[`WebhookController.java`](../../services/payment-service/src/main/java/com/samato/paymentservice/web/WebhookController.java) handles the inbound POST:

```java
@PostMapping("/razorpay")
public ResponseEntity<Void> handle(
        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
        @RequestBody String rawBody) {
    if (signature == null || signature.isBlank()) {
        log.warn("Razorpay webhook missing signature header");
        return ResponseEntity.status(401).build();
    }
    if (!razorpay.verifyWebhookSignature(rawBody, signature)) {
        log.warn("Razorpay webhook signature verification failed");
        return ResponseEntity.status(401).build();
    }
    try {
        JsonNode event = objectMapper.readTree(rawBody);
        paymentService.handleWebhook(rawBody, event);
    } catch (Exception e) {
        log.error("Razorpay webhook processing error: {}", e.getMessage(), e);
        return ResponseEntity.status(500).build();
    }
    return ResponseEntity.ok().build();
}
```

- **Raw body as `String`.** The signature is HMAC-SHA256 of the **exact bytes** Razorpay sent. If we parse to a DTO and re-serialise, the whitespace and key ordering would change and the signature would fail. So `@RequestBody String rawBody` keeps the bytes untouched.
- **Signature verification.** `RazorpayClientImpl.verifyWebhookSignature` ([`RazorpayClientImpl.java:108-116`](../../services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java)) delegates to `com.razorpay.Utils.verifyWebhookSignature(payload, signature, webhookSecret)`. The webhook secret is configured via `razorpay.webhook_secret` (`RAZORPAY_WEBHOOK_SECRET` env var). The `WebhookSignatureVerifier` utility class is **dead code** — it implements a constant-time HMAC comparison but is never called; the SDK call is the active path. See [payment-service.md §3.6](../services/payment-service.md#36-apiwebhooksignatureverifierjava).
- **What the 401 / 500 mean.** A `401` says "your signature was bad" — Razorpay retries. A `500` says "I couldn't process this" — Razorpay retries. Either way, the dedup is on the **Razorpay event id**, not on `Idempotency-Key` (webhooks don't have one).

### Dispatch to the command handler

`PaymentService.handleWebhook` ([`PaymentService.java:83-110`](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentService.java)) dedupes on the Razorpay event id, then dispatches:

```java
public boolean handleWebhook(String rawBody, JsonNode event) {
    String eventId = event.path("id").asText(null);
    String eventType = event.path("event").asText(null);
    if (eventId == null || eventType == null) {
        log.warn("Webhook missing id or event field, ignoring");
        return false;
    }
    Optional<String> replay = idempotency.findReplay("RazorpayWebhook", eventId);
    if (replay.isPresent()) {
        log.info("Razorpay webhook {} is a replay — skipping", eventId);
        return false;
    }

    try {
        dispatchWebhook(eventType, event);
    } catch (Exception e) {
        // We DON'T mark the webhook processed on failure — Razorpay
        // will retry. This is the standard webhook pattern.
        log.error("Webhook {} ({}) failed: {}", eventId, eventType, e.getMessage());
        throw e;
    }

    // Mark as processed AFTER successful dispatch.
    idempotency.recordResult("RazorpayWebhook", eventId,
            null /* aggregate resolved per-event */, 200, "{\"ok\":true}");
    return true;
}
```

The `dispatchWebhook` switch routes by event type:

```java
private void dispatchWebhook(String eventType, JsonNode event) {
    switch (eventType) {
        case "payment.captured" -> { ... }
        case "payment.failed"   -> { ... }
        case "refund.processed"  -> {
            String razorpayPaymentId = event.at("/payload/refund/entity/payment_id").asText();
            String razorpayRefundId  = event.at("/payload/refund/entity/id").asText();
            long amountPaise         = event.at("/payload/refund/entity/amount").asLong(0);
            String currency          = event.at("/payload/refund/entity/currency").asText("INR");
            Money amount = Money.of(BigDecimal.valueOf(amountPaise).movePointLeft(2), currency);
            UUID paymentId = lookupPaymentIdByRazorpayPaymentId(razorpayPaymentId);
            commandHandler.handleMarkRefundCompleted(paymentId, UUID.randomUUID(),
                    razorpayRefundId, amount);
        }
        default -> log.warn("Unknown Razorpay webhook event type: {}", eventType);
    }
}
```

For the `refund.processed` event, the handler looks up the aggregate id by the `razorpay_payment_id` (using a native query on the JSONB event_data column — see [`EventStoreEntryRepository.findByRazorpayPaymentId`](../../services/payment-service/src/main/java/com/samato/paymentservice/eventstore/EventStoreEntryRepository.java)) and then calls:

```java
@Transactional
public PaymentResponse handleMarkRefundCompleted(UUID paymentId, UUID commandId,
                                                String razorpayRefundId, Money amount) {
    return mutate(paymentId, commandId, p ->
        p.completeRefund(razorpayRefundId, amount, nextVersion(p, 1)));
}
```

The `Payment.completeRefund` decider ([`Payment.java:163-174`](../../services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java)) validates the state machine:

```java
public PaymentEvent.RefundCompleted completeRefund(String razorpayRefundId, Money refundAmount, int nextVersion) {
    if (status != PaymentStatus.REFUND_INITIATED) {
        throw new IllegalStateException("Cannot complete refund in state: " + status);
    }
    if (!razorpayRefundId.equals(lastRazorpayRefundId)) {
        throw new IllegalStateException("Refund id mismatch: " + razorpayRefundId
                + " vs " + lastRazorpayRefundId);
    }
    PaymentEvent.RefundCompleted event = new PaymentEvent.RefundCompleted(
        paymentId, razorpayRefundId, refundAmount, nextVersion);
    apply(event);
    return event;
}
```

The same `mutate` helper then appends `RefundCompleted` to the `events` table, projects the read model to `REFUNDED`, enqueues an outbox row for `samato.payment.refunded`, and (if version is a multiple of 50) writes a snapshot.

### The `samato.payment.refunded` topic

Unlike `samato.payment.refund.initiated`, this topic **has an Avro schema**: [`PaymentRefundedEvent.avsc`](../../shared-kafka/src/main/avro/PaymentRefundedEvent.avsc). However, the wire format is still **the same byte[] of raw JSONB from the event store** (the outbox uses the byte-array template, not the Avro template). The Avro class is unused at runtime — see [the anomaly in §13](#13-the-avro-vs-byte-anomaly-callout).

> **Why does the Avro schema exist if it's not used on the wire?** The schema was authored in the early design phase when the plan was to use Confluent Avro end-to-end, but the order/payment services were never switched to the Avro template (see [shared-and-kafka.md Anomaly 4](../services/shared-and-kafka.md#anomalies-and-how-to-interpret-them)). The schema is "documentation" — it documents the field shape that a future Avro encoder would produce. The actual JSON on the topic follows the same field names but is plain text.

---

## 9. Step 6: The user sees the refund

Alice has been watching a spinner since she tapped "Request refund". The badge now reads **"Refunded"**. How did she get there?

### Polling the payment view

Alice's app fires a `GET /api/payments/{id}` (or `GET /api/orders/{id}`, which the order-service serves from its own `orders` table — see §14 below for the gap on order-side reactivity). The request lands on [`PaymentController.get`](../../services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java):

```java
@GetMapping("/{id}")
public PaymentResponse get(@PathVariable UUID id) {
    return queryService.findById(id);
}
```

`PaymentQueryService.findById` ([`PaymentQueryService.java:47-52`](../../services/payment-service/src/main/java/com/samato/paymentservice/query/PaymentQueryService.java)) reads from the CQRS read model:

```java
@Transactional(readOnly = true)
public PaymentResponse findById(UUID paymentId) {
    PaymentView v = viewRepository.findById(paymentId)
            .orElseThrow(() -> new NoSuchElementException("No payment " + paymentId));
    return toResponse(v);
}
```

The `payment_view` row was updated **inside the same DB transaction** as the `RefundCompleted` event append. So the moment the webhook handler commits, the read model is up to date.

### Why the read model is "eventually consistent" but really not

In a system that projects events via a Kafka consumer, the read model is *eventually* consistent — there is a small window between event publish and consumer projection. In Samato's payment-service, the projection is **in-process and transactional** ([`PaymentProjector.apply` is called from `mutate` in the same `@Transactional` boundary](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java)). The read model is **atomically consistent** with the event store — see [CQRS](../00-glossary.md#cqrs).

> **The "eventual" lag in this flow.** The lag is between **payment-service commits** and **Razorpay's webhook delivery**. The lag is on Razorpay's side, not on the projection. Once the webhook is processed, the view is current.

---

## 10. Step 7: Time-travel queries (the event-sourcing payoff)

This is where event sourcing earns its keep. Two weeks after Alice's refund, the finance team needs to answer: "What is the full history of this payment? When was it captured? When was the refund initiated? When did Razorpay confirm the refund? What was the order total? What was the refund amount?"

With a state-stored ledger, this question requires a forensic pull of `payment_view` history (which we don't have — we only store the latest row) plus Razorpay's logs plus a manual join. With event sourcing, the answer is **one SQL query**: read the rows for this `aggregate_id` from the `events` table.

### The endpoint

`GET /api/payments/{id}/events` (declared in [`PaymentController.java:84-89`](../../services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java)):

```java
@GetMapping("/{id}/events")
public List<PaymentDtos.EventResponse> events(@PathVariable UUID id) {
    return queryService.loadEvents(id).stream()
            .map(e -> new PaymentDtos.EventResponse(0, e.version(), e.getClass().getSimpleName(), null))
            .toList();
}
```

The implementation is `PostgresEventStore.loadStream(aggregateId)` ([`PostgresEventStore.java:84-88`](../../services/payment-service/src/main/java/com/samato/paymentservice/eventstore/PostgresEventStore.java)):

```java
public List<PaymentEvent> loadStream(UUID aggregateId) {
    return repository.findStreamByAggregateId(aggregateId).stream()
            .map(e -> eventSerde.fromJson(e.getEventType(), e.getEventData()))
            .toList();
}
```

The repository call is a JPQL query on the `events` table:

```sql
SELECT * FROM events
WHERE aggregate_id = :aggregateId
ORDER BY version ASC
```

### What the finance team sees

For Alice's order, the events come back in order:

| version | sequence | event_type | event_data (truncated) |
|--:|--:|---|---|
| 1 | 100001 | RazorpayOrderCreated | `{ "paymentId": "...", "orderId": "...", "amount": { "amount": 499.00, "currency": "INR" }, "razorpayOrderId": "order_XXX", ... }` |
| 2 | 100045 | PaymentCaptured | `{ "paymentId": "...", "razorpayPaymentId": "pay_XXX", "amount": { "amount": 499.00, "currency": "INR" }, ... }` |
| 3 | 100120 | RefundInitiated | `{ "paymentId": "...", "razorpayRefundId": "rfnd_YYY", "amount": { "amount": 499.00, "currency": "INR" }, ... }` |
| 4 | 100189 | RefundCompleted | `{ "paymentId": "...", "razorpayRefundId": "rfnd_YYY", "amount": { "amount": 499.00, "currency": "INR" }, ... }` |

The `events` table is the source of truth. The `payment_view` is a **materialised projection** of it. The snapshot table (`payment_snapshots`) is a performance optimisation to avoid replaying thousands of events on a hot wallet.

### What can be replayed

- **`GET /api/payments/{id}/events`** — the full event log. This is the audit trail.
- **`GET /api/payments/{id}/balance-at/{version}`** ([`PaymentController.java:94-98`](../../services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java)) — replay events up to version N and return the state at that point. Uses `PaymentQueryService.balanceAt` → `loadAtVersion` ([`PaymentQueryService.java:71-95`](../../services/payment-service/src/main/java/com/samato/paymentservice/query/PaymentQueryService.java)).
- **`loadAggregate(id)`** — full replay to current state. Used internally by the command handler.
- **`PostgresEventStore.findByRazorpayOrderId(razorpayOrderId)`** and `findByRazorpayPaymentId(...)` — native JSONB queries that look up the aggregate from a Razorpay-side id (used by the webhook handler to reconcile). See [`EventStoreEntryRepository`](../../services/payment-service/src/main/java/com/samato/paymentservice/eventstore/EventStoreEntryRepository.java).

### Snapshots (the practical limit on replay cost)

For a hot wallet (thousands of events), replaying from version 1 every time is expensive. `Snapshotter` ([`Snapshotter.java`](../../services/payment-service/src/main/java/com/samato/paymentservice/eventstore/snapshot/Snapshotter.java)) writes a snapshot every `samato.snapshot.every-n-events` (default **50**). On load, the repository hydrates from the latest snapshot and replays events **strictly after** the snapshot's version:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void maybeSnapshot(Payment payment) {
    if (payment.getVersion() <= 0) return;
    if (payment.getVersion() % everyNEvents != 0) return;
    // ... serialise SnapshotState and save to payment_snapshots ...
}
```

Worst-case replay is ~50 events. See [Snapshotter in payment-service.md §3.21](../services/payment-service.md#321-eventsnapshotsnapshotterjava) and [Snapshot in the glossary](../00-glossary.md#event-sourcing).

---

## 11. What can go wrong

Each entry below pairs a realistic failure with the actual code path that handles (or doesn't handle) it.

### 11.1 Alice requests a refund before the payment is captured

**Failure:** the `Payment` aggregate is in `ORDER_CREATED` (no webhook yet). Alice taps "Cancel" but the order isn't `CONFIRMED` — so the saga never even gets to the refund step. The saga's `CONFIRM_ORDER` step is what triggers compensation; without it, there's nothing to compensate.

**Source path:** if the saga's `CHARGE_PAYMENT` step failed (Razorpay rejected the card), the saga walks back and **does not call the refund** because no charge happened. If the saga succeeded through `CONFIRM_ORDER` but the `payment.captured` webhook hasn't arrived, the `Payment` aggregate is in `PAYMENT_INITIATED`, not `CAPTURED`. Calling `Payment.initiateRefund` throws `IllegalStateException("Cannot refund non-captured payment: " + status)`. The HTTP layer maps that to a `500` with the wrapped message.

**Recovery:** wait for the `payment.captured` webhook, then retry. Idempotency makes the retry safe.

### 11.2 Razorpay is down when we try to initiate the refund

**Failure:** `RazorpayClientImpl.refund` throws `PaymentGatewayException` (wrapping `RazorpayException`).

**Source path:** [`RazorpayClientImpl.java:101-104`](../../services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java) catches `RazorpayException` and throws `PaymentGatewayException`. The command handler's `mutate(...)` runs **inside** a `@Transactional` boundary — if the `RazorpayClient.refund` call throws, the transaction rolls back. **The `RefundInitiated` event is NOT appended.** The `outbox_events` row is NOT written. Alice sees a 500.

**Recovery:** retry the saga's `compensateStep`. The `SagaPoller` (1-second tick) re-drives the compensation; the `IdempotencyGuard` ensures the dedup is sound; once Razorpay is back, the refund goes through.

**What about the `RazorpayClientFallback`?** It's **dead code** — the class exists but is not a Spring bean. The actual fault path is the `try/catch` inside `RazorpayClientImpl`. See the comment in [`RazorpayClient.java:16-20`](../../services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClient.java) for the design rationale.

### 11.3 Webhook signature is invalid

**Failure:** Razorpay sends a webhook but with a stale or wrong `X-Razorpay-Signature`.

**Source path:** [`WebhookController.java:57-60`](../../services/payment-service/src/main/java/com/samato/paymentservice/web/WebhookController.java) returns `401` without dispatching. The webhook is **not** added to `processed_commands` — there is no dedup record.

**Recovery:** Razorpay retries the webhook (its default behaviour on non-2xx). The retry carries the same payload + signature. If the signature was wrong due to a clock-skew or secret-mismatch, Razorpay's retry will have the same problem and the operator needs to investigate.

> **Note.** The hand-rolled `WebhookSignatureVerifier` class ([`WebhookSignatureVerifier.java`](../../services/payment-service/src/main/java/com/samato/paymentservice/api/WebhookSignatureVerifier.java)) implements constant-time HMAC-SHA256 but is **never called** by the production code path. The `RazorpayClient.verifyWebhookSignature` delegates to the SDK's `Utils.verifyWebhookSignature`. The class is here for "belt-and-braces" — a future PR could swap it in for the SDK call.

### 11.4 Webhook arrives twice (Razorpay retries after a non-2xx)

**Failure:** Razorpay sends the same `refund.processed` event twice (e.g. the first delivery returned a 500 because the DB connection blipped).

**Source path:** `PaymentService.handleWebhook` ([`PaymentService.java:90-94`](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentService.java)) looks up `idempotency.findReplay("RazorpayWebhook", eventId)`. If found, returns `false` (no work done). The second delivery is a no-op.

**Why this is safe:** the dedup is on the **Razorpay event id** (the `id` field in the webhook JSON, a UUID), not on `Idempotency-Key`. Webhooks don't have `Idempotency-Key` headers; they have Razorpay-generated event ids that are unique per delivery attempt.

> **Asymmetric marking on failure.** Note that `recordResult` is **only called on success** ([`PaymentService.java:106-109`](../../services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentService.java)). On failure, the webhook is NOT marked processed — so Razorpay's retry is honoured. This is the standard webhook pattern. See the comment in `PaymentService.handleWebhook`.

### 11.5 The events table grows unbounded

**Failure:** in a long-lived system, a single payment with thousands of state changes (e.g. partial refunds, retry attempts) accumulates events forever.

**Mitigation:** [`Snapshotter`](../../services/payment-service/src/main/java/com/samato/paymentservice/eventstore/snapshot/Snapshotter.java) writes a snapshot every 50 events (configurable via `samato.snapshot.every-n-events`). The events are still there (append-only, audit-trail), but the replay cost is bounded — the loader hydrates from the latest snapshot and replays only events strictly after it.

**What about the events table size itself?** That's a long-term operational concern. Options for production: partition the `events` table by `created_at` month, archive old events to S3, etc. None of this is implemented today. See [ARCHITECTURE.md Failure semantics](../../ARCHITECTURE.md#failure-semantics) and the `samato.snapshot.every-n-events` config key.

### 11.6 A snapshot is corrupted

**Failure:** the JSONB `snapshot_data` for some payment is malformed (the DB was restored from a bad backup, etc.).

**Source path:** `Snapshotter.loadState` ([`Snapshotter.java:94-102`](../../services/payment-service/src/main/java/com/samato/paymentservice/eventstore/snapshot/Snapshotter.java)) throws `IllegalStateException` if Jackson cannot deserialise the snapshot. The repository loader's caller (`PaymentRepository.load`) propagates the error; the API call returns 500.

**Recovery:** the operator can `DELETE FROM payment_snapshots WHERE payment_id = ?` to force a fallback to the previous snapshot (or to a full replay from version 0). The repository's `loadLatest(paymentId)` does not currently implement "try the latest, fall back to a previous one" — that's a future enhancement. Today the operator intervention is manual.

### 11.7 Alice's JWT expires between refund initiation and webhook handling

**Not a problem.** The webhook is on a separate filter chain (HMAC, not JWT). The webhook signature is the auth. Alice's JWT lifetime is irrelevant to the webhook processing. See [`RazorpayWebhookSecurityConfig.java:35`](../../services/payment-service/src/main/java/com/samato/paymentservice/security/RazorpayWebhookSecurityConfig.java) — the chain's `authorizeHttpRequests` is `permitAll`.

> **The other side of the same coin.** If Alice is polling `GET /api/payments/{id}` after her access token has expired, her client needs to refresh via the refresh token (or the user needs to log in again). That's handled by `auth-service` and is outside the scope of this use case.

### 11.8 The refund is for a partial amount

**Supported.** Razorpay supports partial refunds natively. The `RefundRequest` DTO takes an `amount` (validated `@DecimalMin("0.01")`); the aggregate's `initiateRefund` records that exact `Money` in the `RefundInitiated` event. Multiple partial refunds are possible: each one appends a new `RefundInitiated` event followed (when Razorpay confirms) by a `RefundCompleted` event.

**What's stored in the aggregate.** The `Payment` aggregate currently tracks `lastRazorpayRefundId` (the most recent refund id) but does **not** track a running `amountRefunded`. The amount is in the events. To answer "how much of this payment has been refunded so far?" you would need to sum the `amount.amount()` fields across all `RefundCompleted` events. The PaymentView does not store this aggregate either.

**Caveat.** The `initiateRefund` check `if (!razorpayRefundId.equals(lastRazorpayRefundId))` in `completeRefund` ([`Payment.java:167-170`](../../services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java)) assumes one outstanding refund at a time. Two simultaneous partial refunds where both webhooks arrive out of order could trip this check. This is a known sharp edge; production would track the set of pending refund ids.

### 11.9 The `OUTBOX` poller is slower than 500ms and the row doesn't ship quickly

**Failure:** if `kafkaTemplate.send(...).get(5, SECONDS)` times out, the row stays unsent and the poller returns (without throwing). Next tick retries.

**Source path:** [`OutboxPublisher.java:61-65`](../../services/payment-service/src/main/java/com/samato/paymentservice/outbox/OutboxPublisher.java) catches `ExecutionException` and `TimeoutException` and returns from the method. The transaction commits without setting `sent_at`; the row is retried.

**Recovery:** automatic. The next poller tick picks it up. If Kafka is genuinely down for a long time, the outbox table grows. Production would set up a monitoring alert on the count of unsent outbox rows.

### 11.10 What if Alice (or an attacker) calls `POST /api/payments/{id}/refunds` directly?

**Today, the endpoint requires a valid JWT but no role check.** `@PreAuthorize` is **not** on `PaymentController.refund` — see the [anomaly in payment-service.md §3.36](../services/payment-service.md#336-webpaymentcontrollerjava). The actual security is `authenticated()` from the default `SecurityConfig` filter chain. Anyone with a valid token can hit the endpoint.

**In production this is a problem.** A customer could issue a refund to themselves. The intended deployment would add `@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")` on the controller (matching the inventory's documented behaviour) and ensure the order-service `SERVICE` role is granted to a service account used only by the saga.

> **The same gap exists on the `POST /api/orders` endpoint of order-service.** The `OrderController` is more disciplined (`@PreAuthorize("hasRole('CUSTOMER')")` on `place`), but the payment controller is the loose end.

---

## 12. The data trail

For each step, what is written to which table.

| Step | Table | Row | Notes |
|---|---|---|---|
| 1. `POST /api/orders/{id}/cancel` | `order_service.orders` | `status` → `CANCELLED`, `cancellation_reason` set | Saga compensation path; documented but the controller endpoint isn't implemented in current source — see §4. |
| 2. Saga compensation runs | `order_service.saga_instances` | `status` → `COMPENSATING` | The saga is in compensation. |
| 3. Saga walks back to CHARGE_PAYMENT | `order_service.saga_steps` | `CHARGE_PAYMENT` step → `COMPENSATING` → `COMPENSATED` | Each `REQUIRES_NEW` transaction. |
| 4. Feign call to payment-service | `payment_service.processed_commands` | New row `(command_type="RefundPayment", key=<idempotency-key>, aggregate_id=<payment-id>, result_status=200, result_body=...)` | Written in `IdempotencyGuard.recordResult` after the supplier succeeds. |
| 5. Aggregate loaded | (read-only) | `payment_service.events` is replayed from version 1 (or latest snapshot + post-snapshot events) to get the current state | The `Payment` aggregate is reconstructed. |
| 6. Razorpay call | (Razorpay external) | Razorpay records a `refund` with id `rfnd_XXX` | Money hasn't moved yet — Razorpay is processing. |
| 7. `RefundInitiated` event appended | `payment_service.events` | New row `(sequence_number=N+1, aggregate_id=<payment-id>, event_type='RefundInitiated', event_data=<jsonb>, version=N+1, command_id=<...>)` | Written in `PostgresEventStore.append` inside the command handler's transaction. The `UNIQUE(aggregate_id, version)` constraint is the optimistic-concurrency safety net. |
| 8. Read model updated | `payment_service.payment_view` | `status` → `REFUND_INITIATED` | Written by `PaymentProjector.apply` in the same transaction. |
| 9. Outbox row enqueued | `payment_service.outbox_events` | New row `(id=<uuid>, aggregate_id=<payment-id>, topic='samato.payment.refund.initiated', event_type='RefundInitiated', payload=<jsonb-bytes>, sent_at=NULL)` | In the same transaction as the event append. |
| 10. Maybe snapshot | `payment_service.payment_snapshots` | Row replaced (one per aggregate) if `version % 50 == 0` | In a `REQUIRES_NEW` transaction. |
| 11. Outbox poller picks up | `payment_service.outbox_events` | `sent_at` set to `now()` after the broker acks | The `KafkaTemplate.send(...).get(5, SECONDS)` is synchronous. |
| 12. Webhook arrives | (Razorpay external) | Razorpay POSTs `refund.processed` to `/api/payments/webhooks/razorpay` | Seconds to minutes after step 6. |
| 13. Signature verified | (in-memory) | — | `RazorpayClient.verifyWebhookSignature` (delegates to SDK `Utils.verifyWebhookSignature`). |
| 14. `RefundCompleted` event appended | `payment_service.events` | New row `(sequence_number=N+2, event_type='RefundCompleted', version=N+2, ...)` | Same pattern as step 7. |
| 15. Read model updated | `payment_service.payment_view` | `status` → `REFUNDED` | Same pattern as step 8. |
| 16. Outbox row enqueued | `payment_service.outbox_events` | New row `(topic='samato.payment.refunded', event_type='RefundCompleted', sent_at=NULL)` | Same pattern as step 9. |
| 17. Outbox poller picks up | `payment_service.outbox_events` | `sent_at` set | `samato.payment.refunded` shipped. No consumer in the monorepo — see §14. |
| 18. Webhook dedup recorded | `payment_service.processed_commands` | New row `(command_type='RazorpayWebhook', key=<event-id>, result_status=200, result_body='{"ok":true}')` | Written in `IdempotencyGuard.recordResult` after dispatch succeeds. |

> **Atomicity note.** Steps 7, 8, 9, 10 are all in the same DB transaction. If any one fails (e.g. the projector throws), all roll back. Steps 14, 15, 16, 18 are similarly atomic. The two atomic clusters (initiation, completion) are separated by seconds-to-minutes (Razorpay processing time).

---

## 13. The "Avro vs byte[]" anomaly (callout)

This is the **biggest operational anomaly** in the payment service. Read this once, internalise it, and then everything else makes sense.

### Two on-the-wire formats for Kafka payloads in Samato

| Service | Wire format | Producer | Serializer | Avro schema? |
|---|---|---|---|---|
| `restaurant-service` | Confluent Avro (`magic byte + 4-byte schema id + Avro binary`) | `KafkaTemplate<String, SpecificRecord>` from `shared-kafka` | `KafkaAvroSerializer` | **Yes** — `RestaurantCreatedEvent.avsc`, `RestaurantUpdatedEvent.avsc` in `shared-kafka/src/main/avro/`. The Schema Registry is involved. |
| `order-service` | `byte[]` of `Avro.toString(event).getBytes(UTF_8)` — **JSON text** | `KafkaTemplate<String, byte[]>` from `KafkaByteArrayConfig` | `ByteArraySerializer` | Yes (the `.avsc` files exist) — but the Avro class is unused at runtime. |
| `payment-service` | `byte[]` of `event_data.getBytes()` from the `events` table — **JSON text** | `KafkaTemplate<String, byte[]>` from `KafkaByteArrayConfig` | `ByteArraySerializer` | **Partially** — only 3 of 6 topics have `.avsc` files. |

### The asymmetry in payment-service

The six payment topics, mapped to their `.avsc` files:

| Topic | Avro `.avsc` exists? | On-the-wire payload |
|---|:--:|---|
| `samato.payment.created` | ❌ No | Raw JSONB bytes from `events.event_data` |
| `samato.payment.charged` | ✅ `PaymentChargedEvent.avsc` | Raw JSONB bytes (Avro class unused) |
| `samato.payment.failed` | ✅ `PaymentFailedEvent.avsc` | Raw JSONB bytes (Avro class unused) |
| `samato.payment.refund.initiated` | ❌ No | Raw JSONB bytes |
| `samato.payment.refunded` | ✅ `PaymentRefundedEvent.avsc` | Raw JSONB bytes (Avro class unused) |
| `samato.payment.expired` | ❌ No | Raw JSONB bytes |

**Three payment topics have no Avro schema at all**: `samato.payment.created`, `samato.payment.refund.initiated`, `samato.payment.expired`. These are documented in [`shared-and-kafka.md` §4.6](../services/shared-and-kafka.md#46-avro-schemas-sharedkafkasrcmainavroavsc) as "topics declared in code but not in shared schemas." See [`docs/inventory/shared-and-kafka.json`](../../docs/inventory/shared-and-kafka.json) for the cross-reference.

**Both go through the same byte[] template** (`KafkaTemplate<String, byte[]>` from the local `KafkaByteArrayConfig`). The "Avro or not" question is **not** about the producer template — it's about whether a `.avsc` file exists for the topic. Both kinds of topics use bytes; only the Avro-bearing ones have a Java class generated from the `.avsc` (and that class is used at build time only, not at runtime).

> **For a future consumer of `samato.payment.refund.initiated` or `samato.payment.refunded`:** subscribe with `ByteArrayDeserializer` + Jackson. The JSON shape is the `RefundInitiated` / `RefundCompleted` record from [`PaymentEvent.java`](../../services/payment-service/src/main/java/com/samato/paymentservice/domain/event/PaymentEvent.java). Don't use `KafkaAvroDeserializer` — there is no magic byte + schema id on these messages.

### Why this is the case

The original ADR-12 (see [ARCHITECTURE.md §ADRs](../../ARCHITECTURE.md#key-architectural-decisions-adrs)) said "JSONB in the event store, Avro on the Kafka wire." The event store is JSONB; the Kafka wire is **not** Avro for order/payment services — it is JSON text via the byte[] template. The `.avsc` files were authored but never wired to the producer template. The result is a hybrid system where restaurant-service is the only true Confluent Avro producer/consumer pair.

### What this means for a learner

If you are following the code, the Avro classes in `shared-kafka/target/generated-sources/avro/com/samato/events/PaymentRefundedEvent.java` will look "real" — they have getters, builders, all the Avro machinery. They are **not** instantiated at runtime. The `OutboxEvent.payload` column stores the raw `event_data` JSON string as bytes. The wire bytes are JSON. If you see code that does `event.toString().getBytes(UTF_8)`, that is the byte[]-as-JSON pattern (used in `order-service`); `payment-service` does the same thing by serialising the `events` table's `event_data` JSONB column directly to bytes.

---

## 14. The "no consumer for payment topics" gap

> **A documented gap in the current system.** After the refund is published to `samato.payment.refund.initiated` and `samato.payment.refunded`, **no service in the monorepo consumes them.** This is Phase 6 work. The order-service does **NOT** auto-update `Order.status` to `REFUNDED` based on the Kafka event. The customer-facing "Order is refunded" UI is therefore driven by `Order.status` on the order-service side, which only flips to `CANCELLED` (not `REFUNDED`) at the end of saga compensation.

### What this means in practice

| What is published | Who would consume it | Reality today |
|---|---|---|
| `samato.payment.refund.initiated` | (a future notification-service that emails the customer; a future analytics-service that tracks refund rates) | **No consumer.** The contract is forward-looking. |
| `samato.payment.refunded` | (same as above) | **No consumer.** |
| `samato.payment.created` | (a future analytics-service) | **No consumer.** |
| `samato.payment.charged` | (a future accounting/analytics-service) | **No consumer.** |
| `samato.payment.failed` | (a future notification-service that texts the customer; analytics) | **No consumer.** |
| `samato.payment.expired` | (a future analytics-service) | **No consumer.** |

The only `@KafkaListener` in the entire Samato monorepo is [`RestaurantEventListener` in `search-service`](../services/search-service.md#35-projectionrestauranteventlistenerjava). It subscribes to the **restaurant** topics (`samato.restaurant.created` and `samato.restaurant.updated`), not the payment topics. See [call-graph.json — `kafkaListeners`](../../docs/inventory/call-graph.json).

### Why the gap exists

The payment events are written **first** so that the rest of the system can be built **later**. The `outbox_events` table is the durable buffer; the Kafka topic is the forward-compatible contract. When the notification-service ships in Phase 6, it will subscribe to `samato.payment.refund.initiated` and email Alice "Your refund has been initiated." When the analytics-service ships in Phase 6, it will subscribe to `samato.payment.refunded` and count today's refund rate. Today, those consumers do not exist.

### What order-service does (and does not) update

Order-service updates the `orders` table during the saga:
- `Order.transitionTo(CANCELLED)` is called by `SagaEngine.compensate` when a downstream step fails after `CHARGE_PAYMENT` succeeded.
- The `cancellation_reason` is set to the failing step's error message.
- An `OrderCancelledEvent` is enqueued to `samato.order.cancelled` (also unconsumed, but at least it exists in the `outbox_events` table).

The order's `OrderStatus` enum does not have a `REFUNDED` value. The closest is `CANCELLED` (after compensation). The order's `Payment` status (a different enum on the `Payment` aggregate) is `REFUNDED` — but that lives in `payment-service`'s `payment_view`, not in the order's row.

> **For a production-grade system.** A future `@KafkaListener` in order-service would consume `samato.payment.refunded`, look up the order by `paymentId` (via `PaymentClient.findByOrderId`), and transition the order to a new `REFUNDED` state. The order-service would also need to add `REFUNDED` to the `OrderStatus` enum and handle the transition. None of this is implemented today. Document this clearly when the customer asks "why does my order still say 'cancelled' when my payment says 'refunded'?"

### Why the gap is OK to leave for now

The user's question "is my refund done?" is answerable from `GET /api/payments/{id}` (which returns `status: REFUNDED`). The order's `status: CANCELLED` is technically correct from the order-lifecycle perspective (the order was cancelled; the refund is a separate process on a separate aggregate). The two are joined via `PaymentClient.findByOrderId` if the UI needs a unified view.

---

## 15. See also

### Per-service docs

- [payment-service](../services/payment-service.md) — full walkthrough of every file, the database schema, the Avro topics.
- [order-service](../services/order-service.md) — the saga, the saga compensation path, the `PaymentClient` Feign interface.
- [shared-and-kafka](../services/shared-and-kafka.md) — the `processed_commands` dedup table, the `outbox_events` schema, the Avro class generation, the byte[] template anomaly.

### Architectural context

- [01-architecture-guide.md](../01-architecture-guide.md) — system map, request lifecycle, the "events out" table. ADRs 1, 2, 4, 5, 6, 12 are referenced throughout this use case.
- [00-glossary.md](../00-glossary.md) — every term used here: [Event Sourcing](../00-glossary.md#event-sourcing), [CQRS](../00-glossary.md#cqrs), [Transactional Outbox](../00-glossary.md#transactional-outbox), [Saga](../00-glossary.md#saga), [Idempotency](../00-glossary.md#idempotency), [Snapshotter](../00-glossary.md#event-sourcing), [Database-per-service](../00-glossary.md#database-per-service), [Avro](../00-glossary.md#avro), [SpecificRecord](../00-glossary.md#specificrecord), [Outbox poller](../00-glossary.md#outbox-poller), [At-least-once delivery](../00-glossary.md#at-least-once-delivery), [MDC](../00-glossary.md#mdc), [Correlation ID](../00-glossary.md#correlation-id), [HMAC](../00-glossary.md#hmac--hmac-sha256), [JWT](../00-glossary.md#jwt-json-web-token), [Resilience4j](../00-glossary.md#resilience4j).
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — C4 diagrams, the 13 ADRs (especially **ADR-5 Event Sourcing for payment** and **ADR-13 Razorpay is the source of truth for money**), failure semantics, bring-up summary.

### Other use cases

- [01-place-an-order.md](./01-place-an-order.md) — the saga that produced the payment Alice is now refunding. Read this first; this use case assumes it.
- [02-auth-flow.md](./02-auth-flow.md) — the JWT lifecycle that Alice's `Authorization` header rides on.
- [03-browse-and-search.md](./03-browse-and-search.md) — the read side; complements the event-sourcing "write side" covered here.

### Inventory files

- [call-graph.json — feignClients section](../../docs/inventory/call-graph.json) — confirms `PaymentClient.refund` is on the `samato-payment-service` Feign interface.
- [call-graph.json — kafkaProducers section](../../docs/inventory/call-graph.json) — confirms the six `samato.payment.*` topics and their keys.
- [shared-and-kafka.json — kafkaTopicCrossReference.topicsDeclaredInCodeButNotInSharedSchemas](../../docs/inventory/shared-and-kafka.json) — the `samato.payment.created`, `samato.payment.refund.initiated`, `samato.payment.expired` anomaly.

### Code-grounded references (absolute paths)

- Refund endpoint: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java:103-115`
- Webhook controller: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/web/WebhookController.java:49-71`
- Webhook security chain: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/security/RazorpayWebhookSecurityConfig.java:28-37`
- Webhook dispatch: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentService.java:83-143`
- Command handler (refund): `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java:153-168`
- Command handler (mark refund completed): `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java:172-179`
- Aggregate `initiateRefund`: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java:147-161`
- Aggregate `completeRefund`: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java:163-174`
- Event store append: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/eventstore/PostgresEventStore.java:55-79`
- Outbox enqueue: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java:215-228`
- Outbox poller: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/outbox/OutboxPublisher.java:45-67`
- Projector (refund branch): `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/projection/PaymentProjector.java:97-104`
- Razorpay client: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java:86-105`
- Idempotency guard: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/idempotency/IdempotencyGuard.java:46-66`
- Read model entity: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/projection/PaymentView.java`
- Time-travel endpoint: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java:84-98`
- Query service (load events): `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/query/PaymentQueryService.java:96-100`
- Snapshotter: `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/eventstore/snapshot/Snapshotter.java`
- Avro schema (refunded): `E:/Learning/ollama-projects/springboot-app/samato/shared-kafka/src/main/avro/PaymentRefundedEvent.avsc`
- Saga compensation: `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:370-390`
- PaymentClient (Feign): `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/PaymentClient.java`
- Order controller (cancel documented): `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/web/OrderController.java` (javadoc line 29)
