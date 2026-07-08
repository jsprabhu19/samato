## Status (2026-07-08)

✅ **Verified running on this machine.** The service image is built by `docker compose build` from the local jar, the container is `Up (healthy)`, `/actuator/health` returns **HTTP 200** with `{"status":"UP"}`, and the service is registered in **Eureka** as `SAMATO-ORDER-SERVICE`.

- **Port:** 8083
- **Image:** samato-order-service:dev (compose tags it `order-service:latest`)
- **Health:** `curl http://localhost:8083/actuator/health` → `{"status":"UP", ...}`
- **Bring-up bug fixes in this service**: removed `@Lob` from `IdempotencyRecord`, `OutboxEvent`, `SagaStep` (replaced with `columnDefinition` for TEXT/BYTEA); fixed `OrderCancelledEvent` schema (no `restaurantId`); fixed `OrderPlacedEvent` to use `doubleValue()` for `totalAmount` (Avro `double`); added `KafkaByteArrayConfig` so the outbox can publish raw bytes.

---

# Order Service — Interview Notes

> *"Place an order, charge the customer, and tell everyone about it — without ever getting stuck mid-transaction."*

The order-service is where the microservice story **comes together**: a single API call drives a saga across multiple services, persisted in a database, with retries, compensations, and outbox events. This document is a tour of the design decisions and the concepts they illustrate.

---

## 1. What this service does

- Accepts `POST /api/orders` from a customer.
- Validates the order (restaurant is open, items are on the menu, prices match the server's).
- Reserves inventory (Phase 4: stub).
- Charges the customer (Phase 4: stub).
- Transitions the order to `CONFIRMED` and emits `OrderConfirmedEvent` to Kafka.
- On any failure, walks the saga **backwards**, undoing each completed step.

---

## 2. Saga pattern (orchestration, not choreography)

We use **orchestration** — a central `SagaEngine` knows the entire workflow and calls participants directly. The alternative is **choreography**, where each service emits events and reacts to events from others. Both are valid; the trade-off is:

| | Orchestration | Choreography |
|---|---|---|
| Workflow visibility | Centralized, easy to read | Distributed, harder to follow |
| Coupling | Orchestrator knows participants | Each service only knows events |
| Failure handling | Centralized compensation logic | Each service handles its own |
| Best for | Complex multi-step flows | Simple, decoupled flows |

For an order with five tightly-coupled steps and explicit compensation needs, orchestration is the right call.

### Why persist the saga?

```java
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
    return sagaRepository.save(saga);
}
```

If we kept the saga in memory, a process crash would lose the workflow state. By persisting it, we can:
- **Resume on restart** — the poller (`SagaPoller`) picks up `RUNNING` sagas and drives them.
- **Audit** — every order attempt has a complete step history.
- **Re-attach after a worker death** — a `RUNNING` saga with no in-process owner is a known state we can recover from.

The trade-off is **database writes per step**. That's fine for orders (low volume, high value). For high-volume events, in-memory state machines with Kafka checkpoints are better.

### REQUIRES_NEW for step transactions

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void runStep(SagaInstance saga, SagaStep step) {
    step.markRunning();
    sagaRepository.save(saga);
    ...
    step.markCompleted(payload);
    sagaRepository.save(saga);
}
```

Each step commits independently. Why? Because if `CHARGE_PAYMENT` succeeds and `CONFIRM_ORDER` fails, we need a record of the charge so we can refund it. If they shared a transaction, the failure would roll back the charge — and we wouldn't know to refund anything.

### Compensation in reverse

```java
List<SagaStep> completed = new ArrayList<>();
for (SagaStep s : fresh.getSteps()) {
    if (s.getStatus() == SagaStepStatus.COMPLETED) completed.add(s);
}
Collections.reverse(completed);
for (SagaStep step : completed) {
    compensateStep(fresh, step);
}
```

This is the core insight of the saga pattern: **forward actions are paired with compensations**. We run them in reverse order, undoing the most recent step first, to mirror LIFO semantics. If `RESERVE_INVENTORY` succeeds and `CHARGE_PAYMENT` fails:
1. Refund the (stub) charge.
2. Release the (stub) inventory.

If a compensation itself fails, we mark the saga `FAILED` and stop. Human intervention is required past that point — silent retries would risk double-compensation.

### Interview question: "What happens if the orchestrator crashes mid-saga?"

- **Best case**: the poller picks it up. Steps already `COMPLETED` are skipped (idempotency). The first `PENDING` step is re-executed.
- **Worst case**: a step ran externally (e.g., the inventory service reserved stock) but failed to commit the saga row. On retry, we'd reserve **twice**. The mitigation is **idempotency keys** on the external calls (the inventory step would send a deterministic `Idempotency-Key` derived from the saga's `stepId`).

---

## 3. State machines — Order and Saga are separate

The `Order` has its own state machine (`PLACED → VALIDATED → RESERVED → PAID → CONFIRMED`). The `SagaInstance` has a separate one (`RUNNING → COMPLETED | COMPENSATING → FAILED`). Why?

- The **order** state is the business view ("Is this order done?").
- The **saga** state is the orchestration view ("Are we done driving the workflow?").

In normal operation they line up: `CONFIRMED` order ↔ `COMPLETED` saga. But during compensation, the saga is in `COMPENSATING` while the order is in `RESERVED` (we've started the refund, the order is still technically reserved until the refund completes). Mixing them would be confusing.

### Why `transitionTo` instead of `setStatus`?

```java
public void transitionTo(OrderStatus next) {
    if (!isLegalTransition(this.status, next)) {
        throw new IllegalStateException(
                "Illegal order status transition: " + this.status + " -> " + next);
    }
    this.status = next;
}
```

Because illegal transitions are a class of bug you want to **fail loudly**. If the order is in `CANCELLED` and someone tries to set it to `CONFIRMED`, that should be a runtime exception, not a silent corruption. The state machine is the contract — let's enforce it.

---

## 4. Transactional outbox

```java
@Transactional
public PlaceOrderResult placeOrder(...) {
    Order saved = orderRepository.save(order);
    outbox.appendOrderPlaced(saved);   // same transaction!
    sagaEngine.start(saved.getId());
    sagaEngine.drive(saga.getId());
    ...
}
```

The classic two-phase problem: you save to PostgreSQL, then publish to Kafka. If the app crashes between those two operations, the order exists but no one knows. The outbox pattern fixes this by writing to a `outbox_events` table **in the same transaction** as the business write. A poller reads the table and publishes to Kafka.

The poller is at-least-once (a crash between publish and `UPDATE outbox SET sent_at = NOW()` causes a duplicate send). Downstream services must be **idempotent** — the search service uses `update` semantics so a duplicate event is harmless.

### Polling vs. CDC

Polling is what we have in Phase 4 — it's simple and works. The next step is **Debezium CDC**: it reads the PostgreSQL WAL and pushes outbox events to Kafka in real time. Same guarantees, lower latency, no polling overhead.

---

## 5. Idempotency

```java
if (idempotencyKey != null && !idempotencyKey.isBlank()) {
    Optional<IdempotencyRecord> existing =
        idempotencyRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
    if (existing.isPresent()) {
        IdempotencyRecord rec = existing.get();
        String requestHash = hash(request);
        if (!rec.getRequestHash().equals(requestHash)) {
            throw new DomainException("IDEMPOTENCY_KEY_REUSED", ..., 422);
        }
        // Replay
        return new PlaceOrderResult(original, true);
    }
}
```

Clients retry — networks are flaky, timeouts happen, users double-tap. The `Idempotency-Key` header lets us recognize a retry and return the original response.

The contract is:
- The **same** key + **same** body → return the original order (replay).
- The **same** key + **different** body → `422 Unprocessable Entity` (the key was reused; this is a client bug).
- The **same** key, no body change, but the original is gone → 404 (which is a stale state, very rare).

Per-customer scoping prevents a collision between two users who happen to pick the same key. The `requestHash` catches body-mismatch misuse.

### What about concurrent requests with the same key?

The unique constraint on `idempotency_key` (per customer) means one transaction wins the insert and the other gets a `DataIntegrityViolationException`. We could:
- Catch it, wait for the first to commit, then read the row. (Cleanest.)
- Or, treat it as "request still in progress" and return 409.

Phase 4 doesn't optimize for the race — the client will get a 500 (or replay) but will be safe on retry. Phase 8 would add the "wait-and-replay" logic.

---

## 6. The Order's relationship to price

Look at this carefully:

```java
// In OrderService — we do NOT set the price
OrderItem oi = new OrderItem();
oi.setMenuItemId(item.menuItemId());
oi.setQuantity(item.quantity());
order.addItem(oi);
```

And in the saga:

```java
// In SagaEngine.VALIDATE_ITEMS — server-side pricing wins
ordered.setName(menuItem.name());
ordered.setUnitPrice(menuItem.price());
```

The client posts `{ menuItemId, quantity }` — never a price. The server pulls the price from `restaurant-service` and sets it on the order. **The client cannot fake the total.**

This is a small thing but it's a frequent interview question: "How do you prevent price manipulation?" Answer: don't trust the client's prices, ever. They are display hints, nothing more.

---

## 7. Service-to-service auth

```java
public interface RestaurantClient {
    @GetMapping("/api/restaurants/{id}")
    RestaurantDtos.RestaurantSummary getRestaurant(@PathVariable("id") UUID id);
}
```

The Feign client doesn't carry credentials — instead, the `FeignAuthForwarder` (a `RequestInterceptor`) injects the **inbound** bearer token into outbound calls. This is the "user token passthrough" pattern. It's pragmatic but not ideal:

- **Pro**: no separate service-to-service credentials.
- **Con**: the restaurant service sees the customer's token, which has more authority than it needs. A bug in restaurant-service could leak data.
- **Better (Phase 5+)**: fetch a dedicated `client_credentials` token for order-service and use that. The customer's user-id can be passed as a separate header (e.g., `X-On-Behalf-Of`).

---

## 8. Circuit breakers (Resilience4j)

In production, `RestaurantClient` would be wrapped with `@CircuitBreaker` and `@Retry`:

```java
@CircuitBreaker(name = "restaurant-client", fallbackMethod = "fallback")
@Retry(name = "restaurant-client")
public RestaurantSummary getRestaurant(UUID id) { ... }
```

In Phase 4, the breaker is **configured but not annotated** because the saga has its own retry semantics (the poller re-drives). Adding the breaker on top of the saga poller would cause **double retries** — a recipe for thundering herd. The lesson: breakers and saga retries serve different purposes and shouldn't both be on the same call.

---

## 9. Database schema (highlights)

| Table | Purpose |
|---|---|
| `orders` | The order aggregate, with `version` for optimistic locking. |
| `order_items` | Owned collection; deleted on order removal. |
| `saga_instances` | One per order. Tracks status + reason for failure. |
| `saga_steps` | Owned by saga. Each step has `payload` for compensation. |
| `idempotency_records` | Unique on `idempotency_key`. Hashes the request body. |
| `outbox_events` | Append-only log of unpublished events; poller drains it. |

### Optimistic locking on `Order`

```java
@Version
private long version;
```

When two clients update the same order concurrently, the second commit fails with `OptimisticLockException`. We catch it (or let it bubble) — the response is a 409 and the client can re-fetch and retry. The saga avoids the conflict by using `@Transactional(propagation = REQUIRES_NEW)` per step, so only one writer is active per saga at a time.

---

## 10. Interview Q&A

**Q: Why orchestration over choreography for orders?**

A: Orders have explicit compensation steps (refund, release inventory) and a fixed happy path. With choreography, you'd have N services all listening for partial-failure events, each writing its own compensation. That's a maintenance nightmare. With orchestration, the workflow is in one place.

**Q: What if the saga is stuck in RUNNING forever?**

A: Two scenarios:
1. **Step is silently failing** (e.g., Kafka is down and the poller is erroring out). The fix is monitoring — alert on sagas in `RUNNING` for > N minutes.
2. **Step ran but the saga didn't update** (e.g., crashed between commit and `markCompleted`). The poller will retry. The step must be **idempotent** — sending the same request twice must produce the same outcome.

**Q: How do you prevent the customer from being charged twice?**

A: The `CHARGE_PAYMENT` step uses an idempotency key derived from `sagaId + stepId`. The payment service (in Phase 5) will dedupe on it. The order-service never re-runs a `COMPLETED` step.

**Q: What about cross-service transactions? You can't use 2PC across HTTP.**

A: Correct — you can't. The saga is the answer. Each step is a local transaction in its own service. The saga table in order-service records which local transactions have committed. If we need to undo, we issue compensating transactions. This is the "BASE" model (Basically Available, Soft state, Eventual consistency) vs. ACID.

**Q: How would you test this?**

A: Three layers:
1. **Unit**: mock `RestaurantClient` and test the saga's happy path and each failure branch.
2. **Integration** (Testcontainers): real Postgres + real restaurant-service (via `@SpringBootTest` and `WireMock`). Drive a saga, then assert the order's final state.
3. **Contract** (Pact): the `RestaurantClient` defines the order-service's expectations of restaurant-service. The restaurant-service team publishes a contract verification job. If they change the API, the contract test fails in CI before deploy.

**Q: What happens if the order-service restarts during compensation?**

A: The saga is in `COMPENSATING` state in the DB. The poller sees this and resumes. The last `COMPLETED` step (and any not-yet-`COMPENSATED` ones above it) get their compensations re-run. **All compensations must be idempotent** for this to be safe — refunding the same payment twice is bad. The `paymentId` stored in the step's `payload` is the dedup key.

---

## 11. What's missing — and how Phase 5+ fills it in

- **Payment-service integration**: the `CHARGE_PAYMENT` step is a stub. Phase 5 will replace it with a real call to a dedicated payment service, with proper idempotency.
- **Inventory-service integration**: same — `RESERVE_INVENTORY` is a stub.
- **Saga visualization**: the `/api/orders/{id}/saga` endpoint already returns the steps in a readable format. Phase 8 will add a UI.
- **DLQ for outbox events**: if the Kafka send fails repeatedly, the row stays in `outbox_events` forever. Phase 8 will add a `retry_count` and a separate DLQ table.
- **Compensation failures** mark the saga `FAILED` but don't escalate. Phase 7 will add a notification to ops via Kafka.

---

## 12. Phase 5 — real payment integration

The `CHARGE_PAYMENT` step used to be a stub. It now calls `payment-service`, which integrates Razorpay in test mode.

### What changed in the saga

```java
private String chargePayment(Order order) {
    UUID stepId = UUID.randomUUID();
    PaymentDtos.PaymentResponse resp = paymentClient.createOrder(
        stepId.toString(),  // Idempotency-Key
        new PaymentDtos.CreatePaymentRequest(
            order.getId(), order.getCustomerId(),
            order.getTotalAmount(), order.getCurrency()
        )
    );
    return writeJson(Map.of(
        "paymentId",       resp.paymentId().toString(),
        "razorpayOrderId", resp.razorpayOrderId(),
        "amount",          order.getTotalAmount().toPlainString(),
        "currency",        order.getCurrency()
    ));
}
```

The `Idempotency-Key` header is the saga step id. Payment-service persists `(command_type, key)` in its `processed_commands` table, so a saga retry replays the prior response — no double-charge.

### What changed in compensation

```java
case CHARGE_PAYMENT -> {
    UUID paymentId = readPaymentIdFromPayload(step.getPayload());
    paymentClient.refund(
        paymentId,
        UUID.randomUUID().toString(),     // Idempotency-Key for the refund
        new PaymentDtos.RefundRequest(order.getTotalAmount())
    );
}
```

If a later step (CONFIRM_ORDER, or the inventory service in Phase 6) fails, the saga walks back and refunds. The customer's money is made whole.

### Why this is the right shape

- **We don't store money in our DB.** Razorpay is the source of truth. Our event store is a *reconciliation* ledger.
- **Two layers of idempotency.** HTTP `Idempotency-Key` (saga's safety net) + Razorpay's own `receipt` dedup (customer's safety net).
- **Webhooks do the "captured" transition.** The saga's `chargePayment` only creates the Razorpay order. The actual `payment.captured` event comes from Razorpay, async, via webhook. See `services/payment-service/docs/INTERVIEW-NOTES.md` for the full story.

### What's still TODO

- **`Order.PAID` transition.** The saga still goes `RESERVED → CONFIRMED` directly. We can wire a Kafka listener for `samato.payment.charged` and transition to `PAID` → `CONFIRMED`. This is a Phase 6 task.
- **Inventory-service.** The `RESERVE_INVENTORY` step is still a stub. Phase 6 will replace it.

---

*See also: `docs/INTERVIEW-CHEATSHEET.md` for cross-cutting microservice patterns, and the [Saga Pattern](https://microservices.io/patterns/data/saga.html) deep-dive.*
