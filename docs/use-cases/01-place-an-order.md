# Use case: Place an order

> An end-to-end trace of what happens when a customer taps the **Place Order** button in the Samato mobile app. This document follows one order from the user's HTTPS request through the API gateway, into order-service, across Feign to restaurant-service and payment-service, and out to Kafka. It is written for someone who has read [00-glossary.md](../00-glossary.md) and [01-architecture-guide.md](../01-architecture-guide.md) and wants to see the moving parts in one continuous narrative.

> **Audience**: a beginner with Spring Boot who wants to see how a real microservice app wires up the patterns described in isolation in the other docs.
>
> **Conventions**:
> - Every **clickable link** in this doc is a relative markdown link (e.g. `[auth-service.md](../services/auth-service.md)`).
> - **Source-file citations** (in backticks, e.g. `` `services/order-service/.../SagaEngine.java:104` ``) are shown as repo-relative paths so you can copy-paste them into an editor. They are display text, not clickable links.
> - Every term that has its own entry in the glossary is a relative markdown link to [00-glossary.md](../00-glossary.md).
> - `▶` marks the file or method that is currently running; `↳` marks a return value or follow-on effect.
> - Source-line citations use the form `path/to/File.java:NN` to point at a specific line. The line numbers are correct as of this writing but may drift slightly as the repo evolves.

---

## 1. The story

Ravi, a hungry customer, opens the Samato app on his phone. He has already logged in (his JWT is in the app's secure store). He browses restaurants, taps one, picks two items, taps **Place Order**. The phone sends a single `POST /api/orders` with a JSON body describing the restaurant and the items, plus an `Idempotency-Key` header so a retried submit doesn't double-charge his card.

What happens next is a small distributed transaction that crosses three services:

1. **api-gateway** receives the request, validates the JWT, sets a few headers, and routes the request to order-service.
2. **order-service** persists the order, appends an event to its outbox, starts a [saga](../00-glossary.md#saga), and then drives the saga synchronously. The saga has five steps — validate the restaurant, validate the items, reserve inventory, charge payment, confirm order — and each step is its own database transaction. Steps 1, 2, and 4 cross to other services via [Feign](../00-glossary.md#feignclient).
3. **restaurant-service** answers the two read-only questions the saga asks (does the restaurant exist? what's the price of each menu item?).
4. **payment-service** creates a Razorpay order so Ravi can complete payment (in test mode, the saga accepts the Razorpay `ORDER_CREATED` as the success signal — there is no separate "PAID" event wired up yet; see [§5](#5-what-can-go-wrong-failure-modes)).
5. The saga writes its final `samato.order.confirmed` event into the outbox; the [OutboxPublisher](../00-glossary.md#transactional-outbox) poller picks it up and ships it to Kafka within 500ms.

The customer sees a `201 Created` response with the order body. Behind the scenes, five more SQL rows were written, three rows were sent to Kafka, and two downstream service-to-service HTTP calls were made — all in roughly 1-2 seconds.

---

## 2. Prerequisites

To follow this walkthrough you should know:

- What a [JWT](../00-glossary.md#jwt) is, and that the gateway validates it. Full lifecycle in [02-how-auth-works.md](../02-how-auth-works.md).
- What a [saga](../00-glossary.md#saga) is in the orchestrator flavor, and what [compensation](../00-glossary.md#compensation) means when something goes wrong.
- What the [transactional outbox](../00-glossary.md#transactional-outbox) pattern is and why we need it.
- What [Feign](../00-glossary.md#feignclient) is, and that it generates the HTTP client at runtime from a Java interface.
- What an [Idempotency-Key](../00-glossary.md#idempotency-key) is for and how a replay is detected.
- The data flow at the system level ([01-architecture-guide.md §3](../01-architecture-guide.md)).

You do **not** need to know Razorpay's API, Avro in detail, or Spring Security internals to follow this trace.

---

## 3. The call chain (whole flow at a glance)

The numbered arrows map directly to the steps in [§4](#4-step-by-step-file-trace). Every step is annotated with the service it lives in.

```
                                                                                 ┌────────────────────────────┐
                                                                                 │  discovery-service (8761)  │
                                                                                 │  (Eureka)                  │
                                                                                 └─────────────┬──────────────┘
                                                                                               │ registers
                                                                                               │
 Customer   api-gateway     order-service        restaurant-service    payment-service      Kafka
 (phone)        │                │                       │                   │                 │
   │            │                │                       │                   │                 │
   │ ① POST /api/orders  + Bearer JWT  + Idempotency-Key  │                   │                 │
   ├───────────▶                 │                       │                   │                 │
   │            │ ② JWT verify,  │                       │                   │                 │
   │            │   X-User-Id    │                       │                   │                 │
   │            │   X-Correlation-Id                      │                   │                 │
   │            │   BearerTokenCaptureFilter              │                   │                 │
   │            ├───────────────▶                        │                   │                 │
   │            │                │ ③ OrderController     │                   │                 │
   │            │                │   @PreAuthorize       │                   │                 │
   │            │                │   @AuthenticationPrincipal Jwt           │                 │
   │            │                │                       │                   │                 │
   │            │                │ ④ OrderService.placeOrder               │                 │
   │            │                │   4a. idempotency-replay short-circuit   │                 │
   │            │                │   4b. persistAndStartSaga @Transactional │                 │
   │            │                │       └─ order row        INSERT         │                 │
   │            │                │       └─ outbox row       INSERT         │                 │
   │            │                │       └─ saga row         INSERT         │                 │
   │            │                │       └─ saga_steps       INSERT (x5)     │                 │
   │            │                │       └─ idempotency_row  INSERT         │                 │
   │            │                │                       │                   │                 │
   │            │                │ ⑤ SagaEngine.drive    │                   │                 │
   │            │                │   └─ runStep (REQUIRES_NEW)              │                 │
   │            │                │      ⑤-A  validateRestaurant               │                 │
   │            │                │      Bearer JWT + X-Correlation-Id        │                 │
   │            │                ├─────── ⑤-A.1 GET /api/restaurants/{id} ───▶                  │
   │            │                │                       │                   │                 │
   │            │                │      ⑤-B  validateItems (also REQUIRES_NEW)               │
   │            │                │      For each menuItemId, trust server price                │
   │            │                ├─────── ⑤-B.1 GET /api/restaurants/{id}/menu?ids=... ────────▶│
   │            │                │                       │                   │                 │
   │            │                │      ⑤-C  reserveInventory (stub)         │                 │
   │            │                │                       │                   │                 │
   │            │                │      ⑤-D  chargePayment                   │                 │
   │            │                │      Idempotency-Key = saga stepId        │                 │
   │            │                ├─────── ⑤-D.1 POST /api/payments/orders ──────────────────────▶│
   │            │                │                       │            PaymentController.createOrder
   │            │                │                       │            ├ IdempotencyGuard.executeOnce
   │            │                │                       │            ├ PaymentCommandHandler @Transactional
   │            │                │                       │            │   └ RazorpayClientImpl.createOrder
   │            │                │                       │            │       └ Razorpay API (test mode)
   │            │                │                       │            │   └ payment.createOrder → ORDER_CREATED
   │            │                │                       │            │   └ append "Payment" outbox row (samato.payment.created)
   │            │                │                       │            │   └ save ProcessedCommand
   │            │                │                       │            ◀──────────────────────────│ PaymentResponse
   │            │                │ ◀──────────────────── ⑤-D.2 PaymentResponse
   │            │                │      ⑤-E  confirmOrder  (REQUIRES_NEW)
   │            │                │       └─ order.transitionTo(CONFIRMED)
   │            │                │       └─ outbox.appendOrderConfirmed  → INSERT
   │            │                │                       │                   │                 │
   │            │                │ ⑥ Saga COMPLETED      │                   │                 │
   │            │                │   OrderResponse 201   │                   │                 │
   │            │ ◀──────────────┤                       │                   │                 │
   │            │                │                       │                   │                 │
   │            │                │ ⑦ @Scheduled poller (every 500ms)        │                 │
   │            │                │    publishPending()    │                   │                 │
   │            │                │    for each unsent outbox row:           │                 │
   │            │                │    └─ kafka.send(new ProducerRecord(...))│                 │
   │            │                │    └─ UPDATE outbox SET sent_at=now()    │                 │
   │            │                ├────────────────────────────── produce ──────────────────────▶ samato.order.placed
   │            │                ├────────────────────────────── produce ──────────────────────▶ samato.order.confirmed
   │            │                │                       │                   │                 │
   │            │                │                       │ ⑧ (saga never crosses back — payment outbox ships samato.payment.created)  │
   │            │                │                       │                   └─ produce ────────▶ samato.payment.created
   │            │                │                       │                   │                 │
   │            │                │                       │                   │                 │
   │ ◀──── 201 Created + JSON body ──────────────────────┘                   │                 │
   │            │                │                       │                   │                 │
```

(Step ① is the request on the wire; steps ②-⑥ are the synchronous part of the call chain; step ⑦ is the first async hop — the order-service outbox poller — that happens within 500ms of the response; step ⑧ is the second async hop — the payment-service outbox poller, also 500ms cadence.)

---

## 4. Step-by-step file trace

For each step below, the headings are: **What happens**, **Files involved**, **Code (the load-bearing lines)**.

### ① The phone sends the request

**What happens.** The mobile app issues a single HTTPS request:

```
POST https://api.samato.local/api/orders
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIs...   (the customer's JWT)
Idempotency-Key: 5e9c0a4f-7c8e-4d0c-9d3b-2c8e3aaf6a91         (a UUID the app generated)
Content-Type: application/json

{
  "restaurantId": "8c1d4d3a-3d0e-4a4f-9b3c-2a0e7e1c1c1c",
  "items": [
    { "menuItemId": "f4d2a1c0-...-...", "quantity": 2 },
    { "menuItemId": "e5b1a2c1-...-...", "quantity": 1 }
  ],
  "currency": "INR",
  "notes": "extra spicy, no onions"
}
```

**Files involved.** None yet — this is the wire. The JWT and the Idempotency-Key are described in [02-how-auth-works.md](../02-how-auth-works.md) and [01-architecture-guide.md §4](../01-architecture-guide.md).

**Anomalies to keep in mind.**
- The body does **not** include prices. The saga will pull the server's prices from restaurant-service, not trust the client. This is the **server-side pricing** rule — see [services/order-service.md §5](../services/order-service.md).
- The body does not include `customerId`. The customer's id is the JWT's `sub` claim; the gateway forwards it as `X-User-Id` and the order-service controller parses the JWT again to be sure ([security in depth](#anomaly-1-security-is-defense-in-depth-not-single-layer)).

---

### ② The gateway validates the JWT and forwards the request

**What happens.** The request lands on api-gateway:8080. Spring Cloud Gateway runs its filter chain:

1. `JwtAuthFilter` (custom) calls auth-service's JWKS endpoint, verifies the RS256 signature, checks `iss`, `aud`, `exp`, and the `roles` claim. If anything is wrong, the gateway returns `401` and the order is never placed.
2. The filter sets a few headers on the downstream request: `X-User-Id` (= JWT subject), `X-User-Email`, `X-User-Roles`, `X-Correlation-Id` (a fresh UUID if the client didn't send one).
3. `GatewayRoutesConfig` matches `/api/orders/**` to the order-service route, and the gateway uses Eureka to look up an instance.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/api-gateway/src/main/java/com/samato/gateway/security/JwtAuthFilter.java` — the validator.
- `E:/Learning/ollama-projects/springboot-app/samato/services/api-gateway/src/main/java/com/samato/gateway/config/GatewayRoutesConfig.java` — the route table.
- `E:/Learning/ollama-projects/springboot-app/samato/docs/services/api-gateway.md` — the per-service write-up.

**Anomaly 1: security is defense in depth, not single layer.**
The gateway's validation is *not* the only check. The order-service controller will re-parse the JWT (different filter chain, different `JwtDecoder` bean) and the `[OrderController.place]` method is annotated `[PreAuthorize("hasRole('CUSTOMER')")]`. So if the gateway were ever misconfigured, the inner service still rejects the call. This is by design — see `BearerTokenCaptureFilter` in [SecurityConfig.java](#files-involved-2).

---

### ③ The order-service security filter chain re-validates the JWT

**What happens.** The request arrives at order-service. Before the controller is even considered, two things happen:

1. Spring Security's filter chain validates the JWT a second time. If it's bad or missing, the request is `401` before reaching the controller.
2. A custom `BearerTokenCaptureFilter` (added to the chain via `addFilterAfter`) reads the `Authorization` header and stuffs the bearer into a `ThreadLocal` named `CURRENT_TOKEN`. This is what makes downstream Feign calls able to forward the same token. The `try / finally` block clears the `ThreadLocal` when the request finishes.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/security/SecurityConfig.java:50-66` — the `filterChain` bean.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/security/SecurityConfig.java:104-120` — `BearerTokenCaptureFilter` (inner class).
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/FeignAuthForwarder.java:29, 42-44` — the `ThreadLocal` is `FeignAuthForwarder.CURRENT_TOKEN`; `set`/`clear`/`current` are the accessors.

**Code (load-bearing lines).**

```java
// SecurityConfig.java
http.addFilterAfter(new BearerTokenCaptureFilter(),
        UsernamePasswordAuthenticationFilter.class);

// SecurityConfig.java (inner class)
String auth = request.getHeader("Authorization");
if (auth != null && auth.startsWith("Bearer ")) {
    FeignAuthForwarder.set(auth.substring("Bearer ".length()));
}
try { chain.doFilter(request, response); }
finally { FeignAuthForwarder.clear(); }
```

**Anomaly 2: the `ThreadLocal` is a known shortcut, not the right long-term shape.**
The Javadoc on `FeignAuthForwarder` says it directly: *"In Phase 4 we trust the inbound token (gateway-validated). Phase 5+ should add proper service tokens so the same JWT isn't replayed downstream — for now this is a pragmatic shortcut."* The full text is in [FeignAuthForwarder.java:22-25](#files-involved). For an interview, mention this as a deliberate design decision and the migration path (client-credentials grant, `ServiceTokenProvider` fallback for background threads).

---

### ④ The controller dispatches to `OrderService`

**What happens.** Spring resolves the handler:

```java
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
```

Three things happen in this method body, in order:

1. `@PreAuthorize` is checked — the JWT's `roles` claim must contain `CUSTOMER`. (The role-to-authority conversion is in `SecurityConfig#jwtAuthConverter`.)
2. `@Valid` triggers bean validation on the request body — `@NotNull restaurantId`, `@NotEmpty List<@Valid OrderItemRequest>`, `@Size(max=500) notes`. If anything is missing, the request is `400` before reaching the service.
3. The controller calls `orderService.placeOrder(customerId, idempotencyKey, request)`.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/web/OrderController.java:48-58` — the `place` handler.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/OrderDtos.java` — the request DTO with the `@NotNull`/`@NotEmpty`/`@Valid` annotations.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/security/SecurityConfig.java:81-98` — the role-mapping converter.

**Code (load-bearing lines).**

```java
// OrderController.java:48-58
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
```

**Why `customerId` is the JWT subject, not a request field.**
If `customerId` were a request field, a malicious customer could place an order on someone else's tab. The JWT is the trust anchor; the controller treats `sub` as the source of truth, and the gateway's `X-User-Id` is a hint that we double-check against the JWT.

---

### ⑤ `OrderService.placeOrder` runs the idempotency check and persists

**What happens.** `placeOrder` is the orchestrator of the synchronous part. It is **not** `@Transactional` — it deliberately spans two transactions (the persist step, then the saga drive) so the saga's read transactions can see the freshly committed rows.

```java
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
                throw new DomainException("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key reused with a different request body", 422);
            }
            Order original = orderRepository.findById(rec.getOrderId())
                    .orElseThrow(() -> new IllegalStateException("..."));
            return new PlaceOrderResult(original, true);   // replayed = true
        }
    }

    // 2. Persist the order, outbox event, and saga bootstrap in ONE transaction.
    UUID sagaId = persistAndStartSaga(customerId, idempotencyKey, request);

    // 3. Drive the saga in a fresh transaction context.
    try { sagaEngine.drive(sagaId); }
    catch (Exception ex) { log.warn(...); }
    Order finalOrder = orderRepository.findById(findOrderIdBySagaId(sagaId)).orElseThrow();
    return new PlaceOrderResult(finalOrder, false);
}
```

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/service/OrderService.java:69-110` — `placeOrder`.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/service/OrderService.java:118-154` — `persistAndStartSaga` (the `@Transactional` boundary).
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/domain/IdempotencyRepository.java` — the lookup query.

**Code (load-bearing lines).**

```java
// OrderService.java:118-154
@Transactional
public UUID persistAndStartSaga(UUID customerId, String idempotencyKey, OrderDtos.PlaceOrderRequest request) {
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
```

**Anomaly 3: `placeOrder` is not `@Transactional`, but `persistAndStartSaga` is.**
The reason is in the comment: *"we need to commit the order BEFORE driving the saga, otherwise the saga's `sagaRepository.findById(...)` can't see the row in a fresh transaction."* If `placeOrder` were `@Transactional`, the saga would see a stale read of its own `SagaInstance`. The two-method split (outer non-transactional, inner transactional) is the fix.

**Anomaly 4: the hash uses SHA-256 of the JSON-serialized request.**
The hash is computed by `hash(request)`:

```java
// OrderService.java:187-198
private String hash(Object o) {
    String json = objectMapper.writeValueAsString(o);
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
}
```

Why hash the body? To detect "the same key, but a different body" — that's the "key reused" `422`. The hash doesn't need to be cryptographically perfect (no secrets here); it needs to be deterministic across attempts, which Jackson's `writeValueAsString` is for a given version.

---

### ⑥ The saga engine boots and starts driving

**What happens.** The `SagaEngine.start` method creates the `SagaInstance` and five `SagaStep` rows, all in `PENDING` state. The order is then driven in steps ⑦-⑪ below.

```java
// SagaEngine.java:100-123
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
    orderRepository.findById(orderId).ifPresent(o -> {
        o.setSagaId(saved.getId());
        orderRepository.save(o);
    });
    return saved;
}
```

`WORKFLOW` is the canonical five-step list:

```java
// SagaEngine.java:55-61
private static final List<SagaStepType> WORKFLOW = List.of(
        SagaStepType.VALIDATE_RESTAURANT,  // 0
        SagaStepType.VALIDATE_ITEMS,       // 1
        SagaStepType.RESERVE_INVENTORY,    // 2
        SagaStepType.CHARGE_PAYMENT,       // 3
        SagaStepType.CONFIRM_ORDER         // 4
);
```

After `start` returns, `OrderService.placeOrder` calls `sagaEngine.drive(sagaId)`.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:55-61` — the `WORKFLOW` constant.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:100-123` — `start`.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:129-163` — `drive`.

**Anomaly 5: the saga engine self-injects via `@Lazy SagaEngine self`.**
The constructor takes a `SagaEngine self` field, and the engine calls `self.runStep(...)` instead of `this.runStep(...)` from inside `drive`. The reason is in the Javadoc: *"A plain `this.method()` call bypasses the proxy and the annotation is ignored."* This is the standard Spring `@Transactional` self-invocation gotcha — calling a `@Transactional` method on `this` skips the proxy and the transaction is never opened. The `@Lazy` breaks the otherwise-circular constructor dependency. Same pattern shows up in `compensate` → `self.compensateStep(...)`.

---

### ⑦ Step A: `VALIDATE_RESTAURANT` — first Feign call

**What happens.** Inside `drive`, the engine picks the first `PENDING` step and calls `runStep`, which in turn calls `dispatch` → `validateRestaurant`:

```java
// SagaEngine.java:200-216
private String validateRestaurant(Order order) {
    RestaurantDtos.RestaurantSummary restaurant =
            restaurantClient.getRestaurant(order.getRestaurantId());
    if (restaurant == null) {
        throw new IllegalStateException("Restaurant not found: " + order.getRestaurantId());
    }
    if (!restaurant.active()) {
        throw new IllegalStateException("Restaurant is closed: " + restaurant.name());
    }
    return writeJson(Map.of(
            "restaurantId", restaurant.id().toString(),
            "name", restaurant.name()
    ));
}
```

**What crosses the wire.** The Feign call is:

```
GET http://samato-restaurant-service/api/restaurants/8c1d4d3a-...
Authorization: Bearer eyJ...     (forwarded by FeignAuthForwarder)
X-Correlation-Id: ...            (propagated by FeignCorrelationIdInterceptor)
```

The `RestaurantClient` interface in order-service declares the call; the URL `samato-restaurant-service` is resolved by Eureka.

```java
// RestaurantClient.java:30-31
@GetMapping("/api/restaurants/{id}")
RestaurantDtos.RestaurantSummary getRestaurant(@PathVariable("id") UUID id);
```

**What restaurant-service does.**

1. Spring Security on restaurant-service validates the bearer again (it has its own `SecurityConfig`).
2. The `RestaurantController.get` handler runs (`@PreAuthorize("isAuthenticated()")`).
3. The service method is `@Cacheable` on a `restaurants` Caffeine cache — first call is a DB read, subsequent calls are cache hits.
4. The response is a JSON `RestaurantSummary` record containing `id`, `name`, `active`, etc. (the saga only needs the `id` and `active` fields; the menu comes in step B).

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:200-216` — `validateRestaurant`.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/RestaurantClient.java:30-31` — the Feign interface method.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/RestaurantDtos.java` — the response DTO.
- `E:/Learning/ollama-projects/springboot-app/samato/services/restaurant-service/src/main/java/com/samato/restaurantservice/web/RestaurantController.java:46-50` — the handler.
- `E:/Learning/ollama-projects/springboot-app/samato/services/restaurant-service/src/main/java/com/samato/restaurantservice/service/RestaurantService.java:57-62` — `@Cacheable` read.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/FeignAuthForwarder.java:46-58` — the bearer interceptor.

**Anomaly 6: the `@PreAuthorize` uses `isAuthenticated()` for browse endpoints but `hasRole(...)` for writes.**
This is the same pattern in all services — see the per-service `SecurityConfig` files. The browse endpoints only need the JWT to be valid; the write endpoints need a specific role. The `RestaurantService.java` Javadoc on `@PreAuthorize` is a one-liner: *"the customer is taken from the JWT subject."*

**Anomaly 7: there is no "open / closed" flag yet — `active` stands in for it.**
The saga treats `active == true` as "open for orders". The code comment in `validateRestaurant` says: *"there is no separate open/closed flag in Phase 4; that's a Phase 8 feature (business hours, holidays, etc.)."* See [SagaEngine.java:206-211](#files-involved-2).

---

### ⑧ Step B: `VALIDATE_ITEMS` — second Feign call (and the price-rewrite)

**What happens.** The next step pulls the menu for the items in the order and recomputes the total from the **server's** price, overwriting anything the client might have sent (which, per the request DTO, they can't anyway):

```java
// SagaEngine.java:218-250
private String validateItems(Order order) {
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
```

**What crosses the wire.**

```
GET http://samato-restaurant-service/api/restaurants/{id}/menu?ids=f4d2...,e5b1...
Authorization: Bearer eyJ...
X-Correlation-Id: ...
```

**The comma-separated `ids` query param is a deliberate trade-off.** Putting the whole menu in the response would be wasteful for large restaurants; the per-item `MenuItem` lookup is a single DB call (`menuItems.findByRestaurantIdAndIdIn(...)`); a 5-item order round-trips in a few hundred bytes. See `RestaurantClient#getMenu` at [RestaurantClient.java:33-35](#files-involved-2).

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:218-250` — `validateItems`.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/RestaurantClient.java:33-35` — the `getMenu` method.

**Anomaly 8: the saga saves the order in the middle of the saga.**
This is the only step in the saga that mutates the order itself (`order.setTotalAmount` and the per-item `unitPrice`). The save happens inside the step's `REQUIRES_NEW` transaction, so the change is visible to subsequent steps (in their own transactions) but invisible to anyone outside until the step commits. If the next step fails and triggers compensation, the compensation only needs to undo the *external* side effects (refund, release inventory) — the order can stay in any internal state because it's marked `CANCELLED` at the end.

---

### ⑨ Step C: `RESERVE_INVENTORY` — the stub

**What happens.** A Phase 4 stub. The real implementation would call restaurant-service to decrement stock atomically. For now the step just emits a "would reserve" payload:

```java
// SagaEngine.java:252-261
private String reserveInventory(Order order) {
    int units = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
    return writeJson(Map.of(
            "stub", true,
            "units", units,
            "reservationId", UUID.randomUUID().toString()
    ));
}
```

**Compensation is also a stub.** When the saga compensates a `RESERVE_INVENTORY` step, it logs a message and moves on. The full file path is [SagaEngine.java:392-395](#files-involved-2). In a real system this step would talk to a stock database; for the bible it's a placeholder that documents the shape of the integration.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:252-261` — the stub.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:392-395` — the no-op compensation.

---

### ⑩ Step D: `CHARGE_PAYMENT` — third Feign call, this time to payment-service

**What happens.** The saga generates a fresh `stepId` UUID, then asks payment-service to create a Razorpay order. The `stepId` is the `Idempotency-Key` — if the saga retries the step, payment-service will see the same key in its `processed_commands` table and replay the prior result instead of double-charging.

```java
// SagaEngine.java:263-289
private String chargePayment(Order order) {
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
```

**What crosses the wire.**

```
POST http://samato-payment-service/api/payments/orders
Authorization: Bearer eyJ...
X-Correlation-Id: ...
Idempotency-Key: e8c7a4f1-...
Content-Type: application/json

{
  "orderId":    "9a3f...",
  "customerId": "4b0a...",
  "amount":     "549.00",
  "currency":   "INR"
}
```

The `PaymentClient` interface declares the call:

```java
// PaymentClient.java:38-41
@PostMapping("/api/payments/orders")
PaymentDtos.PaymentResponse createOrder(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody PaymentDtos.CreatePaymentRequest request);
```

**What payment-service does, in detail.** This is the deepest part of the call chain, so it gets its own sub-trace.

1. **Gateway routing.** `samato-payment-service` is the Eureka name. The request is also behind a gateway, so the JWT gets re-validated by payment-service's `SecurityConfig` ([E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/security/SecurityConfig.java](../services/payment-service.md)).
2. **`PaymentController.createOrder`** ([PaymentController.java:49-69](#files-involved-3)). The `Idempotency-Key` header is `@RequestHeader("Idempotency-Key")` and `@RequestBody` is `@Valid @RequestBody CreatePaymentRequest`. The method:
    - Generates a deterministic `paymentId` from the order id: `UUID.nameUUIDFromBytes(("payment:" + req.orderId()).getBytes())`. This is so a retry of the same `CreateRazorpayOrder` (same stepId) reuses the same `paymentId`, which the saga's idempotency keying depends on.
    - Constructs a `PaymentCommand.CreateRazorpayOrder` (an immutable record) holding the stepId (`commandId`), `paymentId`, the order id, the customer id, the amount, the currency, and the idempotency key.
    - Delegates to `PaymentService.createRazorpayOrder(cmd, idempotencyKey)`.
3. **`PaymentService.createRazorpayOrder`** ([PaymentService.java:54-63](#files-involved-3)). Calls `idempotency.executeOnce("CreateRazorpayOrder", idempotencyKey, cmd.paymentId(), PaymentResponse.class, () -> commandHandler.handleCreateRazorpayOrder(cmd, idempotencyKey))`.
4. **`IdempotencyGuard.executeOnce`** ([IdempotencyGuard.java:46-66](#files-involved-3)) looks up `processed_commands` for `(command_type="CreateRazorpayOrder", key=idempotencyKey)`. If there's a row, it deserialises the stored response and returns it without running the supplier. If there isn't, it runs the supplier and records the result. The race-loser path is handled: a `DataIntegrityViolationException` on the insert means another thread won; we re-read the winner.
5. **`PaymentCommandHandler.handleCreateRazorpayOrder`** ([PaymentCommandHandler.java:74-115](#files-involved-3)) is `@Transactional`. Inside the transaction it:
    - Calls `RazorpayClientImpl.createOrder(paise, currency, receipt=orderId.toString(), idempotencyKey)`. The receipt is our `orderId` — Razorpay dedups on `receipt` server-side, so even if our `Idempotency-Key` is somehow lost, a retry of the same order is still safe.
    - Loads `Payment` (aggregate) from the repository. If it already exists, returns the current state (this is the "idempotent replay" path — the saga retry case).
    - Decides on a new `Payment` aggregate, which produces a `RazorpayOrderCreated` event in its internal list.
    - Calls `eventStore.append(paymentId, commandId, uncommitted)` to write the event to the event store ([PostgresEventStore.java](#files-involved-3)).
    - Calls `projector.apply(entries)` to update the read model (`payment_view`) in the same transaction.
    - For each event, calls `enqueueOutbox(e)` which inserts a row into payment-service's `outbox_events` table with `topic = "samato.payment.created"`.
6. **`RazorpayClientImpl.createOrder`** ([RazorpayClientImpl.java:48-83](#files-involved-3)) is where the SDK call lives. The interesting bit is the `amount` extraction:

    ```java
    // RazorpayClientImpl.java:66-77
    Order order = razorpay.orders.create(request);
    String orderId = order.get("id");
    String status  = order.get("status");
    // Razorpay's SDK returns amount as an Integer for orders < 2^31 paise
    // (~20M INR) and as a Long for larger amounts. Use Number to cover
    // both — older code did `long amount = order.get("amount")` and
    // threw ClassCastException for the Integer case.
    Number amountNum = order.get("amount");
    long amount = amountNum.longValue();
    String ccy = order.get("currency");
    return new RazorpayOrderResult(orderId, receipt, amount, ccy, status);
    ```

7. **`RazorpayClientImpl.toPaise`** ([RazorpayClientImpl.java:128-132](#files-involved-3)) converts the BigDecimal rupee amount to paise with banker's rounding:

    ```java
    public static long toPaise(BigDecimal rupees) {
        return rupees.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact();
    }
    ```

8. Back in `PaymentCommandHandler`, the saga gets a `PaymentResponse` with `paymentId`, `razorpayOrderId`, `status=ORDER_CREATED`, etc.
9. The payment-service response travels back to the saga. The saga stores `paymentId` and `razorpayOrderId` in the step's payload — these are what the **compensation** path will read if a later step fails.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:263-289` — the saga step.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/PaymentClient.java:38-41` — the Feign method.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/PaymentDtos.java` — the request/response DTOs.
- `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java:49-69` — the controller.
- `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentService.java:54-63` — the service.
- `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/idempotency/IdempotencyGuard.java:46-66` — the idempotency dedup.
- `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java:74-115` — the command handler.
- `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java:48-83` — the SDK wrapper.
- `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java:128-132` — `toPaise`.

**Anomaly 9: the saga considers the payment "charged" the moment Razorpay's order is created.**
In the test environment we never call the hosted checkout. We treat `RazorpayOrderCreated` (= `ORDER_CREATED` status) as success and proceed to `CONFIRM_ORDER`. In production, the saga would either (a) wait for a `payment.captured` webhook before confirming, or (b) be moved into a "PAYMENT_INITIATED" state and have a separate consumer mark the order `CONFIRMED` when the webhook fires. As of this writing **no consumer subscribes to `samato.payment.charged`** — see [Anomaly 12](#anomaly-12-no-consumer-listens-to-samatopaymentcharged). The saga's `CHARGE_PAYMENT` step is what advances the order; no separate event is read back.

**Anomaly 10: Razorpay's SDK returns `Integer` for small amounts and `Long` for big ones.**
The code comment in [RazorpayClientImpl.java:69-74](#files-involved-3) explains it: *"Razorpay's SDK returns amount as an Integer for orders < 2^31 paise (~20M INR) and as a Long for larger amounts. Use Number to cover both — older code did `long amount = order.get("amount")` and threw ClassCastException for the Integer case."* The fix is to read into a `Number` and call `.longValue()`. This is the kind of thing the rest of the codebase avoids by going through `Number` whenever a JSON number can be either.

**Anomaly 11: the `Idempotency-Key` is the saga's *step* id, not the request id.**
The order-service does **not** send the customer's `Idempotency-Key` header to payment-service — it sends the saga's `stepId` UUID. This is correct: a customer retry of the **same** order via a fresh `Idempotency-Key` would have a *new* saga (because the saga's stepId is fresh), but the saga itself is the one being retried, not the request. The customer's `Idempotency-Key` is used at the order-service boundary ([step ⑤](#⑤-orderserviceplaceorder-runs-the-idempotency-check-and-persists)) to dedupe the **whole order**; the saga's stepId is used at the payment-service boundary to dedupe the **whole saga's payment step**. The two layers don't share a key.

---

### ⑪ Step E: `CONFIRM_ORDER` — close the saga and emit the event

**What happens.** The last step transitions the order to `CONFIRMED` and queues the outbox event:

```java
// SagaEngine.java:291-297
private String confirmOrder(Order order) {
    order.transitionTo(OrderStatus.CONFIRMED);
    orderRepository.save(order);
    outbox.appendOrderConfirmed(order);
    return writeJson(Map.of("confirmed", true));
}
```

`Order.transitionTo` checks the state machine — `PLACED → VALIDATED → RESERVED → PAID → CONFIRMED` is the happy path, but the saga actually jumps straight from `RESERVED` to `CONFIRMED` because the payment step is what advances the order in Phase 4 (see [Anomaly 9](#anomaly-9-the-saga-considers-the-payment-charged-the-moment-razorpays-order-is-created)). If the transition is illegal, `transitionTo` throws `IllegalStateException`, the saga catches it, and compensation runs.

`outbox.appendOrderConfirmed` is the **append** half of the outbox pattern:

```java
// OutboxPublisher.java:80-91
public void appendOrderConfirmed(Order order) {
    OrderConfirmedEvent event = OrderConfirmedEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setOrderId(order.getId().toString())
            .setCustomerId(order.getCustomerId().toString())
            .setRestaurantId(order.getRestaurantId().toString())
            .setTotalAmount(order.getTotalAmount().doubleValue())
            .setCurrency(order.getCurrency())
            .setOccurredAt(System.currentTimeMillis())
            .build();
    save(TOPIC_ORDER_CONFIRMED, "OrderConfirmedEvent", "Order", order.getId(), event);
}
```

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:291-297` — `confirmOrder`.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/outbox/OutboxPublisher.java:80-91` — the append method.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/outbox/OutboxPublisher.java:108-123` — the `save` helper.

**Anomaly 12: the outbox event payload is JSON bytes, not Avro binary.**
The `save` helper does:

```java
// OutboxPublisher.java:108-118
private void save(String topic, String eventType, String aggregateType,
                  UUID aggregateId, Object avroEvent) {
    byte[] payload = avroEvent.toString().getBytes(StandardCharsets.UTF_8);
    OutboxEvent row = new OutboxEvent();
    row.setTopic(topic);
    row.setEventType(eventType);
    row.setAggregateType(aggregateType);
    row.setAggregateId(aggregateId);
    row.setPayload(payload);
    repo.save(row);
    log.debug("Outbox: queued {} for aggregate {}", eventType, aggregateId);
}
```

`avroEvent.toString()` calls Avro's `toString` override, which renders the record as **JSON text**. The bytes in `outbox_events.payload` are therefore UTF-8 JSON, not the Confluent wire format (magic byte + schema id + Avro binary). Compare this with restaurant-service's `Outbox`, which uses `AvroBytes.encode(event)` to produce real Avro binary — see `restaurant-service/src/main/java/com/samato/restaurantservice/service/Outbox.java:108`. The order/payment path is the documented anomaly called out at the top of the glossary ([00-glossary.md](../00-glossary.md)) and again in [services/shared-and-kafka.md](../services/shared-and-kafka.md). Any consumer of these topics that expects Confluent Avro will fail to deserialise the messages. The mitigation in Phase 8 is to switch to `AvroBytes.encode(...)` on the publisher side and use `KafkaAvroDeserializer` on the consumer side.

**Anomaly 13: order/payment outbox sends are async, restaurant outbox sends are sync.**
`OutboxPublisher.publishPending` (order-service) does `kafka.send(record)` and immediately marks the row sent — it does not call `.get()`. Compare with `Outbox.publishPending` (restaurant-service) at [Outbox.java:130](#files-involved-2) which does `kafkaTemplate.send(record).get(5, SECONDS)`. The two patterns give different failure semantics:

- restaurant-service: send succeeds → row marked sent; send fails → exception, row left unsent, retried on the next tick.
- order-service: send is async; the success is signalled by a future the publisher never reads. If the send eventually fails, the row is **already** marked sent. (A future hardening is to add a `.whenComplete` callback that re-queues the row on failure, but it's not implemented in Phase 4.)

---

### ⑫ Saga completion

**What happens.** After `runStep` returns, `drive` increments `currentStepIndex`, saves the saga, and loops. On the next iteration `nextPendingStep` returns `null` (all five steps are `COMPLETED`), so `drive` sets `saga.status = COMPLETED` and saves.

```java
// SagaEngine.java:144-151
SagaStep step = saga.nextPendingStep();
if (step == null) {
    saga.setStatus(SagaStatus.COMPLETED);
    sagaRepository.save(saga);
    return;
}
```

Control returns to `OrderService.placeOrder`, which reloads the order by `sagaId`, wraps it in a `PlaceOrderResult(replayed=false)`, and returns to the controller. The controller wraps that in `201 Created` with the JSON `OrderResponse`.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:144-151` — terminal-loop.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/service/OrderService.java:103-110` — the call site.

---

### ⑬ The outbox poller publishes the events to Kafka (500ms later)

**What happens.** The request thread has already returned `201 Created` to the customer. Up to 500ms later, the `@Scheduled` poller wakes up:

```java
// OutboxPublisher.java:132-156
@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")
@Transactional
public void publishPending() {
    List<OutboxEvent> unsent = repo.findUnsent();
    if (unsent.isEmpty()) return;
    for (OutboxEvent event : unsent) {
        try {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                    event.getTopic(),
                    event.getAggregateId().toString(),   // key = aggregate id
                    event.getPayload()
            );
            kafka.send(record);
            event.setSentAt(java.time.Instant.now());
            repo.save(event);
            log.info("Outbox: published {} from {} to topic {}",
                    event.getEventType(), event.getAggregateId(), event.getTopic());
        } catch (Exception e) {
            log.error("Outbox: failed to publish event {} (will retry): {}",
                    event.getId(), e.getMessage());
        }
    }
}
```

For our happy-path order, three rows are picked up:

- `samato.order.placed` (from step ⑤ `appendOrderPlaced`)
- `samato.order.confirmed` (from step ⑪ `appendOrderConfirmed`)

There is no `samato.order.cancelled` row — that one only gets written on compensation.

**The partition key is the aggregate id (`orderId` as a String).** That gives all events for the same order the same partition, so a downstream consumer sees them in order. See `OutboxPublisher.java:142`.

**Files involved.**

- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/outbox/OutboxPublisher.java:132-156` — the poller.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/domain/OutboxEventRepository.java` — the `findUnsent` query.
- `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/resources/db/migration/V1__init.sql:81` — the `outbox_events` table DDL.

---

### ⑭ Payment-service publishes its own outbox row to `samato.payment.created`

**What happens.** Separately from step ⑬, payment-service's own outbox poller runs (also at 500ms cadence). In our happy path the row is the `RazorpayOrderCreated` event enqueued at step ⑩. The poller picks it up, sends the JSON bytes to `samato.payment.created`, and marks the row sent. See [PaymentCommandHandler.java:215-228](#files-involved-3) for the enqueue and `E:/Learning/ollama-projects/springboot-app/samato/services/payment-service/src/main/java/com/samato/paymentservice/outbox/OutboxPublisher.java` for the poller.

**The two outbox pollers are independent.** Order-service doesn't know about the payment-service outbox; payment-service doesn't know about the order-service outbox. They just write to two different topics. This is the whole point of the outbox pattern: each service's events are durable in its own database, and a separate process publishes them at its own pace.

---

### ⑮ What the customer sees

The customer gets a `201 Created` with the order body. To them, the order was placed instantly. The events on Kafka are advisory (search-service could project the order into OpenSearch, a notification service could send a "your order is confirmed" push, etc.) — but **as of this writing no consumer subscribes to `samato.order.placed` or `samato.order.confirmed`**, and no consumer subscribes to `samato.payment.charged`. This is the state of the codebase and is also the most important thing to surface in an interview; see [§5](#5-what-can-go-wrong-failure-modes).

---

## 5. What can go wrong (failure modes)

This section is the "what if" companion to the trace. Each failure has: **trigger**, **which step it surfaces at**, **what the saga does**, **what the customer sees**.

### 5.1 Idempotency-Key replay with a different body

**Trigger.** The customer submits the same `Idempotency-Key` with a different restaurant or different items.
**Step.** ⑤ (`OrderService.placeOrder`).
**Saga behaviour.** Detected by the `hash(request)` mismatch. Throws `DomainException("IDEMPOTENCY_KEY_REUSED", ..., 422)`.
**Customer sees.** `422 Unprocessable Entity`, body `{"code": "IDEMPOTENCY_KEY_REUSED", "message": "..."}`. No order written. ([OrderService.java:80-85](#files-involved))

### 5.2 Restaurant not found, or restaurant is inactive

**Trigger.** `restaurantId` doesn't exist, or the restaurant is `active=false`.
**Step.** ⑦ (`validateRestaurant`).
**Saga behaviour.** `validateRestaurant` throws `IllegalStateException`. The `runStep` exception propagates to `drive`, which sets `saga.status = COMPENSATING` and runs the compensation flow.
**Customer sees.** `500` (the exception propagates out of `placeOrder`, which only logs a warning — see [OrderService.java:104-107](#files-involved)). Compensation is fast because no external state was changed.
**Compensation.** None of the previous steps did anything (only step ⑦ itself failed). The order moves to `CANCELLED`, an `OrderCancelled` outbox row is written, the saga moves to `COMPLETED`. ([SagaEngine.java:333-358](#files-involved-2))

### 5.3 Menu item not on menu, or unavailable

**Trigger.** The customer's `menuItemId` is wrong, or the menu item has been marked `available=false` since the customer added it to the cart.
**Step.** ⑧ (`validateItems`).
**Saga behaviour.** Same as 5.2 — the step throws, the saga compensates. The order is `CANCELLED`, no payment was attempted.

### 5.4 Restaurant-service is down

**Trigger.** Eureka has no instances, or the Feign call times out, or the call returns 5xx.
**Step.** ⑦ or ⑧.
**Saga behaviour.** Same as 5.2: the Feign call throws, the saga compensates.
**Fallback.** `RestaurantClientFallback` returns a stub `RestaurantSummary` for `getRestaurant` and a stub `MenuResponse` for `getMenu`. The fallback is *not* the happy path — it returns objects that the saga will treat as "not active" or "no items" and reject. So a Feign circuit-breaker trip effectively makes the order fail (which is what we want — never charge for a non-existent restaurant). See `E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/java/com/samato/orderservice/api/RestaurantClientFallback.java`.
**Resilience4j is configured but not annotated.** The application.yml has a `samato.resilience4j.restaurant-client` block with circuit-breaker + retry, but the code does not use `@CircuitBreaker` or `@Retry` on the Feign methods. Resilience4j is effectively only providing config, not behaviour; the saga's own `try/catch` is what handles the failure. See [Resilience4j in order-service.yml](#files-involved-2) and the comment on [RestaurantClient.java:18-22](#files-involved-2). This is the documented Phase 7 gap.

### 5.5 Razorpay fails (network, auth, etc.)

**Trigger.** `RazorpayClientImpl.createOrder` throws `RazorpayException`, which is wrapped in `PaymentGatewayException`.
**Step.** ⑩ (`chargePayment`).
**Saga behaviour.** The exception propagates out of `runStep`. `drive` sets `saga.status = COMPENSATING`. Compensation runs:
- `VALIDATE_RESTAURANT`, `VALIDATE_ITEMS`, `RESERVE_INVENTORY` have no-op compensations.
- `CONFIRM_ORDER` wasn't reached, so there's nothing to undo.
- The order is moved to `CANCELLED` and a `samato.order.cancelled` outbox row is enqueued.

The customer sees `500` (the exception is logged, not converted to a clean error — see [OrderService.java:104-107](#files-involved)).
**Anomaly 14.** The compensation runs from `REQUIRES_NEW` transactions, so each step's compensation commits independently. If the process crashes mid-compensation, the next saga poller tick (every 1000ms — see [SagaPoller.java:38](#files-involved-2)) picks the saga back up and resumes from where it left off. The `SagaStatus` column is the source of truth.

### 5.6 Payment-service is down

**Trigger.** Same as 5.5 from the order-service point of view — the Feign call throws.
**Saga behaviour.** Same as 5.5.

### 5.7 `payment.captured` webhook never arrives

**Trigger.** The customer's payment fails or is abandoned at the Razorpay hosted checkout. In the test setup we never call hosted checkout, so this is a hypothetical. In production it would be a real failure mode.
**Step.** Post-saga — the order is already `CONFIRMED` because step ⑪ happened. The webhook would arrive at payment-service, which would dispatch it through `PaymentService.handleWebhook` → `dispatchWebhook("payment.captured", ...)` → `commandHandler.handleMarkCaptured(...)`. That writes a `PaymentCaptured` event to the event store, projects to the read model, and enqueues a `samato.payment.charged` Kafka message.
**Saga behaviour.** No saga activity — the saga is already terminal. The order is `CONFIRMED` even though the customer never paid. **This is a bug** and is fixed in two ways:
1. **Phase 6 work** (per the gaps in the inventory) — add a `samato.payment.charged` consumer that listens for `PaymentCaptured` and would advance the order's state. As of this writing **no such consumer exists**. The anomaly is documented in [docs/inventory/endpoints-and-use-cases.json](../inventory/endpoints-and-use-cases.json).
2. **Saga redesign** — the saga's `CHARGE_PAYMENT` should not be considered "done" until the `PaymentCaptured` event is observed. That requires a new saga step type (`AWAIT_PAYMENT_CAPTURED`) and a state machine in the saga for "waiting on external event".

**Anomaly 12 (also): no consumer listens to `samato.payment.charged`.**
The `kafkaListeners` block in [docs/inventory/call-graph.json](../inventory/call-graph.json) has exactly one entry: `search-service` consuming `samato.restaurant.created/updated`. There is **no** `@KafkaListener` for `samato.payment.charged`, `samato.payment.failed`, `samato.order.placed`, `samato.order.confirmed`, or `samato.order.cancelled`. So the order-service outbox poller dutifully ships the JSON bytes, and no one reads them. The gap is intentional in Phase 4 — the bible focuses on the producer side and the cross-service choreography — but it's a real gap, not a fictional one.

### 5.8 Process crash between saga steps

**Trigger.** The order-service JVM is killed (deploy, OOM, hardware) between, say, step ⑧ (validate items) and step ⑩ (charge payment).
**Saga behaviour.** On restart, `SagaPoller.resumeInProgress` ([SagaPoller.java:38-52](#files-involved-2)) fires every 1000ms. It queries `sagaRepository.findByStatus(SagaStatus.RUNNING)`, finds our saga, calls `sagaEngine.drive(saga.getId())`. `drive` reads the saga, sees the next `PENDING` step (the one that wasn't reached), runs it. The earlier steps are already `COMPLETED` and are skipped. The customer's experience is "the order took a few extra seconds" rather than "the order is broken".

### 5.9 Process crash between Kafka write and `sent_at` update

**Trigger.** JVM is killed after `kafka.send(record)` succeeded but before `repo.save(event)` (the `sent_at` update) committed.
**Saga behaviour.** On restart, the outbox poller picks up the same row again and sends it again. The consumer sees a duplicate. As of Phase 4 there is no consumer-side dedup for order events (search-service's restaurant projection is idempotent because it's an upsert by restaurantId, but a hypothetical order projection would need a similar key). The trade-off is documented in [OutboxPublisher.java:39-42](#files-involved-2): *"We don't dedupe at the publisher. Downstream consumers should be idempotent."*

### 5.10 `payment.refund` failure during compensation

**Trigger.** A later step fails after `CHARGE_PAYMENT` succeeded, so compensation runs. The compensation calls `paymentClient.refund(...)`, but the payment-service refund API is down.
**Step.** Compensation of ⑩.
**Saga behaviour.** The `compensateStep` method is itself `@Transactional(REQUIRES_NEW)`. The `compensate` method catches the exception, calls `step.markFailedCompensation(ex.getMessage())`, sets `saga.status = FAILED`, and **stops** — it does not retry the failed compensation. The saga's `failureReason` is set, and the operator must intervene.
**Customer sees.** `500`. The order is in `CANCELLED` state, but the saga is in `FAILED` state — the money has been charged, the inventory was released, and someone has to refund the customer manually.

**Anomaly 15.** The `compensate` method intentionally gives up on the first failed compensation. The Javadoc says: *"Walks the completed steps in REVERSE order and undoes them. Stops at the first step whose compensation fails — manual intervention is required past that point."* For the interview, this is a fair trade-off — you don't want a "compensate forever" loop that hides the real problem.

### 5.11 JWT missing or expired

**Trigger.** Customer submits with no `Authorization` header, or with a token that has expired.
**Step.** ② (gateway) and ③ (order-service security).
**Saga behaviour.** Never reached.
**Customer sees.** `401 Unauthorized`. No order, no outbox row, no saga. The BearerTokenCaptureFilter never fires, the controller never executes, the saga engine never starts. This is the most common failure and the cheapest to fix.

### 5.12 Customer role is not `CUSTOMER`

**Trigger.** A driver or restaurant-owner JWT tries to call `POST /api/orders`.
**Step.** ④ (`@PreAuthorize`).
**Saga behaviour.** Never reached. The `MethodSecurityException` is converted to a 403 by the global exception handler.
**Customer sees.** `403 Forbidden`.

---

## 6. The data trail

The table below traces each "data" thing that gets created along the way, with the file/method that writes it, the table or topic it lands in, and the row's key columns.

| Step | What is written | Where (file) | Where (table or topic) | Key columns / value |
|---|---|---|---|---|
| ⑤ | `Order` row | `OrderService.persistAndStartSaga` | `orders` table (order-service DB) | `id` (UUID, random), `customer_id` (from JWT `sub`), `restaurant_id`, `status = PLACED`, `total_amount = NULL` at this point, `currency` |
| ⑤ | Outbox row for `OrderPlaced` | `OutboxPublisher.appendOrderPlaced` | `outbox_events` table | `aggregate_id = order.id`, `topic = samato.order.placed`, `event_type = OrderPlacedEvent`, `payload = JSON bytes`, `sent_at = NULL` |
| ⑤ | `SagaInstance` row | `SagaEngine.start` | `saga_instances` table | `id` (UUID), `order_id`, `status = RUNNING`, `current_step_index = 0`, `failure_reason = NULL` |
| ⑤ | 5× `SagaStep` rows | `SagaEngine.start` | `saga_steps` table | `saga_id`, `step_index 0..4`, `step_type` (one of the 5), `status = PENDING`, `payload = NULL`, `error_message = NULL` |
| ⑤ | `IdempotencyRecord` (if `Idempotency-Key` was sent) | `OrderService.persistAndStartSaga` | `idempotency_records` table | `customer_id`, `idempotency_key`, `request_hash`, `order_id`, `response_status = 201` |
| ⑦ | `RestaurantSummary` (transient) | `RestaurantController.get` | read from `restaurants` (restaurant-service DB) or Caffeine cache | `id`, `name`, `active` |
| ⑧ | `MenuItem` set (transient) | `RestaurantController.getMenu` | read from `menu_items` (restaurant-service DB) | `id`, `name`, `price`, `available` |
| ⑧ | Updated `Order` row (price + total) | `SagaEngine.validateItems` (via `runStep`'s `REQUIRES_NEW`) | `orders` table | `total_amount = computed sum` |
| ⑩ | `Payment` aggregate (initial) | `PaymentCommandHandler.handleCreateRazorpayOrder` | `payment_view` table (payment-service DB) | `payment_id`, `razorpay_order_id`, `status = ORDER_CREATED` |
| ⑩ | `RazorpayOrderCreated` event | `PostgresEventStore.append` | `event_store` table | `aggregate_id = paymentId`, `version = 1`, `event_type = RazorpayOrderCreated`, `event_data = JSONB` |
| ⑩ | Outbox row for `RazorpayOrderCreated` | `PaymentCommandHandler.enqueueOutbox` | `outbox_events` table (payment-service DB) | `aggregate_id = paymentId`, `topic = samato.payment.created`, `event_type = RazorpayOrderCreated`, `payload = JSONB bytes`, `sent_at = NULL` |
| ⑩ | `ProcessedCommand` | `IdempotencyGuard.recordResult` | `processed_commands` table | `command_type = CreateRazorpayOrder`, `key = saga stepId`, `aggregate_id = paymentId`, `result_body = serialized PaymentResponse` |
| ⑩ | Razorpay `Order` (remote) | `RazorpayClientImpl.createOrder` | razorpay.com (test mode) | `id = rzp_test_...`, `amount = paise`, `currency`, `receipt = orderId`, `status = created` |
| ⑪ | Updated `Order` row (status) | `SagaEngine.confirmOrder` | `orders` table | `status = CONFIRMED` |
| ⑪ | Outbox row for `OrderConfirmed` | `OutboxPublisher.appendOrderConfirmed` | `outbox_events` table (order-service DB) | `aggregate_id = order.id`, `topic = samato.order.confirmed`, `event_type = OrderConfirmedEvent` |
| ⑬ | Updated outbox row (`sent_at`) | `OutboxPublisher.publishPending` | `outbox_events` (order-service) | `sent_at = now()` for `OrderPlaced` and `OrderConfirmed` |
| ⑬ | Kafka message | `OutboxPublisher.publishPending` | `samato.order.placed` topic | `key = orderId`, `value = JSON bytes` (Anomaly 12) |
| ⑬ | Kafka message | `OutboxPublisher.publishPending` | `samato.order.confirmed` topic | `key = orderId`, `value = JSON bytes` |
| ⑭ | Kafka message | payment-service `OutboxPublisher.publishPending` | `samato.payment.created` topic | `key = paymentId`, `value = JSON bytes` |
| ⑮ | HTTP response | `OrderController.place` | wire | `201 Created`, body = `OrderResponse` JSON |

**Tables touched across the whole happy path**: `orders`, `saga_instances`, `saga_steps`, `idempotency_records`, `outbox_events` (×2 in order-service DB; ×1 in payment-service DB), `event_store`, `payment_view`, `processed_commands` (all in payment-service DB). No order is written to `restaurants` or `menu_items` — those are read-only in this flow.

**Topics produced**: `samato.order.placed`, `samato.order.confirmed`, `samato.payment.created`. None of these are consumed inside the order-service or payment-service for this flow.

**Why three outbox rows for one order.** The order-service outbox has two rows (one per event) because there are two events. The payment-service outbox has one row because there is one payment event. Each row is independent: if the order-service outbox poller crashes after sending `OrderPlaced` but before `OrderConfirmed`, the next tick re-sends the `OrderConfirmed` row (and a consumer must dedup by eventId).

---

## 7. See also

- [00-glossary.md](../00-glossary.md) — every term in this doc is defined there.
- [01-architecture-guide.md](../01-architecture-guide.md) — the system map; this walkthrough is the order-side slice of that map.
- [02-how-auth-works.md](../02-how-auth-works.md) — the JWT lifecycle and the gateway's role in this flow.
- [services/order-service.md](../services/order-service.md) — the order-service reference (file map, controllers, saga engine internals, schema).
- [services/payment-service.md](../services/payment-service.md) — the payment-service reference (event sourcing, IdempotencyGuard, Razorpay, webhook).
- [services/restaurant-service.md](../services/restaurant-service.md) — the restaurant-service reference (the two read endpoints the saga calls).
- [services/shared-and-kafka.md](../services/shared-and-kafka.md) — the full anomaly breakdown for the two on-the-wire Kafka formats.
- [services/api-gateway.md](../services/api-gateway.md) — the gateway routes and the `JwtAuthFilter`.
- [docs/inventory/call-graph.json](../inventory/call-graph.json) — the machine-readable list of every Feign client, Kafka producer, Kafka consumer, outbox table, and Resilience4j config in the repo.
- [docs/inventory/endpoints-and-use-cases.json](../inventory/endpoints-and-use-cases.json) — the endpoint reference and the Phase 6 gap list.

### Other use cases

- [02-auth-flow.md](./02-auth-flow.md) — the JWT issuance + JWKS story end to end.
- [03-browse-and-search.md](./03-browse-and-search.md) — the read path through restaurant-service and search-service.
- [04-refund-flow.md](./04-refund-flow.md) — the saga compensation path in detail (and the `payment.captured` / `refund.processed` webhooks).

### Source-of-truth files cited in this walkthrough

If you want to re-derive anything above, these are the files and line ranges that are load-bearing:

- `services/order-service/src/main/java/com/samato/orderservice/web/OrderController.java:48-58` — entry point.
- `services/order-service/src/main/java/com/samato/orderservice/service/OrderService.java:69-110, 118-154, 187-198` — the place order service.
- `services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java:55-61, 100-163, 200-297, 307-419` — the saga and the five steps.
- `services/order-service/src/main/java/com/samato/orderservice/saga/SagaPoller.java:38-52` — the resumability poller.
- `services/order-service/src/main/java/com/samato/orderservice/outbox/OutboxPublisher.java:66-156` — the append + publish loop.
- `services/order-service/src/main/java/com/samato/orderservice/api/FeignAuthForwarder.java:29-58` — the JWT forwarder.
- `services/order-service/src/main/java/com/samato/orderservice/api/PaymentClient.java:32-51` — the payment Feign interface.
- `services/order-service/src/main/java/com/samato/orderservice/api/RestaurantClient.java:24-36` — the restaurant Feign interface.
- `services/order-service/src/main/java/com/samato/orderservice/security/SecurityConfig.java:50-66, 81-98, 104-120` — the security filter chain and the JWT-to-authority converter.
- `services/payment-service/src/main/java/com/samato/paymentservice/web/PaymentController.java:49-69` — the create-order endpoint.
- `services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentService.java:54-110` — the service-level dispatcher.
- `services/payment-service/src/main/java/com/samato/paymentservice/idempotency/IdempotencyGuard.java:46-93` — the dedup.
- `services/payment-service/src/main/java/com/samato/paymentservice/service/PaymentCommandHandler.java:74-115, 215-240` — the command handler and the outbox enqueue.
- `services/payment-service/src/main/java/com/samato/paymentservice/api/RazorpayClientImpl.java:48-83, 128-132` — the Razorpay SDK wrapper, the `Number` quirk, and `toPaise`.
- `services/restaurant-service/src/main/java/com/samato/restaurantservice/web/RestaurantController.java:46-62` — the two endpoints the saga calls.
- `services/restaurant-service/src/main/java/com/samato/restaurantservice/service/RestaurantService.java:57-77` — the cached reads.
- `services/restaurant-service/src/main/java/com/samato/restaurantservice/service/Outbox.java:68-141` — the contrast: real Avro binary + sync send.

### Glossary terms introduced by this walkthrough (cross-references)

- [Saga](../00-glossary.md#saga)
- [Compensation](../00-glossary.md#compensation)
- [Transactional outbox](../00-glossary.md#transactional-outbox)
- [Idempotency-Key](../00-glossary.md#idempotency-key)
- [Feign](../00-glossary.md#feignclient)
- [JWT](../00-glossary.md#jwt)
- [JWKS](../00-glossary.md#jwks)
- [@Transactional](../00-glossary.md#transactional)
- [@Scheduled](../00-glossary.md#scheduled)
- [@PreAuthorize](../00-glossary.md#preauthorize)
- [@Cacheable](../00-glossary.md#cacheable) (used by restaurant-service, not order-service)
- [Event sourcing](../00-glossary.md#event-sourcing) (used by payment-service)
- [CQRS](../00-glossary.md#cqrs) (used by payment-service)
- [Paise](../00-glossary.md#paise) (Razorpay's smallest unit)
- [SagaStep / SagaStepType / SagaStepStatus](../00-glossary.md#sagastep--sagasteptype--sagastepstatus)
- [SagaStatus](../00-glossary.md#sagastatus)
- [OrderStatus](../00-glossary.md#orderstatus)
- [PaymentStatus](../00-glossary.md#paymentstatus)
