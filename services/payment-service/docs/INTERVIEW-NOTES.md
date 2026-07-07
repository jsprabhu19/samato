# Phase 5 — Payment Service (Razorpay + Event-Sourced Reconciliation Ledger)

> *"Razorpay moves the money. We remember what happened."*

This document is the interview-prep narration of the payment-service.
The code is the canonical truth; this file is the *why*.

---

## TL;DR

We built a `payment-service` that:

1. **Integrates Razorpay in test mode** for actual money movement.
2. **Stores every state change as an event** in a JSONB-backed PostgreSQL event store.
3. **Projects events to a denormalised read model** (CQRS) for fast status queries.
4. **Verifies Razorpay webhooks** with HMAC-SHA256 signature checking.
5. **Is idempotent** on every command path (HTTP `Idempotency-Key` header + `processed_commands` table).
6. **Connects to the order-service saga** so failed orders automatically refund customers.

---

## 1. The architectural decision: Razorpay-first, ledger-second

This is the single most important design choice in the bible. There are two extremes:

| Approach | Description | Trade-off |
|----------|-------------|-----------|
| **Wallet-first** | We own the money. The PSP is "just" a gateway. | Doubles the bug surface (we + PSP bugs). Compliance nightmare. Hard to reverse. |
| **PSP-first** | Razorpay owns the money. We mirror state for audit. | We can't lie about money — ever. Reconciliation drift is detectable. |

We chose **PSP-first**. Reasons:

- **Razorpay is the source of truth for "did the money move?"** If our DB says "captured" but Razorpay says "failed", the customer is confused. We mirror Razorpay's state transitions through webhooks.
- **Regulatory reality.** Storing card / UPI / wallet data in our system invites PCI-DSS audits and 6-month security reviews. Razorpay handles that; we don't store any of it.
- **Reconciliation is real.** A dual-ledger approach (Razorpay says X, we say Y) is auditable. If they diverge, we have a row in `events` that explains the divergence.

> Interview line: *"Payment gateways are always the source of truth. We keep a parallel ledger for compliance, time-travel, and analytics. The interesting question is how we keep them in sync — the answer is webhooks, idempotency, and an event store."*

---

## 2. The event-sourced Payment aggregate

`Payment` is a fully event-sourced aggregate:

- **No setters.** State changes only via `apply(PaymentEvent)`.
- **Identity is set by the first event.** The first `RazorpayOrderCreated` populates `paymentId`, `orderId`, `customerId`, `currency`. Subsequent events throw if they try to reassign.
- **State is derived.** The current state is the result of replaying all events from version 0.
- **Snapshots every 50 events.** Bounded replay cost.

```java
public class Payment {
    public static Payment rehydrate(List<PaymentEvent> events) { ... }
    public static Payment rehydrateFromSnapshot(SnapshotState s) { ... }
    public RazorpayOrderCreated createOrder(PaymentCommand.CreateRazorpayOrder cmd, String razorpayOrderId, int nextVersion) { ... }
    public PaymentCaptured markCaptured(String razorpayPaymentId, int nextVersion) { ... }
    // ...
    void apply(PaymentEvent e) { ... }  // the ONLY mutator
}
```

> Interview line: *"The compiler enforces the invariant: every state change is paired with an event. We don't need a debugger to know the aggregate's state is consistent with its history."*

---

## 3. The event store schema

```sql
CREATE TABLE events (
    sequence_number  BIGSERIAL    PRIMARY KEY,
    aggregate_id     UUID         NOT NULL,
    aggregate_type   VARCHAR(50)  NOT NULL,
    event_type       VARCHAR(80)  NOT NULL,
    event_data       JSONB        NOT NULL,
    version          INT          NOT NULL,
    command_id       UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
);
```

Three things to notice:

- **`UNIQUE(aggregate_id, version)`** is the **optimistic concurrency control**. If two writers try to append `version=5` for the same aggregate, the second one gets a `DataIntegrityViolationException` which we translate to an `OptimisticLockException`.
- **`JSONB` for the payload** (not Avro, not bytea). Debug-friendly: a human can read the rows in psql. Indexable via GIN if we want to query by payload field.
- **`BIGSERIAL` for `sequence_number`** gives us a **monotonic global ordering** for the read-model projector (we apply events in insertion order, not in per-aggregate version order).

> Interview line: *"Two concurrency primitives: per-aggregate version catches concurrent writers for the same aggregate; global sequence number orders the projection. They're different things, both important."*

---

## 4. The command-side flow (what happens when the saga calls `createOrder`)

```
1. order-service saga calls paymentClient.createOrder(idempotencyKey, request)
        │
        ▼
2. WebhookController / PaymentController receives POST /api/payments/orders
        │   - JWT validated by Spring Security
        │   - Idempotency-Key header required
        ▼
3. PaymentService.createRazorpayOrder(cmd, idempotencyKey)
        │   - Wraps the call in IdempotencyGuard.executeOnce(...)
        │   - If (CreateRazorpayOrder, idempotencyKey) seen: replay prior result
        │   - Otherwise: run the command handler
        ▼
4. PaymentCommandHandler.handleCreateRazorpayOrder(...)
        │   - razorpay.createOrder(...) — paise on the wire
        │   - Payment.createOrder(cmd, razorpayOrderId, version=1)
        │     → produces RazorpayOrderCreated event
        │   - PostgresEventStore.append(...) — REQUIRES_NEW txn
        │   - PaymentProjector.apply(...) — updates payment_view
        │   - Outbox row inserted for Kafka topic samato.payment.created
        │   - maybeSnapshot(...) — every 50 events
        ▼
5. OutboxPublisher (scheduled, every 500ms)
        │   - Reads unsent rows, publishes to Kafka, marks sent_at
        ▼
6. Response: { paymentId, razorpayOrderId, amount, currency, status: "ORDER_CREATED" }
```

The whole flow is **transactional up to step 4**. If the JVM dies after `eventStore.append` but before the response, the saga will retry; the Idempotency-Key replays the prior response. If the JVM dies after step 4's response but before the outbox poller runs, the outbox row is still in the DB — Kafka gets the event eventually.

> Interview line: *"The transactional outbox is the only safe way to do 'DB-write + Kafka-publish' without 2PC. We accept eventual consistency between the DB and Kafka in exchange for not losing any event."*

---

## 5. Webhook handling — the source of truth for "money moved"

Razorpay doesn't trust us to ask "did the payment succeed?" every second. Instead, they POST to us.

```
Razorpay                payment-service
    │                          │
    │──POST /api/payments/webhooks/razorpay
    │  X-Razorpay-Signature: <hmac sha256 hex>
    │  body: {"event":"payment.captured", ...}
    │─────────────────────────▶
    │                          │
    │                  ┌────── verifyWebhookSignature
    │                  │       (HMAC SHA-256, constant-time)
    │                  │
    │                  ├────── Idempotency check
    │                  │       (RazorpayWebhook, eventId)
    │                  │       If seen: 200 OK, no work
    │                  │
    │                  └────── Dispatch to command handler
    │                          markCaptured, markFailed,
    │                          completeRefund, etc.
    │                          (same code path as internal calls)
    │                          │
    │◀─────200 OK──────────────│
```

Two security choices that are NOT obvious:

1. **HMAC verification happens in the controller**, not in Spring Security. We have a separate `SecurityFilterChain` that allows `permitAll()` for `/api/payments/webhooks/**`, because Razorpay doesn't have our JWT. The signature check is the auth.
2. **Bad signature → 401.** Razorpay retries on non-2xx, so a 401 makes them retry, but a tampered request will never pass.

> Interview line: *"The signature header is the only auth. We never trust the body. We return 401 on bad signature so Razorpay keeps retrying — if it's tampered, we want to know."*

---

## 6. Idempotency — the saga's safety net

The saga's `CHARGE_PAYMENT` step can be retried (network blip, leader election, etc). If we charge the customer twice, we've lost their trust forever.

Two layers of protection:

| Layer | Mechanism | What it protects against |
|-------|-----------|-------------------------|
| **HTTP** | `Idempotency-Key` header (from saga's step id) | Saga retry, network-level double-call |
| **PSP** | Razorpay's own idempotency (via `receipt` field) | Same customer refreshing the page |

The `processed_commands` table:

```sql
CREATE TABLE processed_commands (
    id            UUID PRIMARY KEY,
    command_type  VARCHAR(80) NOT NULL,    -- e.g. 'CreateRazorpayOrder'
    key           VARCHAR(200) NOT NULL,   -- e.g. saga step id
    aggregate_id  UUID NOT NULL,
    result_status INT NOT NULL,
    result_body   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_command UNIQUE (command_type, key)
);
```

First call: write the row, return the result. Subsequent calls with the same `(command_type, key)`: read the row, return the cached result. No double-charge.

> Interview line: *"Idempotency is the boring correctness property that everyone forgets until they ship a billing bug. Two layers, one HTTP, one PSP, both required."*

---

## 7. Time-travel queries

The `events` table is a complete log. The `/balance-at/{version}` endpoint:

```java
@GetMapping("/{id}/balance-at/{version}")
public BalanceAtResponse balanceAt(@PathVariable UUID id, @PathVariable int version) {
    Money m = queryService.balanceAt(id, version);
    return BalanceAtResponse.of(id, version, m);
}
```

Implementation:

```java
public Payment loadAtVersion(UUID paymentId, int version) {
    List<PaymentEvent> stream = eventStore.loadStream(paymentId);
    return Payment.rehydrate(
        stream.stream()
              .filter(e -> e.version() <= version)
              .toList());
}
```

You can ask: *"What was the state of payment X at version 5?"* — and the answer is **replay events 0..5**. The same code path that rehydrates the current state is used to time-travel.

> Interview line: *"The same code that boots up the aggregate boots up its past. The event store is not just a write log — it's a temporal database. 'Time travel' isn't a feature, it's a side effect of the architecture."*

---

## 8. Snapshots

A hot payment can accumulate hundreds of events. Replay on every load gets expensive. Solution: snapshot every 50 events.

```sql
CREATE TABLE payment_snapshots (
    payment_id     UUID PRIMARY KEY,
    version        INT NOT NULL,
    snapshot_data  JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

The `PaymentRepository.load(paymentId)` does:

```
1. SELECT latest snapshot for paymentId, get version V
2. Rehydrate Payment from snapshot
3. Replay events with version > V (in practice, ~50 events)
```

If a snapshot is corrupt, we throw — we don't fall back silently, because the operator needs to know.

> Interview line: *"Snapshots are a performance optimisation, not a source of truth. If they go wrong, we fall back to the event store. We never lose data."*

---

## 9. The order-service saga integration

The order-service saga's `CHARGE_PAYMENT` step now does:

```java
private String chargePayment(Order order) {
    UUID stepId = UUID.randomUUID();   // Idempotency-Key
    PaymentDtos.PaymentResponse resp = paymentClient.createOrder(
        stepId.toString(),
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

If the saga fails later, the compensation walks back through the steps and **refunds**:

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

The customer is made whole. The saga's job is to make the system "as if the order never happened" when it fails.

> Interview line: *"Saga compensation is the inverse of each step. CHARGE_PAYMENT's compensation is REFUND — both with idempotency. If the saga retries during compensation, we don't refund twice."*

---

## 10. Webhook signature verification — the security boundary

The signature verification is in `WebhookSignatureVerifier`:

```java
public static boolean verify(String secret, String payload, String expectedSignatureHex) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(payload.getBytes(UTF_8));
    return constantTimeEquals(toHex(digest), expectedSignatureHex);
}
```

Three things to notice:

- **Constant-time comparison.** `MessageDigest.isEqual` is constant-time, so an attacker can't measure response time to guess the prefix.
- **Secret from env var.** The webhook secret is never in the application.yml; it comes from `RAZORPAY_WEBHOOK_SECRET` env var (or Vault in production).
- **The SDK's `Utils.verifyWebhookSignature` does the same thing.** We have our own implementation for two reasons: (a) the SDK throws on internal errors, we want a clean boolean; (b) auditors like to see security checks in our code, not a transitive dependency.

> Interview line: *"Constant-time comparison is the easy detail. The hard part is what happens when verification fails: we return 401, not 200, so Razorpay retries. We never silently accept a bad signature."*

---

## 11. The outbox pattern (transactional event publishing)

The problem: we need to write to the event store AND publish to Kafka. Two operations, two systems, no 2PC.

```
If we publish to Kafka, then crash before DB commit → event is published, DB has no record (data loss).
If we DB-commit, then crash before Kafka publish → DB has the event, Kafka doesn't (delivery loss).
```

Solution: **outbox table**.

```sql
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    topic           VARCHAR(100) NOT NULL,
    payload         BYTEA NOT NULL,
    sent_at         TIMESTAMPTZ,           -- null = unsent
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`OutboxPublisher` is a `@Scheduled` poller that:

1. Reads `WHERE sent_at IS NULL` in small batches.
2. Publishes each to Kafka.
3. On success, sets `sent_at = now()`.
4. On failure, leaves the row, retries next tick.

The same DB transaction writes the event to `events` AND the row to `outbox_events`. Either both commit or neither does.

> Interview line: *"The outbox is the standard answer to 'DB and Kafka in the same transaction'. It's not free — you need a poller — but it's much simpler than 2PC and works at any scale."*

---

## 12. Why JSONB on the inside, Avro on the outside

| Layer | Format | Why |
|-------|--------|-----|
| Event store | JSONB | Human-debuggable, GIN-indexable, no schema registry needed |
| Kafka wire | Avro | Schema evolution, contract enforcement, smaller payloads |
| HTTP API | JSON | Standard, easy to consume from any client |

We don't need schema evolution in the event store because we own both ends. We DO need it on Kafka because we don't know who's consuming.

> Interview line: *"The format choice is driven by who reads the data. Inside, we want debuggability. On the wire, we want a contract. Different problems, different solutions."*

---

## 13. What we deferred to Phase 6+

- **Real money.** Test mode only. Set `RAZORPAY_KEY_ID` from your dashboard.
- **Multi-currency.** INR only. The `currency` field is a 3-letter code everywhere; switching to USD is a small change but we kept the bible focused.
- **Admin dashboard for refunds.** Operators refund via SQL or via the API directly.
- **Webhook signature rotation.** When you rotate the secret, both old and new need to be accepted during the transition.
- **Failed-webhook retry queue.** Razorpay retries automatically; we don't need our own.
- **PDF receipts.** Post-MVP.
- **Order status `PAID` transition.** The saga still goes `RESERVED → CONFIRMED` directly. The `PAID` state is in the enum but not yet wired; we can hook it to the `payment.captured` Kafka event in Phase 6.

---

## 14. Interview questions, answered

**Q: Why event sourcing instead of just storing the latest state?**
A: Three reasons. (1) Audit — every state change has a record. (2) Time-travel — we can ask "what was the state at version N?". (3) Schema evolution — adding a new field doesn't require migrating old rows; the events are immutable.

**Q: Why JSONB and not Avro in the event store?**
A: JSONB is human-debuggable and GIN-indexable. We don't need schema evolution in the store because we own both ends. On Kafka we use Avro because we don't know all the consumers.

**Q: Why a separate read model (CQRS)?**
A: Two reasons. (1) The read model can be denormalised for fast queries (e.g. "razorpay_order_id → payment"). The event store is not designed for that. (2) We can change the read model without touching the events.

**Q: How do you prevent double-charging?**
A: Two layers. The HTTP `Idempotency-Key` header is the saga's safety net. Razorpay's own `receipt` dedup is the customer's safety net (refresh the page, no second charge). Both are needed.

**Q: How do you handle Razorpay being down?**
A: The Feign client has a fallback factory that throws `PaymentUnavailableException`. The saga's retry policy picks the step up on the next tick. The order stays in `RESERVED` state; the saga will retry until Razorpay is back.

**Q: What's the difference between a command and an event?**
A: A command is "I want this to happen" — it can be rejected. An event is "this already happened" — it cannot. In our code: `PaymentCommand` records can fail validation; `PaymentEvent` records are immutable facts.

**Q: Why not just store payment status in one row?**
A: Because we lose the history. "Payment went CREATED → INITIATED → CAPTURED → REFUND_INITIATED → REFUNDED" tells a story that "current status: REFUNDED" doesn't. Compliance, analytics, debugging — all need the history.

---

## 15. The one-line summary

> *We own the audit log; Razorpay owns the money. The event store keeps us honest about both.*
