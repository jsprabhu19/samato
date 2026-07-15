# payment-service (port 8084)

> Plain-English purpose: the **payment-service** is the "money" half of the Samato platform. It talks to **Razorpay** (the Indian payment service provider) to create orders, capture payments, and issue refunds; it also keeps a complete **event-sourced ledger** in PostgreSQL so we can answer "what happened to this customer's ₹499?" with forensic precision. The service demonstrates five patterns at once: **Event Sourcing** (the `Payment` aggregate is rebuilt by replaying events), **CQRS** (the write path appends to `events`; the read path queries the denormalised `payment_view`), **Transactional Outbox** (event publication to Kafka is decoupled via `outbox_events`), **Idempotency** (every command is deduped on `(command_type, idempotency_key)`), and **API-level Resilience** (circuit breaker on the Razorpay boundary). The one thing this service is *not* is a source of truth for whether money moved — Razorpay is. We mirror Razorpay's state transitions through webhooks; our ledger is the audit trail.

---

## 1. Where it sits in the system

```
                         ┌──────────────────┐
                         │   api-gateway    │  (JWT verify at edge)
                         │   :8080          │
                         └────────┬─────────┘
                                  │ /api/payments/**  (JWT)
                                  ▼
   ┌────────────────┐    ┌──────────────────────┐    ┌────────────────────┐
   │  order-service │───►│   payment-service    │───►│  Razorpay (test)   │
   │  (saga)        │Feign│   :8084              │HTTPS│  rzp_test_***      │
   │  :8083         │    │                      │    └─────────┬──────────┘
   └────────────────┘    │  Controllers:        │              │ webhooks (HMAC)
                         │   - PaymentController│              │ (replays state)
                         │   - WebhookController│◄─────────────┘
                         │                      │
                         │  Outbox poller (500ms)
                         │       │
                         │       ▼
                         │  Kafka  :9094
                         │   - samato.payment.created
                         │   - samato.payment.charged
                         │   - samato.payment.failed
                         │   - samato.payment.refund.initiated
                         │   - samato.payment.refunded
                         │   - samato.payment.expired
                         │
                         │  PostgreSQL `payment_service`
                         │   - events (JSONB, append-only)
                         │   - payment_snapshots (every 50 events)
                         │   - payment_view (CQRS read model)
                         │   - processed_commands (idempotency)
                         │   - outbox_events
                         └──────────────────────┘
```

- **Calls in (synchronous):** the `order-service` saga via **Feign** (`PaymentClient.createOrder` and `.refund`) and an authenticated REST client via the API gateway (`GET /api/payments/{id}`, `GET /api/payments/by-order/{orderId}`, etc.).
- **Calls out (synchronous):** Razorpay's REST API (`/v1/orders`, `/v1/payments/{id}/refund`), wrapped by the `RazorpayClient` interface and the `RazorpayClientImpl` SDK wrapper. The JWKS endpoint of `auth-service` is fetched on startup by Spring's resource-server boot (no per-request call).
- **Calls in (asynchronous, webhooks):** Razorpay POSTs to `/api/payments/webhooks/razorpay` whenever a payment is captured, fails, or is refunded.
- **Calls out (asynchronous, Kafka):** the outbox poller publishes events to Kafka (no other service consumes payment events today; the contract is forward-looking — the orders team and any future notification/analytics services are the consumers).
- **Persistence:** one PostgreSQL database per service; this one is **`payment_service`**, created by `infra/postgres/init-databases.sh`.

---

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/payment-service` |
| Port | **8084** (read from `application.yml` `server.port`) |
| Database(s) | PostgreSQL `payment_service` on `localhost:5432` (compose overrides to `postgres:5432` via `POSTGRES_URL`) |
| Publishes topics | `samato.payment.created`, `samato.payment.charged`, `samato.payment.failed`, `samato.payment.refund.initiated`, `samato.payment.refunded`, `samato.payment.expired` (via outbox, NOT directly via `KafkaTemplate`) |
| Consumes topics | (none) — no `@KafkaListener`; webhooks are the only inbound async source |
| REST endpoints | `POST /api/payments/orders` (create Razorpay order), `GET /api/payments/{id}`, `GET /api/payments/by-order/{orderId}`, `GET /api/payments/{id}/events`, `GET /api/payments/{id}/balance-at/{version}`, `POST /api/payments/{id}/refunds`, `POST /api/payments/webhooks/razorpay` |
| Depends on | `shared` (cross-cutting: `DomainException`, `MdcKeys`); `shared-kafka` (Avro deps on classpath even though this service does not encode Avro on the wire — the outbox publishes raw JSONB bytes); Razorpay SDK (`com.razorpay:razorpay-java` 1.4.5); `auth-service` (JWKS) |

---

## 3. File-by-file walkthrough

This section walks every `.java` file under `services/payment-service/src/main/java/`. The directory tree is:

```
com/samato/paymentservice/
├── PaymentServiceApplication.java
├── api/              ← REST boundary, Razorpay SDK wrapper, DTOs, HMAC verifier
├── config/           ← Kafka byte-array template
├── domain/           ← Aggregate, value object, status enum, repository, command/event sealed interfaces
├── domain/command/   ← Sealed interface + record command types
├── domain/event/     ← Sealed interface + record event types
├── eventstore/       ← Append-only event log, optimiser lock, serde
├── eventstore/snapshot/
├── idempotency/      ← processed_commands table + guard
├── outbox/           ← Transactional outbox entity, repo, scheduled poller
├── projection/       ← In-process CQRS projector + read model
├── query/            ← Read-side service (event store + view)
├── security/         ← Two filter chains (JWT + webhook permitAll)
├── service/          ← Orchestrator + command handler (writes)
└── web/              ← Two REST controllers
```

### 3.1 `PaymentServiceApplication.java` (root)

- **What it is** — the Spring Boot main entry point.
- **Why it exists** — bootstraps the Spring context, starts the embedded Tomcat on port 8084, registers the service with Eureka, and turns on `@Scheduled` so the outbox poller can run. The javadoc on the class is a full architectural essay ("Razorpay moves the money. We remember what happened.").
- **Spring annotations:**
  - `@SpringBootApplication` — combines `@Configuration`, `@EnableAutoConfiguration`, and `@ComponentScan`; the application root, kicks off component scanning.
  - `@EnableDiscoveryClient` — registers the service with Eureka so the api-gateway and order-service can look it up by name (`samato-payment-service`).
  - `@EnableScheduling` — turns on `@Scheduled` processing so `OutboxPublisher.publishPending()` runs every 500 ms.
  - `@ComponentScan(basePackages = {"com.samato.paymentservice", "com.samato.shared", "com.samato.sharedkafka"})` — the default scan from `@SpringBootApplication` would only look under `com.samato.paymentservice`; this explicit triple-scan pulls in the cross-cutting classes from `shared/` (the exception/error handling) and `shared-kafka/` (the Avro classes on the classpath). Without this, the `KafkaMdcProducerInterceptor` from shared-kafka would not be registered.
- **What it calls** — `SpringApplication.run(...)` (static). That's it; the rest is auto-wired.
- **What calls it** — the JVM, via `java -jar` or `docker compose up payment-service`.
- **Configuration keys** — none directly; all bean wiring is driven by `application.yml`.

### 3.2 `api/PaymentDtos.java`

- **What it is** — a container of three `record` types used as HTTP request/response bodies: `CreatePaymentRequest`, `RefundRequest`, `PaymentResponse`, plus two small helpers (`EventResponse`, `BalanceAtResponse`).
- **Why it exists** — DTOs are deliberately **separate from domain types** so the wire format can evolve without rippling into the event-sourced core (e.g. we can add a `correlationId` field to the response without touching `Payment`).
- **Spring annotations:**
  - `public final class` — `PaymentDtos` is a namespace, not instantiable; `private PaymentDtos() {}` prevents subclassing. The records are plain Java 21 `record`s, not Spring-annotated.
  - `@NotNull` (Jakarta Validation, on the record components of `CreatePaymentRequest` and `RefundRequest`) — tells Spring's `@Valid` to reject a request that has null in those fields. The class itself is the namespace; the validation runs at the controller.
  - `@DecimalMin("0.01")` (on `amount`) — refuses amounts of 0 or negative. Stops a malicious client from "refunding" a negative amount and pocketing the difference.
  - `@Pattern(regexp = "[A-Z]{3}")` (on `currency`) — enforces the ISO 4217 3-letter shape.
- **What it calls** — only Java stdlib (`BigDecimal`, `OffsetDateTime`, `UUID`) and the local `Money` / `PaymentStatus` types.
- **What calls it** — the controllers (`PaymentController`, `WebhookController`) and the service layer (`PaymentCommandHandler.toResponse`).
- **Configuration keys** — none.

### 3.3 `api/RazorpayClient.java`

- **What it is** — an **interface** that defines the boundary between the service and Razorpay. Three operations: `createOrder`, `refund`, `verifyWebhookSignature`. Plus two result records (`RazorpayOrderResult`, `RazorpayRefundResult`).
- **Why it exists** — separating the contract from the implementation gives us (1) a test seam (mock Razorpay in unit tests), (2) a place to apply Resilience4j policies, (3) a fallback class that we can wire if the circuit opens. The javadoc explicitly warns: **don't put `@CircuitBreaker` on this interface** because that creates a JDK dynamic proxy, and with two impls (real + fallback) Spring can't disambiguate the bean.
- **Spring annotations:** none on the interface itself. The annotations live on `RazorpayClientImpl` (the concrete class) where CGLIB proxying works correctly.
- **What it calls** — none (it's an interface).
- **What calls it** — `RazorpayClientImpl` (implements), `RazorpayClientFallback` (would also implement but isn't wired as a bean — see §3.5), and `PaymentCommandHandler` (uses it via constructor injection).
- **Configuration keys** — none.

### 3.4 `api/RazorpayClientImpl.java`

- **What it is** — the **real** Razorpay integration. Wraps the `com.razorpay:razorpay-java` SDK (`com.razorpay.RazorpayClient`).
- **Why it exists** — talks to Razorpay for real. Money on the wire is in **paise** (1 INR = 100 paise); we keep `BigDecimal` ₹ throughout the domain. This class is the **single conversion point** for that boundary — `toPaise(BigDecimal rupees)` uses `RoundingMode.HALF_EVEN` (banker's rounding).
- **Spring annotations:**
  - `@Component` — registers the class as a Spring-managed bean so the `RazorpayClient` interface is implemented by exactly one bean.
  - `@Override` (on every method) — compile-time check that we satisfy the `RazorpayClient` contract.
  - `@Value("${razorpay.key_id}")`, `@Value("${razorpay.key_secret}")`, `@Value("${razorpay.webhook_secret}")` (on the constructor parameters) — inject the Razorpay credentials from `application.yml`. Defaults to `rzp_test_xxxxxxxxxxxxxx` so the service boots without a real `.env`.
- **What it calls:**
  - `com.razorpay.RazorpayClient` (the SDK) — `razorpay.orders.create(...)`, `razorpay.payments.refund(...)`, `Utils.verifyWebhookSignature(...)`.
  - `com.razorpay.Order`, `com.razorpay.Refund` (response types from the SDK).
  - `org.json.JSONObject` (the SDK's request body type).
- **What calls it** — `PaymentCommandHandler` (constructor-injected as `RazorpayClient`).
- **Configuration keys:**
  - `razorpay.key_id` — the `rzp_test_***` key from the Razorpay dashboard.
  - `razorpay.key_secret` — the secret for the key.
  - `razorpay.webhook_secret` — the secret Razorpay uses to sign webhook bodies (HMAC-SHA256).
- **Notable anomaly:** the SDK's `order.get("amount")` can return either `Integer` or `Long` depending on the amount; the class casts via `Number.longValue()` to avoid `ClassCastException`.

### 3.5 `api/RazorpayClientFallback.java`

- **What it is** — a **reference implementation** of `RazorpayClient` that throws `PaymentGatewayException` instead of calling Razorpay. It is **not wired as a Spring bean** (no `@Component`).
- **Why it exists** — kept as documentation of "what to do when the circuit opens: throw a domain exception, don't fake success." The actual resilience strategy is **in `RazorpayClientImpl`** via `try/catch` and the Resilience4j config block in `application.yml` (`samato.resilience4j.razorpay-client`).
- **Spring annotations:** none (deliberately — see javadoc on `RazorpayClient`).
- **What it calls** — only the `PaymentGatewayException` inner class of `RazorpayClientImpl`.
- **What calls it** — nothing today. The inventory flags this as a known anomaly: it is **the only `RazorpayClientFallback`-using class**, but it isn't even instantiated. If a future engineer wants a real circuit-breaker fallback, they have the template here.
- **Configuration keys** — none.

### 3.6 `api/WebhookSignatureVerifier.java`

- **What it is** — a **static utility** for HMAC-SHA256 signature verification, written from scratch using `javax.crypto.Mac`.
- **Why it exists** — Razorpay's SDK ships `Utils.verifyWebhookSignature`, but the class javadoc gives two reasons for a hand-roll: (1) the SDK throws `RazorpayException` on internal errors, and we want a clean boolean; (2) constant-time comparison via `MessageDigest.isEqual` makes the security boundary auditable.
- **Spring annotations:** none (it's a `final` class with a `private` constructor — pure utility, not a bean).
- **What it calls** — `javax.crypto.Mac`, `javax.crypto.spec.SecretKeySpec`, `java.security.MessageDigest`.
- **What calls it** — currently nothing in this service. The `WebhookController` uses `RazorpayClient.verifyWebhookSignature` (which delegates to the SDK's `Utils`). **Anomaly:** the verifier is dead code as of this scan; the only call path for signature verification goes through the SDK via `RazorpayClientImpl.verifyWebhookSignature`. The static class is here for "belt-and-braces" direct use.
- **Configuration keys** — none.

### 3.7 `config/KafkaByteArrayConfig.java`

- **What it is** — a `@Configuration` class that defines **two beans** for the outbox: a `ProducerFactory<String, byte[]>` and a `KafkaTemplate<String, byte[]>`.
- **Why it exists** — the shared-kafka module ships a `KafkaTemplate<String, SpecificRecord>` for Avro + Schema Registry. The outbox, however, stores the **raw JSONB event payload** (the `event_data` column as UTF-8 bytes). So we need a second template that just forwards bytes without any serialization. This is one of the inventory's flagged anomalies: **the outbox publishes via a byte-array template, not the Avro template**.
- **Spring annotations:**
  - `@Configuration` — marks the class as a source of bean definitions.
  - `@Value("${spring.kafka.bootstrap-servers}")` — injects the broker address (`kafka:9094` in compose).
  - `@Bean` (on both factory and template methods) — registers the two beans.
- **What it calls** — `DefaultKafkaProducerFactory`, `KafkaTemplate`, plus Kafka producer config constants.
- **What calls it** — `OutboxPublisher` (constructor-injects `KafkaTemplate<String, byte[]>`).
- **Configuration keys:**
  - `spring.kafka.bootstrap-servers` — drives the broker address.
  - All Kafka producer properties (`acks=all`, `enable.idempotence=true`, `max.in.flight=5`, `compression.type=lz4`) are **hardcoded** here, not in `application.yml`. (Note: the per-service `application.yml` also sets these on the default Spring producer — that config doesn't reach this factory.)

### 3.8 `domain/Payment.java`

- **What it is** — the **Payment aggregate** (the state machine and the "thing" we're tracking).
- **Why it exists** — represents one customer's attempt to pay for one order. Has a lifecycle (`ORDER_CREATED → PAYMENT_INITIATED → CAPTURED → REFUND_INITIATED → REFUNDED` with branches to `FAILED` / `EXPIRED`). The class is the centrepiece of the service.
- **Spring annotations:** none on the class itself. (No `@Entity` — this is a **domain class, not a JPA entity**. The event store is the persistence model, not a JPA `payments` table.)
- **What it calls** — `Money`, `PaymentStatus`, `PaymentEvent`, `PaymentCommand` (all local types).
- **What calls it** — `PaymentRepository.load`, `PaymentCommandHandler.mutate`, `Snapshotter.maybeSnapshot`.
- **Configuration keys** — none.

**Key design points** (worth knowing even though they aren't annotations):
- **No setters.** All mutation goes through `apply(PaymentEvent)`, which is package-private. This is the **invariant enforcer** — the compiler makes it impossible to mutate state without appending an event.
- **Identity is set by the first event.** The first `RazorpayOrderCreated` populates `paymentId`, `orderId`, `customerId`, `currency`. A second `RazorpayOrderCreated` throws `IllegalStateException`.
- **State transitions are guarded.** Each `decide` method (e.g. `markCaptured`, `initiateRefund`) checks the current status before producing an event. `refund` requires `CAPTURED`; `markCaptured` rejects if already captured or refunded.
- **`drainUncommitted()`** moves the new events out of the aggregate so the command handler can persist them. Without this, the aggregate would carry the uncommitted events forever.

### 3.9 `domain/Money.java`

- **What it is** — a `record` value object: `(BigDecimal amount, String currency)`.
- **Why it exists** — money must be exact. `BigDecimal` with a canonical 2-decimal scale is the industry-standard answer; arithmetic is scale-aware, and there's no `0.30000000000000004` bug.
- **Spring annotations:** none. (Note: the inventory claims `@Embeddable` but the actual source has no JPA annotation. The record is used as a value object in events and DTOs, not as a JPA `@Embeddable`. **This is a discrepancy with the inventory** — the class is plain Java.)
- **What it calls** — only `java.math` and `java.util.Objects`.
- **What calls it** — `Payment`, `PaymentEvent.*`, `PaymentCommand.*`, `PaymentDtos`, `RazorpayClientImpl.toPaise`.
- **Configuration keys** — none.

### 3.10 `domain/PaymentStatus.java`

- **What it is** — a Java `enum` with seven values: `ORDER_CREATED`, `PAYMENT_INITIATED`, `CAPTURED`, `FAILED`, `REFUND_INITIATED`, `REFUNDED`, `EXPIRED`.
- **Why it exists** — the aggregate's state machine. The javadoc includes the ASCII state diagram.
- **Spring annotations:** none.
- **What it calls** — none.
- **What calls it** — `Payment` (current status), `PaymentView` (persisted status, as `@Enumerated(EnumType.STRING)`), `PaymentCommandHandler`, `PaymentProjector`.
- **Configuration keys** — none.

### 3.11 `domain/PaymentRepository.java`

- **What it is** — the **read side of the aggregate**. Knows how to load a `Payment` from the event store, with snapshot support.
- **Why it exists** — this is the only place that knows about both the snapshot store and the event store. The rest of the code asks for a `Payment` by id and doesn't care how it's reconstructed.
- **Spring annotations:**
  - `@Repository` — marks the class as a Spring-managed data-access bean; enables AOP-based exception translation.
- **What it calls:**
  - `PostgresEventStore` — `loadStream(aggregateId)`, `loadStreamAfter(aggregateId, version)`.
  - `Snapshotter` — `loadLatest(paymentId)`, `loadState(paymentId)`.
  - `Payment` (static factories `rehydrate`, `rehydrateFromSnapshot`).
- **What calls it** — `PaymentService`, `PaymentCommandHandler` (via `load`), `PaymentQueryService.loadAggregate`.
- **Configuration keys** — none.

### 3.12 `domain/command/PaymentCommand.java`

- **What it is** — a `sealed interface PaymentCommand` with four record implementations: `CreateRazorpayOrder`, `InitiatePayment`, `RefundPayment`, `MarkExpired`.
- **Why it exists** — commands are **intentions** (they can be rejected); events are **facts** (they cannot). Every command carries a `commandId` (for tracing through the event store) and a `paymentId` (for the aggregate).
- **Spring annotations:** none on the interface or records (plain Java).
- **What it calls** — `Money`, `UUID`.
- **What calls it** — `Payment` (decide methods take commands as parameters), `PaymentService` (creates them from HTTP requests), `PaymentCommandHandler` (consumes them).
- **Configuration keys** — none.

### 3.13 `domain/event/PaymentEvent.java`

- **What it is** — a `sealed interface PaymentEvent` with seven record implementations: `RazorpayOrderCreated`, `PaymentInitiated`, `PaymentCaptured`, `PaymentFailed`, `RefundInitiated`, `RefundCompleted`, `PaymentExpired`.
- **Why it exists** — every state change is an event. Events are immutable, append-only, and serve as both the audit log and the source from which aggregates are rebuilt.
- **Spring annotations:** none on the interface or records. (`PaymentEvent` is **the service-internal event**, separate from the **Kafka Avro events** in `shared-kafka/src/main/avro/`. The two type hierarchies are not connected — see §7 for the mapping.)
- **What it calls** — `Money`, `UUID`.
- **What calls it** — `Payment.apply` (the only mutator), `PostgresEventStore` (serialises/deserialises via `EventSerde`), `PaymentProjector` (projects them into the read model), `PaymentQueryService.loadEvents`.
- **Configuration keys** — none.

### 3.14 `eventstore/EventStoreEntry.java`

- **What it is** — a JPA `@Entity` mapping the **`events` table** (one row per event ever appended). The persistence model for the event store.
- **Why it exists** — the event store is the source of truth for state transitions. This class is what Hibernate reads/writes when we query or append events.
- **Spring annotations:**
  - `@Entity` — JPA-managed entity.
  - `@Table(name = "events")` — pins the table name to `events`.
  - `@Id` + `@GeneratedValue(strategy = GenerationType.IDENTITY)` — the `sequence_number` is a `BIGSERIAL` (the global monotonic ordering).
  - `@Column(name = "sequence_number", ...)` — pins column names explicitly so they match the migration SQL.
  - `@Type(JsonType.class)` + `@Column(name = "event_data", nullable = false, columnDefinition = "jsonb")` — the `JsonType` from **hypersistence-utils** is what makes Hibernate write a `String` to a PostgreSQL `jsonb` column (not a `text` column). Without it, the migration's `jsonb` type would clash with Hibernate's default.
  - `@Column(insertable = false, updatable = false)` on `createdAt` — lets the database default (`DEFAULT now()`) populate the column instead of JPA.
- **What it calls** — `PaymentEvent`, `EventSerde`, `hypersistence.utils.hibernate.type.json.JsonType`.
- **What calls it** — `EventStoreEntryRepository` (Spring Data JPA), `PostgresEventStore` (`EventStoreEntry.from(...)` factory), `PaymentProjector.apply` (reads the entry, uses the type discriminator to decide which projection to run).
- **Configuration keys** — none.

### 3.15 `eventstore/EventStoreEntryRepository.java`

- **What it is** — Spring Data JPA repository for `EventStoreEntry`. Three custom queries on top of the inherited `JpaRepository<EventStoreEntry, Long>` API.
- **Why it exists** — the event store's "find by aggregate" and "find by Razorpay id" lookups are the basis of every projection and webhook reconciliation.
- **Spring annotations:** none on the interface (Spring Data JPA figures it out at boot from the extends clause).
- **What it calls** — `EventStoreEntry`.
- **What calls it** — `PostgresEventStore`.
- **Configuration keys** — none.

The three custom queries:
- `findStreamByAggregateId(aggregateId)` — ordered by `version ASC`; used for replay.
- `findByRazorpayOrderId(razorpayOrderId)` — **native query** on the JSONB column: `WHERE event_data->>'razorpayOrderId' = :razorpayOrderId`. Used by the webhook handler to find the aggregate from a Razorpay-side id.
- `findLatestVersion(aggregateId)` — `COALESCE(MAX(version), 0)` so brand-new aggregates start at version 0 (next event is 1).

### 3.16 `eventstore/PostgresEventStore.java`

- **What it is** — the **append + load** API of the event store. Wraps `EventStoreEntryRepository` and `EventSerde`.
- **Why it exists** — this is the boundary between "domain" (where `Payment` lives) and "persistence" (where the events table lives). The `UNIQUE(aggregate_id, version)` constraint is the **optimistic concurrency safety net**: a concurrent writer that loses the race gets `DataIntegrityViolationException`, which we translate to `OptimisticLockException`.
- **Spring annotations:**
  - `@Component` — Spring-managed bean.
  - `@Transactional(propagation = Propagation.REQUIRES_NEW)` on `append(...)` — each batch of events is a fresh transaction. The caller's transaction is the "command" transaction; ours is the "append" transaction. We don't double-wrap.
- **What it calls:**
  - `EventStoreEntryRepository` — `findLatestVersion`, `saveAll`, `findStreamByAggregateId`, `findByRazorpayOrderId`, `findByRazorpayPaymentId`.
  - `EventSerde` — `toJson`, `fromJson`.
  - `EventStoreEntry.from(...)` (static factory).
  - `OptimisticLockException` (thrown on race).
  - `Payment.rehydrate(...)` (the rebuild from replay).
- **What calls it** — `PaymentRepository` (loadStream, loadStreamAfter), `PaymentCommandHandler` (append), `PaymentService.lookupPaymentId` (findByRazorpayOrderId / findByRazorpayPaymentId for webhook reconciliation), `PaymentQueryService.loadEvents` (loadStream).
- **Configuration keys** — none.

### 3.17 `eventstore/EventSerde.java`

- **What it is** — serialise/deserialise a `PaymentEvent` to/from a JSON string.
- **Why it exists** — separating the persistence format from the domain type means we can index by `event_type` (a `VARCHAR(80)` column) AND have the payload as a separate JSONB column. Jackson's `@JsonTypeInfo` polymorphism would embed the discriminator in the payload and we don't want that.
- **Spring annotations:**
  - `@Component` — Spring-managed bean.
- **What it calls** — `ObjectMapper` (injected), all seven `PaymentEvent` record types (the switch in `fromJson`).
- **What calls it** — `EventStoreEntry.from(...)` (calls `toJson`), `PostgresEventStore.loadStream` / `loadStreamAfter` (calls `fromJson`).
- **Configuration keys** — none.

### 3.18 `eventstore/OptimisticLockException.java`

- **What it is** — a `RuntimeException` with `aggregateId`, `expectedVersion`, `actualVersion` fields. **Anomaly:** the inventory says this is in `domain/`; the actual file is in `eventstore/`. The `package com.samato.paymentservice.eventstore` declaration confirms it.
- **Why it exists** — thrown by `PostgresEventStore.append` when a concurrent writer beat us. The `expectedVersion` and `actualVersion` fields let the caller decide what to do (retry, surface a 409 to the user).
- **Spring annotations:** none.
- **What it calls** — only `RuntimeException` (the parent).
- **What calls it** — `PostgresEventStore.append`.
- **Configuration keys** — none.

### 3.19 `eventstore/snapshot/PaymentSnapshot.java`

- **What it is** — a JPA `@Entity` mapping the **`payment_snapshots`** table.
- **Why it exists** — without snapshots, a hot wallet (10 000+ events) would replay all events on every load. With snapshots every 50 events, worst-case replay is ~50 events.
- **Spring annotations:**
  - `@Entity` — JPA-managed.
  - `@Table(name = "payment_snapshots")` — pin table name.
  - `@Id` on `paymentId` — snapshots are keyed by the aggregate id (one snapshot per aggregate, replaced in place).
  - `@Type(JsonType.class)` + `@Column(name = "snapshot_data", nullable = false, columnDefinition = "jsonb")` — same JSONB pattern as `events.event_data`.
  - `@Column(insertable = false, updatable = false)` on `createdAt` — DB default populates it.
- **What it calls** — `hypersistence.utils.hibernate.type.json.JsonType`.
- **What calls it** — `PaymentSnapshotRepository` (Spring Data JPA), `Snapshotter`.
- **Configuration keys** — none.

### 3.20 `eventstore/snapshot/PaymentSnapshotRepository.java`

- **What it is** — a near-empty Spring Data JPA repository (`JpaRepository<PaymentSnapshot, UUID>`). No custom queries — we just use the inherited `findById` and `save`.
- **Spring annotations:** none (Spring Data JPA from the extends clause).
- **What calls it** — `Snapshotter`.
- **Configuration keys** — none.

### 3.21 `eventstore/snapshot/Snapshotter.java`

- **What it is** — writes a snapshot if the payment's version is a multiple of N (default 50, configurable).
- **Why it exists** — bounds the replay cost. The snapshot is a serialised `SnapshotState` record; on load, the repository hydrates the `Payment` from the snapshot and then replays events strictly **after** the snapshot's version.
- **Spring annotations:**
  - `@Component` — Spring-managed.
  - `@Transactional(propagation = Propagation.REQUIRES_NEW)` on `maybeSnapshot` — snapshot is a fresh transaction (so a snapshot write doesn't pollute the command's transaction).
  - `@Value("${samato.snapshot.every-n-events:50}")` on the constructor parameter — injects the snapshot interval; default 50.
- **What it calls:**
  - `PaymentSnapshotRepository` — `findById`, `save`.
  - `ObjectMapper` — `writeValueAsString`, `readValue`.
  - `Payment.getVersion`, `getPaymentId`, `getOrderId`, etc. (to build the snapshot).
  - The inner `SnapshotState` record.
- **What calls it** — `PaymentRepository.load` (calls `loadLatest` and `loadState`), `PaymentCommandHandler.mutate` (calls `maybeSnapshot` after appending).
- **Configuration keys:**
  - `samato.snapshot.every-n-events` — default 50.

### 3.22 `idempotency/IdempotencyGuard.java`

- **What it is** — the **idempotency wrapper**. `executeOnce(commandType, key, aggregateId, resultType, work)` runs the supplier **at most once** for a given `(command_type, key)` pair; on retry, returns the cached result.
- **Why it exists** — the order-service saga retries HTTP calls. We must not create two Razorpay orders for the same logical request. The `processed_commands` table catches the duplicate by `(command_type, key)`.
- **Spring annotations:**
  - `@Component` — Spring-managed.
  - `@Transactional(propagation = Propagation.REQUIRES_NEW)` on `recordResult(...)` — the cached result write is a fresh transaction so the supplier's transaction (which might already be committed) doesn't double-wrap.
- **What it calls:**
  - `ProcessedCommandRepository` — `findByCommandTypeAndKey`, `save`.
  - `ObjectMapper` — `writeValueAsString`, `readValue`.
- **What calls it** — `PaymentService.createRazorpayOrder`, `PaymentService.refund` (both wrap their command logic in `executeOnce`).
- **Configuration keys** — none.

**Note on the deterministic UUID:** for webhooks, the aggregate id is not known at dedup time. `deriveAggregateId(key)` builds a version-5 UUID from a SHA-1 hash of the key, just to satisfy the `NOT NULL` column constraint. This is documented in the javadoc.

### 3.23 `idempotency/ProcessedCommand.java`

- **What it is** — a JPA `@Entity` mapping the **`processed_commands`** table.
- **Why it exists** — the dedup table. The `UNIQUE(command_type, key)` constraint is the **dedup mechanism**; the `result_body` is the cached HTTP response.
- **Spring annotations:**
  - `@Entity` — JPA-managed.
  - `@Table(name = "processed_commands")` — pin name.
  - `@Id` on `id` (UUID).
  - `@Column(insertable = false, updatable = false)` on `createdAt` — DB default.
- **What it calls** — only Java stdlib.
- **What calls it** — `ProcessedCommandRepository`, `IdempotencyGuard.recordResult`.
- **Configuration keys** — none.

### 3.24 `idempotency/ProcessedCommandRepository.java`

- **What it is** — Spring Data JPA repo with one custom finder: `findByCommandTypeAndKey(String, String)`.
- **What it calls** — `ProcessedCommand`.
- **What calls it** — `IdempotencyGuard`.
- **Configuration keys** — none.

### 3.25 `outbox/OutboxEvent.java`

- **What it is** — a JPA `@Entity` mapping the **`outbox_events`** table.
- **Why it exists** — the transactional outbox. The trick that makes "DB write + Kafka publish" atomic without 2PC: append a row in the same transaction as the business write; a poller drains it.
- **Spring annotations:**
  - `@Entity` — JPA-managed.
  - `@Table(name = "outbox_events")` — pin name.
  - `@Id` on `id` (UUID).
  - `@Column(name = "payload", nullable = false, columnDefinition = "bytea")` — the wire payload is `bytea` (the JSONB event body as UTF-8 bytes).
  - `@Column(name = "sent_at")` — NULL = unsent, non-NULL = sent (the marker field).
  - `@Column(insertable = false, updatable = false)` on `createdAt` — DB default.
- **What it calls** — only Java stdlib.
- **What calls it** — `OutboxEventRepository`, `OutboxPublisher`, `PaymentCommandHandler.enqueueOutbox` (creates a new `OutboxEvent`).
- **Configuration keys** — none.

### 3.26 `outbox/OutboxEventRepository.java`

- **What it is** — Spring Data JPA repo with one custom finder: `findUnsent(Pageable)` returning the next batch of unsent events ordered by `createdAt ASC`.
- **What it calls** — `OutboxEvent`.
- **What calls it** — `OutboxPublisher.publishPending`.
- **Configuration keys** — none.

### 3.27 `outbox/OutboxPublisher.java`

- **What it is** — a `@Component` with a `@Scheduled` method that polls `outbox_events` and publishes unsent rows to Kafka.
- **Why it exists** — drains the outbox. Uses `.get(5, TimeUnit.SECONDS)` for **synchronous** publish (so failures are visible immediately; the row stays unsent and is retried next tick). Production-grade would use a callback; this implementation is a deliberate simplicity choice.
- **Spring annotations:**
  - `@Component` — Spring-managed.
  - `@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")` — poll every 500 ms (configurable).
  - `@Transactional` on `publishPending` — the whole batch is in one transaction; on failure we exit early and retry next tick.
- **What it calls:**
  - `OutboxEventRepository` — `findUnsent(PageRequest.of(0, 50))`.
  - `KafkaTemplate<String, byte[]>` (the bean from `KafkaByteArrayConfig`) — `send(topic, key, payload).get(5, TimeUnit.SECONDS)`.
  - `OutboxEvent.setSentAt(OffsetDateTime.now())` on success.
- **What calls it** — Spring's scheduler, every 500 ms.
- **Configuration keys:**
  - `samato.outbox.poll-ms` — default 500 ms.
  - `BATCH_SIZE` is hardcoded to 50.

### 3.28 `projection/PaymentView.java`

- **What it is** — the JPA `@Entity` mapping the **`payment_view`** table. The CQRS read model.
- **Why it exists** — denormalised view: has both the order id (for cross-service lookup) and the Razorpay-side ids (for webhook reconciliation). Written by the projector, read by the REST API.
- **Spring annotations:**
  - `@Entity` — JPA-managed.
  - `@Table(name = "payment_view", indexes = { @Index(name = "idx_pv_order", columnList = "order_id") })` — pin name and create the `order_id` index. Note: the migration also creates `idx_pv_razorpay_order` and `idx_pv_customer` that aren't declared here (the migration is the source of truth for indexes).
  - `@Id` on `paymentId` (UUID).
  - `@Enumerated(EnumType.STRING)` on `status` — stores the enum as its name string, not the ordinal.
  - `@Column(insertable = false, updatable = false)` on `updatedAt` — DB default (`DEFAULT now()`) populates it.
- **What it calls** — only Java stdlib and the local `PaymentStatus` enum.
- **What calls it** — `PaymentViewRepository` (Spring Data), `PaymentProjector` (the only writer), `PaymentQueryService` (read).
- **Configuration keys** — none.

### 3.29 `projection/PaymentViewRepository.java`

- **What it is** — Spring Data JPA repo with two custom finders: `findByRazorpayOrderId(String)` and `findByOrderId(UUID)`.
- **What it calls** — `PaymentView`.
- **What calls it** — `PaymentQueryService`.
- **Configuration keys** — none.

### 3.30 `projection/PaymentProjector.java`

- **What it is** — the **CQRS projector**. Subscribes (in-process) to a list of newly-appended `EventStoreEntry`s and updates the read model.
- **Why it exists** — runs in the **same transaction** as the event appends. The read model and the event store are atomically consistent — no "is the view up to date?" question.
- **Spring annotations:**
  - `@Component` — Spring-managed.
- **What it calls:**
  - `PaymentViewRepository` — `findById`, `save`.
  - `EventSerde` — `mapper().readValue(...)` to deserialise each event into its typed record.
  - `PaymentView` setters (the projector is the only writer; the setters are deliberately public on a read model).
- **What calls it** — `PaymentCommandHandler.mutate(...)` calls `projector.apply(entries)` after the event store append.
- **Configuration keys** — none.

**Anomaly:** the `JsonFields` inner record is dead code (the javadoc admits it). It was a debugging helper; the actual deserialisation goes through `EventSerde.mapper().readValue`.

### 3.31 `query/PaymentQueryService.java`

- **What it is** — the read-side service. Two flavours of read: (1) from the CQRS read model (`PaymentView`) — fast, simple; (2) from the event store with replay — slow but always correct, used for time-travel.
- **Why it exists** — separates "what's the current status?" (read-model) from "what was the state at version N?" (event store).
- **Spring annotations:**
  - `@Service` — Spring-managed.
  - `@Transactional(readOnly = true)` on every method — the read methods are all in a read-only transaction (a hint to JDBC / Hibernate for optimisations).
- **What it calls:**
  - `PaymentViewRepository` — `findById`, `findByOrderId`, `findByRazorpayOrderId`.
  - `PaymentRepository` — `load` (returns a rehydrated `Payment` aggregate).
  - `PostgresEventStore` — `loadStream` (returns raw events for time-travel).
- **What calls it** — `PaymentController` (via `queryService.findById`, `.findByOrderId`, `.loadAggregate`, `.loadEvents`, `.balanceAt`).
- **Configuration keys** — none.

### 3.32 `security/SecurityConfig.java`

- **What it is** — the **default** Spring Security filter chain: JWT for everything **except** the webhook path (which is configured in `RazorpayWebhookSecurityConfig`).
- **Why it exists** — locks down the REST API. JWT validation uses the `auth-service`'s JWKS endpoint (configured via `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`).
- **Spring annotations:**
  - `@Configuration` — source of bean definitions.
  - `@EnableWebSecurity` — turns on Spring Security for this app.
  - `@Bean` on `apiFilterChain` — registers the chain.
- **What it calls** — `HttpSecurity` (fluent builder).
- **What calls it** — Spring Security's filter chain chooser.
- **Configuration keys:**
  - `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` — set from `AUTH_JWKS_URI` env var (compose: `http://auth-service:9000/.well-known/jwks.json`).
  - `management.endpoints.web.exposure.include` — `actuator/**` is `permitAll`; the rest requires JWT.

### 3.33 `security/RazorpayWebhookSecurityConfig.java`

- **What it is** — a **second**, higher-precedence filter chain for the Razorpay webhook path.
- **Why it exists** — Razorpay doesn't have our JWT. The webhook is `permitAll` at the Spring layer; the controller does the real check (HMAC signature verification). A `401` from this chain means the signature is bad.
- **Spring annotations:**
  - `@Configuration` — source of beans.
  - `@EnableWebSecurity` — also a web-security config.
  - `@Bean` + `@Order(Ordered.HIGHEST_PRECEDENCE)` on `webhookFilterChain` — makes this chain match BEFORE the default JWT chain. Combined with the `securityMatcher("/api/payments/webhooks/**")` it ensures webhook requests never see the JWT filter.
- **What it calls** — `HttpSecurity`.
- **What calls it** — Spring Security's filter chain chooser.
- **Configuration keys** — none.

### 3.34 `service/PaymentCommandHandler.java`

- **What it is** — the **command-side handler**. This is where CQRS writes happen: load → decide → append → project → maybe snapshot → write to outbox. All in one DB transaction.
- **Why it exists** — the only place that mutates the `Payment` aggregate. Every public method is `@Transactional`. The decider function pattern (`mutate(paymentId, commandId, decider)`) avoids one-off methods for every command.
- **Spring annotations:**
  - `@Component` — Spring-managed.
  - `@Transactional` on every public method — wraps the load + decide + append + project + outbox + snapshot in one transaction.
- **What it calls:**
  - `PaymentRepository` — `load`.
  - `PostgresEventStore` — `append`.
  - `PaymentProjector` — `apply(entries)`.
  - `Snapshotter` — `maybeSnapshot(p)`.
  - `RazorpayClient` — `createOrder`, `refund`.
  - `OutboxEventRepository` — `save(...)` (in `enqueueOutbox`).
- **What calls it** — `PaymentService` (the orchestrator), `PaymentService.dispatchWebhook` (for the four webhook-derived commands: `handleMarkCaptured`, `handleMarkFailed`, `handleMarkRefundCompleted`, `handleMarkExpired`).
- **Configuration keys** — none.

**The `topicFor(eventType)` switch** maps domain event types to Kafka topic names. This is the one place where the local event type and the Kafka topic are coupled. **Anomaly:** the topics `samato.payment.created`, `samato.payment.refund.initiated`, and `samato.payment.expired` are published by the outbox but **have no corresponding `.avsc` schema in `shared-kafka/src/main/avro/`** — the payload is the raw JSONB bytes from the event store, not an Avro-encoded record. Downstream consumers of these topics must be JSON parsers, not Avro.

### 3.35 `service/PaymentService.java`

- **What it is** — the **top-level orchestrator** that controllers and webhooks call. Wraps the command handler in idempotency and dispatches webhooks to the right handler.
- **Why it exists** — owns the "request came in" layer. Knows about HTTP commands (deduped on `Idempotency-Key`) and webhooks (deduped on Razorpay's event id).
- **Spring annotations:**
  - `@Service` — Spring-managed.
- **What it calls:**
  - `PaymentCommandHandler` — `handleCreateRazorpayOrder`, `handleRefund`, `handleMarkCaptured`, `handleMarkFailed`, `handleMarkRefundCompleted`.
  - `IdempotencyGuard` — `executeOnce(...)`, `findReplay(...)`, `recordResult(...)`.
  - `PostgresEventStore` — `findAggregateIdByRazorpayOrderId`, `findAggregateIdByRazorpayPaymentId` (to map a Razorpay id to our internal aggregate id).
  - `ObjectMapper` — `readTree` (parse the webhook JSON).
- **What calls it** — `PaymentController` (createOrder, refund), `WebhookController.handle(...)` (raw body + parsed event).
- **Configuration keys** — none.

**`handleWebhook` anomaly:** on failure, we do **NOT** mark the webhook as processed. The `recordResult` only runs on success. This is the standard webhook pattern — Razorpay retries on non-2xx, so a transient failure means a retry; we shouldn't burn the dedup key on a failed processing.

### 3.36 `web/PaymentController.java`

- **What it is** — the REST surface for the payment service (excluding the webhook). Six endpoints, all under `/api/payments`.
- **Why it exists** — converts HTTP requests into commands and queries. **Anomaly:** the inventory says `@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")` is on every method, but the actual source has **no `@PreAuthorize` annotations**. The security is "any authenticated user" via `SecurityConfig` (the default chain requires `authenticated()` but does not restrict by role). The inventory appears to be aspirational or out of date.
- **Spring annotations:**
  - `@RestController` — marks the class as a controller whose methods return JSON bodies (or `ResponseEntity`).
  - `@RequestMapping("/api/payments")` — base path for all six endpoints.
  - `@PostMapping`, `@GetMapping` — HTTP method + sub-path.
  - `@RequestHeader("Idempotency-Key")` — required on the two write endpoints. The controller rejects blank keys with `400`.
  - `@Valid` + `@RequestBody` — triggers Jakarta Bean Validation (`@NotNull`, `@DecimalMin`, `@Pattern`) on the request DTOs.
  - `@PathVariable` — extracts `{id}`, `{orderId}`, `{version}` from the URL.
- **What it calls:**
  - `PaymentService` — `createRazorpayOrder`, `refund`.
  - `PaymentQueryService` — `findById`, `findByOrderId`, `loadEvents`, `loadAggregate`, `balanceAt`.
  - `PaymentDtos.*` — request and response shapes.
  - `PaymentCommand.CreateRazorpayOrder`, `PaymentCommand.RefundPayment` — built from the DTOs.
- **What calls it** — the api-gateway routes `/api/payments/**` here; `order-service` via Feign (`PaymentClient`).
- **Configuration keys** — none.

### 3.37 `web/WebhookController.java`

- **What it is** — Razorpay's webhook receiver. POSTs to `/api/payments/webhooks/razorpay`.
- **Why it exists** — Razorpay sends the **result** of a payment asynchronously (the customer might never return to the hosted checkout). Without this, we would never know a payment was captured.
- **Spring annotations:**
  - `@RestController` — JSON-by-default.
  - `@RequestMapping("/api/payments/webhooks")` — base path.
  - `@PostMapping("/razorpay")` — POST endpoint.
  - `@RequestHeader(value = "X-Razorpay-Signature", required = false)` — the HMAC signature header. Required at the application level (we 401 if missing or bad).
  - `@RequestBody String rawBody` — we need the **raw body as a String** (not parsed JSON) because HMAC verification requires the exact bytes Razorpay signed. Parsing to a DTO and re-serialising would change whitespace and break the signature.
- **What it calls:**
  - `RazorpayClient.verifyWebhookSignature(rawBody, signature)` — HMAC check via the SDK.
  - `ObjectMapper.readTree(rawBody)` — parse the body after verification.
  - `PaymentService.handleWebhook(rawBody, event)` — dispatch to the right handler.
- **What calls it** — Razorpay's servers, on every payment/refund event.
- **Configuration keys** — none.

### 3.38 Application config (`application.yml` + `bootstrap.yml`)

`bootstrap.yml` is minimal — it sets `spring.application.name`, `spring.profiles.active`, and the Spring Cloud Config URI (with `fail-fast: false` so local dev works without the config service). The real configuration is in `application.yml`.

Every key in `application.yml`, with its effect:

| Key | Default / Value | Effect |
|---|---|---|
| `spring.application.name` | `samato-payment-service` | Eureka service name, the `instance-id` suffix, and the topic prefix. |
| `spring.profiles.active` | `${SPRING_PROFILES_ACTIVE:default}` | No-op unless overridden; used to switch between `dev` and `prod` style configs. |
| `spring.config.import` | `optional:configserver:http://localhost:8888` | Optional import from config-service. `optional:` means missing config-service doesn't crash startup. |
| `spring.cloud.config.enabled` | `false` | Disables Spring Cloud Config client for local dev; the config-service is optional. |
| `spring.datasource.url` | `${POSTGRES_URL:jdbc:postgresql://localhost:5432/payment_service}` | JDBC URL. In compose, the env var points at `jdbc:postgresql://postgres:5432/payment_service`. |
| `spring.datasource.username` | `${POSTGRES_USER:fd}` | DB user. |
| `spring.datasource.password` | `${POSTGRES_PASSWORD:fd}` | DB password. |
| `spring.datasource.hikari.maximum-pool-size` | `20` | Connection pool upper bound. |
| `spring.datasource.hikari.minimum-idle` | `5` | Connection pool lower bound (kept warm). |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate validates the schema matches the entities at startup; it does NOT create/alter tables. Flyway does the schema work. |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | All `TIMESTAMPTZ` reads/writes go through UTC. |
| `spring.jpa.properties.hibernate.format_sql` | `false` | Less noise in logs. |
| `spring.flyway.enabled` | `true` | Flyway runs on startup. |
| `spring.flyway.locations` | `classpath:db/migration` | Where the migration files live. |
| `spring.flyway.baseline-on-migrate` | `true` | Allows migrating an existing DB without errors. |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `${AUTH_JWKS_URI:http://localhost:9000/.well-known/jwks.json}` | Where Spring fetches the JWKS to verify JWTs. In compose, points at `auth-service`. |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP:localhost:9092}` | Kafka broker. In compose, `kafka:9094` (the INTERNAL listener). |
| `spring.kafka.producer.key-serializer` | `StringSerializer` | Keys are strings. |
| `spring.kafka.producer.value-serializer` | `ByteArraySerializer` | Values are raw bytes. |
| `spring.kafka.producer.acks` | `all` | Wait for all in-sync replicas before ack. |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Exactly-once per partition. |
| `spring.kafka.producer.properties.max.in.flight.requests.per.connection` | `5` | Idempotence allows up to 5. |
| `spring.kafka.producer.properties.compression.type` | `lz4` | Wire compression. |
| `server.port` | `8084` | HTTP listen port. |
| `server.error.include-message` | `always` | Always include the message in error bodies. |
| `server.error.include-binding-errors` | `always` | Always include validation errors. |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` | Actuator endpoints exposed. |
| `management.endpoint.health.show-details` | `when-authorized` | Only show details to authenticated users. |
| `management.tracing.sampling.probability` | `1.0` | Trace every request. |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | Eureka server. In compose, `http://discovery-service:8761/eureka/`. |
| `eureka.instance.instance-id` | `${spring.application.name}:${server.port}` | Stable per-pod instance id. |
| `eureka.instance.prefer-ip-address` | `true` | Register the IP, not the hostname. |
| `razorpay.key_id` | `${RAZORPAY_KEY_ID:rzp_test_xxxxxxxxxxxxxx}` | Razorpay test key. |
| `razorpay.key_secret` | `${RAZORPAY_KEY_SECRET:placeholder_secret_xxxxxxxxxxxxxx}` | Razorpay test secret. |
| `razorpay.webhook_secret` | `${RAZORPAY_WEBHOOK_SECRET:placeholder_webhook_secret_xxxxxxxx}` | Razorpay webhook signing secret. |
| `samato.outbox.poll-ms` | `500` | Outbox poller interval. |
| `samato.snapshot.every-n-events` | `50` | Snapshot every N events. |
| `samato.resilience4j.razorpay-client.circuit-breaker.failure-rate-threshold` | `50` | Open the circuit at 50% failures. |
| `samato.resilience4j.razorpay-client.circuit-breaker.wait-duration-in-open-state` | `10s` | Wait 10 s in OPEN before trying half-open. |
| `samato.resilience4j.razorpay-client.circuit-breaker.sliding-window-size` | `10` | Look at the last 10 calls. |
| `samato.resilience4j.razorpay-client.circuit-breaker.minimum-number-of-calls` | `5` | Need at least 5 calls to compute failure rate. |
| `samato.resilience4j.razorpay-client.retry.max-attempts` | `3` | Retry up to 3 times. |
| `samato.resilience4j.razorpay-client.retry.wait-duration` | `200ms` | Wait 200 ms between retries. |
| `logging.level.root` | `INFO` | Default log level. |
| `logging.level.com.samato.paymentservice` | `DEBUG` | Our own code is DEBUG. |
| `logging.pattern.console` | (custom with `%X{traceId:-} spanId=%X{spanId:-}`) | Inject the MDC keys (`traceId`, `spanId`) into every log line for distributed tracing. |

---

## 4. Endpoints (controllers)

### 4.1 `PaymentController` — `POST /api/payments/orders`

- **HTTP method:** `POST`
- **Path:** `/api/payments/orders`
- **Required headers:** `Authorization: Bearer <jwt>`, `Idempotency-Key: <string>` (required, non-blank).
- **Request body:** `PaymentDtos.CreatePaymentRequest`
  ```json
  {
    "orderId":    "11111111-1111-1111-1111-111111111111",
    "customerId": "22222222-2222-2222-2222-222222222222",
    "amount":     499.00,
    "currency":   "INR"
  }
  ```
- **Response:** `201 Created` with `PaymentDtos.PaymentResponse` (the freshly-initialised payment).
- **Downstream:** `PaymentService.createRazorpayOrder` → `IdempotencyGuard.executeOnce("CreateRazorpayOrder", key, ...)` → `PaymentCommandHandler.handleCreateRazorpayOrder` → `RazorpayClient.createOrder` → append `RazorpayOrderCreated` to event store → project to read model → enqueue outbox row.
- **Allowed callers:** any authenticated user (the actual `SecurityConfig` only requires `authenticated()`; the inventory's `@PreAuthorize` claims about role checks are **not present in the source**).
- **Example curl:**
  ```bash
  curl -X POST http://localhost:8084/api/payments/orders \
    -H "Authorization: Bearer $JWT" \
    -H "Idempotency-Key: order-charge-11111111-1111-1111-1111-111111111111" \
    -H "Content-Type: application/json" \
    -d '{
      "orderId":    "11111111-1111-1111-1111-111111111111",
      "customerId": "22222222-2222-2222-2222-222222222222",
      "amount":     499.00,
      "currency":   "INR"
    }'
  ```

### 4.2 `PaymentController` — `GET /api/payments/{id}`

- **HTTP method:** `GET`
- **Path:** `/api/payments/{id}`
- **Required headers:** `Authorization: Bearer <jwt>`.
- **Request body:** none.
- **Response:** `200 OK` with `PaymentDtos.PaymentResponse`.
- **Downstream:** `PaymentQueryService.findById` → `PaymentViewRepository.findById`.
- **Allowed callers:** any authenticated user.
- **Example curl:**
  ```bash
  curl http://localhost:8084/api/payments/33333333-3333-3333-3333-333333333333 \
    -H "Authorization: Bearer $JWT"
  ```

### 4.3 `PaymentController` — `GET /api/payments/by-order/{orderId}`

- **HTTP method:** `GET`
- **Path:** `/api/payments/by-order/{orderId}`
- **Required headers:** `Authorization: Bearer <jwt>`.
- **Request body:** none.
- **Response:** `200 OK` with `PaymentDtos.PaymentResponse`.
- **Downstream:** `PaymentQueryService.findByOrderId` → `PaymentViewRepository.findByOrderId`.
- **Allowed callers:** any authenticated user. Used by the order-service saga to look up the payment for an order.
- **Example curl:**
  ```bash
  curl http://localhost:8084/api/payments/by-order/11111111-1111-1111-1111-111111111111 \
    -H "Authorization: Bearer $JWT"
  ```

### 4.4 `PaymentController` — `GET /api/payments/{id}/events`

- **HTTP method:** `GET`
- **Path:** `/api/payments/{id}/events`
- **Required headers:** `Authorization: Bearer <jwt>`.
- **Request body:** none.
- **Response:** `200 OK` with `List<PaymentDtos.EventResponse>`.
- **Downstream:** `PaymentQueryService.loadEvents` → `PostgresEventStore.loadStream`.
- **Allowed callers:** any authenticated user. This is the **event log** for a payment — useful for debugging and audit.
- **Example curl:**
  ```bash
  curl http://localhost:8084/api/payments/33333333-3333-3333-3333-333333333333/events \
    -H "Authorization: Bearer $JWT"
  ```

### 4.5 `PaymentController` — `GET /api/payments/{id}/balance-at/{version}`

- **HTTP method:** `GET`
- **Path:** `/api/payments/{id}/balance-at/{version}`
- **Required headers:** `Authorization: Bearer <jwt>`.
- **Request body:** none.
- **Response:** `200 OK` with `PaymentDtos.BalanceAtResponse`.
- **Downstream:** `PaymentQueryService.balanceAt` → `loadAtVersion` (replay events up to and including `version`).
- **Allowed callers:** any authenticated user. This is the **time-travel** endpoint — "what was the state of this payment at version 3?"
- **Example curl:**
  ```bash
  curl http://localhost:8084/api/payments/33333333-3333-3333-3333-333333333333/balance-at/3 \
    -H "Authorization: Bearer $JWT"
  ```

### 4.6 `PaymentController` — `POST /api/payments/{id}/refunds`

- **HTTP method:** `POST`
- **Path:** `/api/payments/{id}/refunds`
- **Required headers:** `Authorization: Bearer <jwt>`, `Idempotency-Key: <string>` (required, non-blank).
- **Request body:** `PaymentDtos.RefundRequest`
  ```json
  { "amount": 499.00 }
  ```
- **Response:** `200 OK` with `PaymentDtos.PaymentResponse`.
- **Downstream:** `PaymentService.refund` → `IdempotencyGuard.executeOnce("RefundPayment", key, ...)` → `PaymentCommandHandler.handleRefund` → `RazorpayClient.refund` → append `RefundInitiated` to event store → project → enqueue outbox row. **The refund completes asynchronously** when Razorpay sends a `refund.processed` webhook (which calls `handleMarkRefundCompleted`).
- **Allowed callers:** any authenticated user. (Production: would restrict to `ADMIN` or the order-service saga. The inventory's `@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")` is **not in the actual source**.)
- **Example curl:**
  ```bash
  curl -X POST http://localhost:8084/api/payments/33333333-3333-3333-3333-333333333333/refunds \
    -H "Authorization: Bearer $JWT" \
    -H "Idempotency-Key: refund-33333333-3333-3333-3333-333333333333" \
    -H "Content-Type: application/json" \
    -d '{ "amount": 499.00 }'
  ```

### 4.7 `WebhookController` — `POST /api/payments/webhooks/razorpay`

- **HTTP method:** `POST`
- **Path:** `/api/payments/webhooks/razorpay`
- **Required headers:** `X-Razorpay-Signature: <hmac-sha256-hex>` (required at the application level; `permitAll` at Spring Security).
- **Request body:** raw JSON (the body must be the **exact bytes** Razorpay signed; do not parse and re-serialise before HMAC verification).
- **Response:** `200 OK` on success, `401 Unauthorized` on missing/bad signature, `500 Internal Server Error` on processing failure (Razorpay retries on non-2xx).
- **Downstream:** `RazorpayClient.verifyWebhookSignature` → `PaymentService.handleWebhook` → `dispatchWebhook(eventType, event)` (routes to `handleMarkCaptured`, `handleMarkFailed`, or `handleMarkRefundCompleted` based on the `event` field in the payload).
- **Allowed callers:** Razorpay's servers only (HMAC signature is the auth).
- **Example curl (signing with the test webhook secret):**
  ```bash
  # Pseudocode — the signature is HMAC-SHA256 of the body using the webhook secret.
  SECRET=placeholder_webhook_secret_xxxxxxxx
  BODY='{"id":"evt_test_001","event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_TEST","order_id":"order_TEST","amount":49900,"currency":"INR"}}}}'
  SIG=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')
  curl -X POST http://localhost:8084/api/payments/webhooks/razorpay \
    -H "X-Razorpay-Signature: $SIG" \
    -H "Content-Type: application/json" \
    --data "$BODY"
  ```

---

## 5. Database schema

Two Flyway migration files in `db/migration/`. The `baseline-on-migrate: true` setting means a non-empty schema can still be migrated.

### 5.1 `V1__init_event_store.sql`

Creates the **event store** and the **snapshot** table.

#### Table `events`

| Column | Type | Notes |
|---|---|---|
| `sequence_number` | `BIGSERIAL PRIMARY KEY` | Global monotonic counter. **Different** from `version` — the version is per-aggregate, the sequence is global. |
| `aggregate_id` | `UUID NOT NULL` | The payment id. |
| `aggregate_type` | `VARCHAR(50) NOT NULL` | Always `'Payment'` for this service. |
| `event_type` | `VARCHAR(80) NOT NULL` | One of `RazorpayOrderCreated`, `PaymentInitiated`, `PaymentCaptured`, `PaymentFailed`, `RefundInitiated`, `RefundCompleted`, `PaymentExpired`. |
| `event_data` | `JSONB NOT NULL` | Serialised event body (Jackson default for the record). |
| `version` | `INT NOT NULL` | Per-aggregate monotonic counter (1, 2, 3, ...). |
| `command_id` | `UUID NOT NULL` | The originating command's id (for tracing). |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | DB default. |

**Constraints:** `UNIQUE(aggregate_id, version)` — the optimistic-concurrency safety net.

**Indexes:**
- `idx_events_aggregate` on `(aggregate_id, version)` — the hot path "give me the stream for this payment".
- `idx_events_type` on `(event_type)` — operational queries ("all PaymentFailed events today").
- `idx_events_created` on `(created_at)` — projection catch-up (process events in insertion order).
- `idx_events_payload_gin` on `event_data jsonb_path_ops` — GIN index for "events where payload->>'orderId' = ?" queries.

**Writers:** `PostgresEventStore.append` (via `EventStoreEntryRepository.saveAll`).
**Readers:** `PostgresEventStore.loadStream`, `loadStreamAfter`, `findByRazorpayOrderId`, `findByRazorpayPaymentId`, `findLatestVersion`. The `EventStoreEntryRepository` exposes these as JPQL/native queries.

#### Table `payment_snapshots`

| Column | Type | Notes |
|---|---|---|
| `payment_id` | `UUID PRIMARY KEY` | The aggregate id. One snapshot per aggregate, replaced in place. |
| `version` | `INT NOT NULL` | The aggregate version at snapshot time. |
| `snapshot_data` | `JSONB NOT NULL` | The `SnapshotState` record serialised as JSON. |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | DB default. |

**Indexes:** `idx_snapshots_version` on `(payment_id, version)`.

**Writers:** `Snapshotter.maybeSnapshot` (every 50 events, in the same transaction as the event append).
**Readers:** `Snapshotter.loadLatest` / `loadState` (called by `PaymentRepository.load` to bound the replay).

### 5.2 `V2__processed_commands_and_views.sql`

Creates the **idempotency table**, the **CQRS read model**, and the **outbox table**.

#### Table `processed_commands`

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | |
| `command_type` | `VARCHAR(80) NOT NULL` | `CreateRazorpayOrder`, `RefundPayment`, `RazorpayWebhook`, ... |
| `key` | `VARCHAR(200) NOT NULL` | The `Idempotency-Key` header value (or the Razorpay event id for webhooks). |
| `aggregate_id` | `UUID NOT NULL` | The payment id (or a deterministic UUID for webhooks where the aggregate isn't known at dedup time). |
| `result_status` | `INT NOT NULL` | HTTP-ish (200, 201, 202, 422). |
| `result_body` | `TEXT` | Serialised response JSON (replayed on retry). |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | DB default. |

**Constraints:** `UNIQUE(command_type, key)` — the dedup mechanism. The key is scoped by command type so a customer could in theory reuse a key across two different command types.

**Indexes:** `idx_proc_cmd_aggregate` on `(aggregate_id)`.

**Writers:** `IdempotencyGuard.recordResult` (in a `REQUIRES_NEW` transaction).
**Readers:** `IdempotencyGuard.executeOnce` and `findReplay` (via `ProcessedCommandRepository.findByCommandTypeAndKey`).

#### Table `payment_view`

| Column | Type | Notes |
|---|---|---|
| `payment_id` | `UUID PRIMARY KEY` | |
| `razorpay_order_id` | `VARCHAR(40)` | Set on `RazorpayOrderCreated`. |
| `razorpay_payment_id` | `VARCHAR(40)` | Set on `PaymentCaptured` (or `PaymentFailed`). |
| `order_id` | `UUID NOT NULL` | The cross-service link. |
| `customer_id` | `UUID NOT NULL` | |
| `amount` | `NUMERIC(19,2) NOT NULL` | |
| `currency` | `VARCHAR(3) NOT NULL` | Always `'INR'` for the bible. |
| `status` | `VARCHAR(30) NOT NULL` | The `PaymentStatus` enum as a string. |
| `last_event_seq` | `BIGINT NOT NULL` | The global sequence number of the last event that touched this row. |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | DB default. |

**Indexes:**
- `idx_pv_razorpay_order` on `(razorpay_order_id)` — webhook lookup.
- `idx_pv_order` on `(order_id)` — "find payment for this order".
- `idx_pv_customer` on `(customer_id)` — "find all payments for this customer".

**Writers:** `PaymentProjector.apply` (only writer).
**Readers:** `PaymentViewRepository.findById`, `findByOrderId`, `findByRazorpayOrderId`. Used by every `GET` endpoint.

#### Table `outbox_events`

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID PRIMARY KEY` | |
| `aggregate_type` | `VARCHAR(100) NOT NULL` | Always `'Payment'` for this service. |
| `aggregate_id` | `UUID NOT NULL` | The payment id. |
| `topic` | `VARCHAR(100) NOT NULL` | The Kafka topic (`samato.payment.charged`, etc.). |
| `event_type` | `VARCHAR(200) NOT NULL` | The domain event type (`PaymentCaptured`, etc.). |
| `payload` | `BYTEA NOT NULL` | The wire payload — the JSONB `event_data` as UTF-8 bytes (NOT Avro). |
| `sent_at` | `TIMESTAMPTZ` | NULL = unsent, non-NULL = sent. The marker field. |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | DB default. |

**Indexes:** `idx_outbox_unsent` on `(sent_at) WHERE sent_at IS NULL` — **partial index**: only unsent rows are interesting.

**Writers:** `PaymentCommandHandler.enqueueOutbox` (in the same transaction as the event append).
**Readers:** `OutboxPublisher.publishPending` (via `OutboxEventRepository.findUnsent(Pageable)`).

---

## 6. Kafka integration

### Topics published

All six topics are published via the **transactional outbox**, not directly. The command handler appends a row to `outbox_events` in the same transaction as the event store append; a scheduled poller (`OutboxPublisher`) drains it.

The `PaymentCommandHandler.topicFor(eventType)` switch is the single mapping point:

| Domain event | Topic | Producer code path |
|---|---|---|
| `RazorpayOrderCreated` | `samato.payment.created` | `PaymentCommandHandler.handleCreateRazorpayOrder` → `enqueueOutbox` |
| `PaymentCaptured` | `samato.payment.charged` | `PaymentService.dispatchWebhook` → `handleMarkCaptured` → `enqueueOutbox` |
| `PaymentFailed` | `samato.payment.failed` | `PaymentService.dispatchWebhook` → `handleMarkFailed` → `enqueueOutbox` |
| `RefundInitiated` | `samato.payment.refund.initiated` | `PaymentService.refund` → `handleRefund` → `enqueueOutbox` |
| `RefundCompleted` | `samato.payment.refunded` | `PaymentService.dispatchWebhook` → `handleMarkRefundCompleted` → `enqueueOutbox` |
| `PaymentExpired` | `samato.payment.expired` | `PaymentService.handleMarkExpired` → `enqueueOutbox` |

**Key:** the `paymentId.toString()` (from `OutboxEvent.aggregateId`). All events for one payment go to the same partition (good for consumers that want per-aggregate ordering).

**Payload format:** the raw JSONB bytes from `event_data`. **Anomaly:** three of these topics (`samato.payment.created`, `samato.payment.refund.initiated`, `samato.payment.expired`) **do not have a corresponding `.avsc` file in `shared-kafka/src/main/avro/`**. They are documented in the inventory as "raw JSON bytes from event store; no Schema-Registry encoding." Downstream consumers must use a JSON deserialiser, not `KafkaAvroDeserializer`.

**Kafka template used:** `KafkaTemplate<String, byte[]>` (the bean from `KafkaByteArrayConfig`), with `acks=all`, `enable.idempotence=true`, `max.in.flight=5`, `compression.type=lz4`.

**Outbox poll interval:** `samato.outbox.poll-ms` (default `500` ms).
**Batch size:** hardcoded to 50 in `OutboxPublisher.BATCH_SIZE`.
**Send mode:** synchronous (`kafkaTemplate.send(...).get(5, TimeUnit.SECONDS)`); on failure the row is left unsent and retried next tick.

### Topics consumed

**None.** This service has no `@KafkaListener`. Inbound async is exclusively Razorpay webhooks (HTTP, with HMAC).

### Outbox table

`outbox_events` (see §5.2 for columns). The marker field is `sent_at` (NULL = unsent). The poller runs every 500 ms by default, reads up to 50 unsent rows ordered by `createdAt`, publishes each, then sets `sent_at`. If a send fails or times out, the row stays unsent and the next tick retries.

---

## 7. Common "if you change X, also update Y" notes

1. **If you add a new `PaymentEvent` record** to `domain/event/PaymentEvent.java`:
   - Add the new case in `EventSerde.fromJson` (the switch on `eventType`).
   - Add the new case in `Payment.apply(...)` (the switch on the event).
   - Add the new case in `PaymentProjector.applyOne(...)` (so the read model is updated).
   - Add the topic mapping in `PaymentCommandHandler.topicFor(...)`.
   - If you want a Kafka Avro schema, add a new `.avsc` file under `shared-kafka/src/main/avro/` and link the generated class here. (This service does NOT use the Avro template today; see the anomaly above.)

2. **If you add a new `PaymentCommand` record** to `domain/command/PaymentCommand.java`:
   - Add a new handler method in `PaymentCommandHandler` (with `@Transactional`).
   - If the command comes from HTTP, add the new endpoint in `PaymentController` and a new DTO in `PaymentDtos`.
   - If the command needs idempotency, wrap it in `IdempotencyGuard.executeOnce(...)` like the existing `createRazorpayOrder` and `refund`.

3. **If you change the event store schema** (add/remove a column in `events`):
   - Add a new migration file `V3__...sql` in `db/migration/`. Do NOT edit `V1` or `V2` — Flyway tracks the checksum.
   - Add the new column to `EventStoreEntry` (and the corresponding JPQL/native queries in `EventStoreEntryRepository`).
   - Update `EventSerde.toJson` / `fromJson` if the column is part of the event payload (it usually is, via `event_data`).

4. **If you add a new outbound Kafka topic:**
   - Add the topic to `infra/kafka` topic auto-creation config (compose sets `KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE=true` so it will be auto-created on first send).
   - If you want a Schema-Registry-encoded Avro payload, you will need a `KafkaTemplate<String, SpecificRecord>` from `shared-kafka` instead of the byte-array template. (Today we use the byte-array template because the payload is pre-serialised JSONB.)

5. **If you wire the `RazorpayClientFallback` as a real fallback** (the class exists but is not a bean):
   - You will need a `@Primary` qualifier and likely a `@ConditionalOnProperty` to avoid dual-bean errors. The fallback currently throws `PaymentGatewayException`; the saga is expected to treat that as transient. See the javadoc on `RazorpayClient` for the design rationale.

6. **If you change the snapshot interval** (`samato.snapshot.every-n-events`):
   - Existing snapshots are NOT rebuilt. The interval only affects **new** snapshots. The current snapshot stays at the version it was created at; the loader replays events strictly after that version.
   - If you change it on a hot production wallet, plan a one-time backfill or accept the transient replay cost.

7. **If you change the JWT key source** (e.g. switch from `auth-service` to Keycloak):
   - Change `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`. No code change.

8. **If you add a `@PreAuthorize` to `PaymentController`** (the inventory claims it should be there):
   - The role names in the JWT come from the `roles` claim set by `auth-service`. Look at `auth-service`'s `JwtRolesCustomizer` for the exact spelling (`CUSTOMER`, `ADMIN`, `RESTAURANT_OWNER`, `DRIVER`, `SERVICE`).

9. **The `RazorpayClientFallback` class is dead code today** — it implements `RazorpayClient` but is not a Spring bean. If you start wiring it, the constructor of `RazorpayClientImpl` and the `@FeignClient(name="razorpay-mock")` in the inventory are red flags: the inventory is **wrong about `@FeignClient`** — this service does NOT use Feign for Razorpay. It uses the official `razorpay-java` SDK. The `RazorpayClient` interface exists for testability, not for Feign.

10. **The `WebhookSignatureVerifier` utility is dead code today** — `WebhookController` calls `RazorpayClient.verifyWebhookSignature` (which delegates to the SDK), not this static helper. If you want to use the constant-time comparison, replace the SDK call in `RazorpayClientImpl.verifyWebhookSignature`.

11. **The `JsonFields` inner record in `PaymentProjector` is dead code** — the projector actually uses `EventSerde.mapper().readValue` for deserialisation. The record was a debugging helper that never made it into the active path.

12. **Money scale is enforced at the constructor.** `Money` canonicalises to 2 decimal places with `RoundingMode.HALF_EVEN`. If you bypass the constructor (e.g. write directly to a JSONB column), you can end up with non-canonical amounts in the event store. The `EventStoreEntry.from(...)` factory always goes through `EventSerde.toJson(event)` which serialises whatever the aggregate has — so the invariant depends on the aggregate never holding non-canonical amounts.

---

## 8. See also

- **Per-service designer note** (interview-prep narration): [services/payment-service/docs/INTERVIEW-NOTES.md](../../services/payment-service/docs/INTERVIEW-NOTES.md) — the "why" of event sourcing, the PSP-first architecture, and the saga integration.
- **Use case walkthroughs:**
  - [Place an order](../use-cases/01-place-an-order.md) — calls `POST /api/payments/orders` from the order-service saga.
  - [Refund flow](../use-cases/04-refund-flow.md) — calls `POST /api/payments/{id}/refunds`.
  - [Auth flow](../use-cases/02-auth-flow.md) — explains the JWT verification that the `SecurityConfig` relies on.
- **Architecture guide:** [01-architecture-guide.md](../01-architecture-guide.md) — the system context, container map, and service-dependency diagram.
- **How auth works:** [02-how-auth-works.md](../02-how-auth-works.md) — the JWKS endpoint and the `auth-service` Spring Authorization Server.
- **Shared infrastructure:** [shared-and-kafka.md](shared-and-kafka.md) — `DomainException`, `GlobalExceptionHandler`, `CorrelationIdFilter`, `MdcKeys`, the Avro events in `shared-kafka/src/main/avro/`, and the Avro producer/consumer config (the Avro-specific events for orders and payments).
- **Repo root docs:** [ARCHITECTURE.md](../../ARCHITECTURE.md) (the C4 diagrams and the service-dependency map), [PROJECT-STATUS.md](../../PROJECT-STATUS.md) (what's running today), [RUN-THE-BIBLE.md](../../RUN-THE-BIBLE.md) (how to bring it up locally), [docs/INTERVIEW-CHEATSHEET.md](../INTERVIEW-CHEATSHEET.md) (cross-service interview talking points).
- **Glossary:** [00-glossary.md](../00-glossary.md) (plain-English definitions for every Spring annotation and microservice term used in this doc).
