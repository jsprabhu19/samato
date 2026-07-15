# order-service (port 8083)

> Plain-English purpose: The order-service is the heart of Samato. It owns the order lifecycle (a customer placing an order at a restaurant), runs a **Saga orchestrator** that coordinates work across multiple services (validate the restaurant, look up the menu, charge payment, confirm), records the work in a persistent outbox so events are never lost, and de-duplicates retries through an idempotency table. It demonstrates several patterns: **Saga (orchestration)**, **Transactional Outbox**, **Idempotency-Key replay**, **Server-side pricing** (don't trust the client), and **Polled resumability** (a poller drives a saga to completion if the request thread crashes).

## 1. Where it sits in the system

```
                              ┌─────────────────────────────┐
   Customer browser ──JWT──►  │  api-gateway (port 8080)    │
                              │  validates JWT, sets        │
                              │  X-User-Id, routes by path  │
                              └──────────────┬──────────────┘
                                             │ /api/orders/**
                                             ▼
                              ┌─────────────────────────────┐
                              │  order-service (port 8083)  │
                              │  ─────────────────────────  │
                              │  OrderController            │
                              │      │                       │
                              │      ▼                       │
                              │  OrderService (place)       │
                              │      │                       │
                              │      ├──►  Idempotency table │
                              │      │                       │
                              │      ├──►  SagaEngine.start  │
                              │      │      │                │
                              │      │      ├──► RestaurantClient ──HTTP──► restaurant-service (8082)
                              │      │      │                  GET /api/restaurants/{id}
                              │      │      │                  GET /api/restaurants/{id}/menu?ids=...
                              │      │      │
                              │      │      ├──► PaymentClient    ──HTTP──► payment-service   (8084)
                              │      │      │                  POST /api/payments/orders
                              │      │      │                  POST /api/payments/{id}/refunds
                              │      │      │
                              │      │      ▼
                              │      │   saga_steps / saga_instances
                              │      │   (each step in REQUIRES_NEW)
                              │      │
                              │      ▼
                              │  OutboxPublisher
                              │      │ append (in-txn) → outbox_events table
                              │      ▼
                              │  @Scheduled poller (500ms) → byte[] KafkaTemplate
                              │      │
                              │      ▼
                              │  Kafka topics:  samato.order.placed
                              │                samato.order.confirmed
                              │                samato.order.cancelled
                              │      │
                              │      └─►  (downstream consumers; no @KafkaListener
                              │           inside order-service itself in this scan)
                              │
                              │  SagaPoller (@Scheduled, 1000ms)
                              │      └──►  drives RUNNING sagas forward
                              │
                              │  ServiceTokenProvider
                              │      └──►  GET /api/auth/dev-token?email=...
                              │           (called once at startup so the
                              │            poller thread can make auth'd Feign calls)
                              └─────────────────────────────┘
                                             ▲
                                             │ registers with
                                             │
                              ┌──────────────┴──────────────┐
                              │  discovery-service (8761)   │
                              │  Eureka server              │
                              └─────────────────────────────┘
```

Key flows:

- **Place order** — Customer POSTs to `/api/orders`. The controller authenticates, hands the request to `OrderService.placeOrder`, which checks the idempotency table, then in one transaction writes the order, the outbox event, and the saga. The saga is then driven synchronously by the request thread, and a separate poller rescues any sagas that get stuck.
- **Cross-service calls** — Two Feign clients: `RestaurantClient` (for menu/availability) and `PaymentClient` (for charge/refund). Both go through Eureka to find their target service by name. JWT is captured from the inbound request and re-injected on outbound Feign calls via `FeignAuthForwarder`.
- **Events out** — Three Kafka topics. All three are written via the **outbox pattern** (rows inserted in the same DB transaction as the business write; a poller publishes them asynchronously).

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/order-service` |
| Port | 8083 |
| Database | PostgreSQL, database name `order_service` (JDBC URL `jdbc:postgresql://localhost:5432/order_service`, env override `POSTGRES_URL`) |
| Publishes topics | `samato.order.placed`, `samato.order.confirmed`, `samato.order.cancelled` |
| Consumes topics | (none — order-service does not listen on any Kafka topic in this scan) |
| REST endpoints | `POST /api/orders`, `GET /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/saga` |
| Depends on | `restaurant-service` (Feign, READ menu + restaurant), `payment-service` (Feign, READ/WRITE payment + refund), `auth-service` (HTTP via `RestClient`, dev-token fetch at startup; indirectly the JWK set via Spring Security resource-server), `discovery-service` (Eureka client), `shared` (errors, observability, validation), `shared-kafka` (Avro event classes), `config-service` (optional Spring Cloud Config import, currently disabled in local dev) |
| Owns | `orders`, `order_items`, `saga_instances`, `saga_steps`, `idempotency_records`, `outbox_events` |

## 3. File-by-file walkthrough

The service has 33 Java files. They are grouped by package below.

### `com.samato.orderservice` (root)

#### `OrderServiceApplication.java`

- **What it is** — the Spring Boot `main` class; the entry point that boots the JVM.
- **Why it exists** — every Spring Boot app needs one. It tells Spring where to scan for components and which cross-cutting features to enable.
- **Spring annotations** —
  - `@SpringBootApplication`: marks this as a Spring Boot app; turns on auto-configuration and component scanning starting from this package.
  - `@EnableDiscoveryClient`: registers the service with Eureka (the service-discovery server on port 8761) so api-gateway can find it by name.
  - `@EnableFeignClients`: turns on Spring Cloud OpenFeign so the `@FeignClient` interfaces (PaymentClient, RestaurantClient) get proxy implementations.
  - `@EnableScheduling`: turns on the `@Scheduled` poller machinery that drives the saga poller and the outbox publisher.
  - `@ComponentScan(basePackages = {"com.samato.orderservice","com.samato.shared","com.samato.sharedkafka"})`: extends component scanning beyond the default package so classes from the `shared` module (e.g., `DomainException`, `GlobalExceptionHandler`) and the `shared-kafka` module (Avro-generated event classes) get picked up.
- **What it calls** — `SpringApplication.run(...)` (the Spring Boot bootstrapper).
- **What calls it** — `java -jar order-service.jar` invokes `OrderServiceApplication.main`; the JVM is the caller.
- **Configuration keys it reads** — `spring.profiles.active`, `spring.config.import`, `spring.cloud.config.enabled` (via the `application.yml` it ships with).

### `com.samato.orderservice.api` (Feign clients, DTOs, auth-forwarder)

#### `OrderController` lives in `web/` — see §3.8 below.

#### `FeignAuthForwarder.java`

- **What it is** — a Spring `@Configuration` that provides two Feign `RequestInterceptor` beans: one that copies the inbound bearer token onto every outbound Feign call, and one that propagates the correlation id (from MDC) for tracing.
- **Why it exists** — When `OrderController.place` is invoked, the customer's JWT arrives in the `Authorization` header. The downstream services (`restaurant-service`, `payment-service`) are also Spring Security resource servers and will reject calls without a valid token. This class bridges "user token → service token passthrough" so the saga can call those services with the customer's identity.
- **Spring annotations** —
  - `@Configuration`: marks the class as a source of Spring beans.
  - `@Bean` (on `bearerTokenInterceptor` and `correlationIdInterceptor`): registers the two interceptors so Feign picks them up automatically.
- **What it calls** —
  - `ServiceTokenProvider.getServiceToken()` (when the per-request token isn't set, e.g., the poller thread).
  - `FeignCorrelationIdInterceptor` (from the `shared` module — see [shared-and-kafka.md](shared-and-kafka.md)).
- **What calls it** — Spring instantiates it during context startup. Spring Cloud OpenFeign pulls the `@Bean` interceptors and applies them to every Feign call. The `BearerTokenCaptureFilter` (inside `SecurityConfig`) calls the static `set` / `clear` methods around each HTTP request.
- **Configuration keys it reads** — none directly; the `ServiceTokenProvider` it depends on reads `samato.auth.base-url` and `samato.service-token.email`.

#### `OrderDtos.java`

- **What it is** — four Java `record` types that shape the wire format for the order endpoints: `OrderItemRequest`, `PlaceOrderRequest`, `OrderItemResponse`, `OrderResponse`. Validation annotations (`@NotNull`, `@Min`, `@NotEmpty`, `@Valid`, `@Size`) live here, NOT on the JPA entity.
- **Why it exists** — Decouples the JSON shape from the database schema. The entity can evolve (add a column) without breaking the API; the DTO can evolve (drop a deprecated field) without rewriting the DB.
- **Spring annotations** — none at the class level (`public final` only). The validation annotations on the record components are read by `MethodArgumentNotValidException` handler at request time.
- **What it calls** — `OrderStatus` (the enum imported from `com.samato.orderservice.domain`).
- **What calls it** — `OrderController` for the request/response types; `OrderService.placeOrder` for the request DTO; `OrderController.toResponse(Order)` builds `OrderResponse`.
- **Configuration keys** — none.

#### `PaymentClient.java`

- **What it is** — a Feign interface to the payment-service. Three methods: `createOrder` (saga's CHARGE_PAYMENT), `refund` (saga compensation), `get` (lookup).
- **Why it exists** — `SagaEngine.chargePayment` needs a typed HTTP client to the payment-service. Feign generates the proxy at runtime from the annotations; we never write HTTP code by hand.
- **Spring annotations** —
  - `@FeignClient(name = "samato-payment-service", fallbackFactory = PaymentClientFallback.class)`: the name is the Eureka service id; the `fallbackFactory` is invoked when the call fails (timeout, 5xx, connection refused).
  - `@PostMapping("/api/payments/orders")`, `@PostMapping("/api/payments/{id}/refunds")`, `@GetMapping("/api/payments/{id}")`: HTTP verb + path. `@RequestHeader("Idempotency-Key")` declares the header used for saga-step deduplication; `@PathVariable` and `@RequestBody` bind the rest.
- **What it calls** — `PaymentDtos` (the wire DTOs, defined right here in the same package).
- **What calls it** — `SagaEngine.chargePayment` (calls `createOrder`); `SagaEngine.compensateStep` for `CHARGE_PAYMENT` (calls `refund`).
- **Configuration keys** — `spring.cloud.openfeign.client.config.*` (timeouts, logger level) come from the shared `application.yml`; the fallback timing is governed by Spring Cloud OpenFeign defaults plus the resilience4j config in `application.yml` (although the `@CircuitBreaker` annotation is **not** present on the interface — see §7 gotchas).

#### `PaymentClientFallback.java`

- **What it is** — a `@Component` that implements `FallbackFactory<PaymentClient>`. When the Feign call fails, Spring Cloud invokes `create(Throwable cause)` and returns a stub `PaymentClient` whose every method throws `PaymentUnavailableException` wrapping the original cause.
- **Why it exists** — Tells the saga "the payment service is unreachable" via a domain exception rather than letting a raw Feign exception bubble up. The saga treats this as a transient failure; `SagaPoller` will retry on the next tick.
- **Spring annotations** —
  - `@Component`: makes it a Spring bean so Spring can autowire it into the Feign proxy generated from `PaymentClient`.
- **What it calls** — defines a static inner class `PaymentUnavailableException` (a plain `RuntimeException`).
- **What calls it** — Feign machinery, when a call to `samato-payment-service` fails.
- **Configuration keys** — none.

#### `PaymentDtos.java`

- **What it is** — three `record` types mirroring payment-service's wire format: `CreatePaymentRequest`, `RefundRequest`, `PaymentResponse`.
- **Why it exists** — the saga sends/receives these. They live here in order-service (not in shared-dtos) so order-service has no build-time dependency on payment-service's package.
- **Spring annotations** — none at the class level. Validation annotations (`@NotNull`, `@DecimalMin`, `@Pattern`) on `CreatePaymentRequest` enforce the wire contract.
- **What it calls** — nothing.
- **What calls it** — `PaymentClient` interface (the request/response types), `SagaEngine.chargePayment` (constructs `CreatePaymentRequest`), `SagaEngine.compensateStep` (constructs `RefundRequest`).
- **Configuration keys** — none.

#### `RestaurantClient.java`

- **What it is** — a Feign interface to restaurant-service. Two methods: `getRestaurant(UUID id)` and `getMenu(UUID id, String menuItemIds)`. The menu call uses a CSV `ids` query parameter so one HTTP call fetches only the items the order needs.
- **Why it exists** — The saga's `VALIDATE_RESTAURANT` and `VALIDATE_ITEMS` steps need to look up restaurant + menu info. A Feign client gives us typed return values and (via `fallbackFactory`) clean failure semantics.
- **Spring annotations** —
  - `@FeignClient(name = "samato-restaurant-service", fallbackFactory = RestaurantClientFallback.class)`.
  - `@GetMapping("/api/restaurants/{id}")`, `@GetMapping("/api/restaurants/{id}/menu")`, `@PathVariable`, `@RequestParam`.
- **What it calls** — `RestaurantDtos` (the wire DTOs).
- **What calls it** — `SagaEngine.validateRestaurant`, `SagaEngine.validateItems`.
- **Configuration keys** — Feign timeout/logger from shared config; resilience4j is configured by name in `application.yml` but the `@CircuitBreaker`/`@Retry` annotations are **not** on this interface (see §7).

#### `RestaurantClientFallback.java`

- **What it is** — `FallbackFactory<RestaurantClient>`. On failure, returns a stub whose methods throw `RestaurantUnavailableException` (a `DomainException` with code `RESTAURANT_UNAVAILABLE`, HTTP 503).
- **Why it exists** — distinguishes "the service is down" (transient) from "restaurant not found" (a 404 from restaurant-service). The saga treats the former as retryable.
- **Spring annotations** —
  - `@Component`.
- **What it calls** — `RestaurantUnavailableException`.
- **What calls it** — Feign machinery on call failure.
- **Configuration keys** — none.

#### `RestaurantDtos.java`

- **What it is** — three records: `RestaurantSummary`, `MenuItem`, `MenuResponse`. `RestaurantSummary.active` is the boolean the saga checks to decide if the restaurant is open for orders.
- **Why it exists** — wire contract mirror; lives in this service so order-service has no build-time dependency on restaurant-service.
- **Spring annotations** — none.
- **What it calls** — nothing.
- **What calls it** — `RestaurantClient` interface, `SagaEngine.validateRestaurant`, `SagaEngine.validateItems`.
- **Configuration keys** — none.

#### `RestaurantUnavailableException.java`

- **What it is** — extends `DomainException` (from the `shared` module). Maps to a 503 HTTP response with code `RESTAURANT_UNAVAILABLE`.
- **Why it exists** — Lets the saga signal "downstream is unreachable" without each call site having to know the HTTP code. `GlobalExceptionHandler` (in `shared`) converts the `DomainException` into a JSON error body.
- **Spring annotations** — none (inherits from `DomainException`).
- **What it calls** — `DomainException` super-constructor.
- **What calls it** — `RestaurantClientFallback.create(...)`.
- **Configuration keys** — none. See [shared-and-kafka.md](shared-and-kafka.md) for the `DomainException` definition.

#### `ServiceTokenProvider.java`

- **What it is** — a `@Component` that holds a long-lived JWT for use by background threads (the saga poller, scheduled jobs). On `ApplicationReadyEvent` it fires `fetchAtStartup`, which calls `GET /api/auth/dev-token?email=...` and parses out the `access_token` field with a hand-rolled substring search (no Jackson — see §7).
- **Why it exists** — the `SagaPoller` runs on a Spring scheduler thread. The per-request token in `FeignAuthForwarder.CURRENT_TOKEN` is null on that thread. Without a token, every Feign call from the poller returns 401, and no saga would ever resume. The service token gives the poller a way to authenticate.
- **Spring annotations** —
  - `@Component`: registers it as a Spring bean.
  - `@EventListener(ApplicationReadyEvent.class)`: triggers `fetchAtStartup` once the application is up. (`ApplicationReadyEvent` is the Spring event that fires after all beans are initialized and the embedded server (if any) is started.)
  - `@Value("${samato.auth.base-url:http://auth-service:9000}")` and `@Value("${samato.service-token.email:service@system}")`: configuration injection.
- **What it calls** — `RestClient.create()` (Spring's modern synchronous HTTP client — note: this is the only place in order-service using `RestClient`; all other HTTP is via Feign).
- **What calls it** — `FeignAuthForwarder.bearerTokenInterceptor` calls `getServiceToken()` when the per-request token is null.
- **Configuration keys** — `samato.auth.base-url` (default `http://auth-service:9000`), `samato.service-token.email` (default `service@system`).

### `com.samato.orderservice.config`

#### `KafkaByteArrayConfig.java`

- **What it is** — a `@Configuration` that defines a `KafkaTemplate<String, byte[]>` bean plus its `ProducerFactory`. Uses `StringSerializer` for keys and `ByteArraySerializer` for values.
- **Why it exists** — The outbox stores pre-serialized Avro `toString()` JSON bytes in `outbox_events.payload`. The shared `KafkaTemplate<String, SpecificRecord>` from `shared-kafka` would try to re-serialize the payload as Avro using the Schema Registry, which is wrong for our outbox bytes. We need a raw byte template that just forwards the bytes. Producer config sets `acks=all`, `enable.idempotence=true`, `max.in.flight=5`, `compression.type=lz4` — all matching `application.yml` defaults.
- **Spring annotations** —
  - `@Configuration`: source of beans.
  - `@Bean`: registers `byteArrayProducerFactory` and `byteArrayKafkaTemplate` (the latter depends on the former).
  - `@Value("${spring.kafka.bootstrap-servers}")`: reads the broker address.
- **What it calls** — Kafka client classes (`DefaultKafkaProducerFactory`, `ProducerConfig`, `ByteArraySerializer`, `StringSerializer`).
- **What calls it** — Spring autowires the `byteArrayKafkaTemplate` bean into `OutboxPublisher`.
- **Configuration keys** — `spring.kafka.bootstrap-servers` (from env `KAFKA_BOOTSTRAP`, default `localhost:9092`). Note that `samato.outbox.poll-ms` is **not** here — it's in `application.yml` and read by `OutboxPublisher` via the `@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")` placeholder on its method.

### `com.samato.orderservice.domain` (JPA entities + repositories)

#### `Order.java`

- **What it is** — the JPA `@Entity` for the `orders` table. It is the order aggregate root with a one-to-many owned collection of `OrderItem`s.
- **Why it exists** — the canonical place to model an order. Status transitions go through `transitionTo(OrderStatus)` which throws on illegal transitions. Has `@Version` for optimistic locking.
- **Spring annotations** —
  - `@Entity`: tells JPA "this class is mapped to a table".
  - `@Table(name = "orders", indexes = { @Index(...), ... })`: maps the class to the `orders` table and declares indexes on `customer_id`, `restaurant_id`, `status`.
  - `@Id`, `@GeneratedValue`, `@Column`: standard JPA primary key + column mapping. (Other JPA annotations present: `@OneToMany`, `@ManyToOne`, `@Enumerated(EnumType.STRING)`, `@PrePersist`, `@PreUpdate`, `@Version` — all JPA standard, not Spring stereotypes.)
- **What it calls** — `OrderItem`, `OrderStatus`. Standard JPA + `java.math.BigDecimal`, `java.time.Instant`, `java.util.UUID`.
- **What calls it** — `OrderRepository` returns it; `OrderService.placeOrder` constructs and saves it; `OrderController.toResponse` serializes it; `SagaEngine` reads/writes it during the saga.
- **Configuration keys** — none.

#### `OrderItem.java`

- **What it is** — a JPA `@Entity` for the `order_items` table. Belongs to an `Order` via `@ManyToOne`. `name` and `unitPrice` are nullable because the saga's `VALIDATE_ITEMS` step sets them (see `V2__relax_order_items_not_null.sql`).
- **Why it exists** — one row per line item. `name` and `unitPrice` are captured at order time (not looked up later) so a price change in the restaurant doesn't retroactively change past orders.
- **Spring annotations** — `@Entity`, `@Table(name = "order_items")`, plus JPA `@Id`, `@GeneratedValue`, `@Column`, `@ManyToOne(fetch = LAZY, optional = false)`, `@JoinColumn`, `@PrePersist`. (`@ManyToOne` here is JPA, not Spring.)
- **What it calls** — `Order`, `UUID`, `BigDecimal`, `Instant`.
- **What calls it** — `Order` (the parent owns the collection), `OrderService.placeOrder` (constructs items), `SagaEngine.validateItems` (sets name + price), `OrderController.toItemResponse` (serializes).
- **Configuration keys** — none.

#### `OrderStatus.java`

- **What it is** — an enum: `PLACED, VALIDATED, RESERVED, PAID, CONFIRMED, CANCELLED`. Has an `isTerminal()` helper (true for `CONFIRMED` and `CANCELLED`).
- **Why it exists** — the business state machine of an order. The Javadoc diagram shows the legal transitions; the code that enforces them is `Order.transitionTo`.
- **Spring annotations** — none.
- **What it calls** — nothing.
- **What calls it** — `Order.status` (the field), `Order.transitionTo` (the gatekeeper), `OrderController` (response serialization), `OrderService` (sets initial state), `SagaEngine.confirmOrder` and `compensate` (set terminal states).
- **Configuration keys** — none.

#### `OrderRepository.java`

- **What it is** — a Spring Data JPA repository interface for `Order`. Four finder methods, all derived from method names.
- **Why it exists** — encapsulates the query layer. `OrderService` and `SagaEngine` never write JPQL directly; they call repository methods.
- **Spring annotations** — none at the class level. (The interface extends `JpaRepository`, which itself is a Spring stereotype that Spring Data picks up via the `@EnableJpaRepositories` auto-config.)
- **What it calls** — `JpaRepository`, `Order`, `OrderStatus`.
- **What calls it** — `OrderService` (save, find, list), `SagaEngine` (read order during step execution).
- **Configuration keys** — none.

#### `SagaInstance.java`

- **What it is** — the JPA `@Entity` for the `saga_instances` table. Holds the saga's status (`SagaStatus`), `currentStepIndex`, `failureReason`, and the owned list of `SagaStep` rows. Has `@Version` for optimistic locking.
- **Why it exists** — the persistence of "where the workflow is". A row per order attempt; a poll-friendly status column so the poller can find in-progress sagas.
- **Spring annotations** — `@Entity`, `@Table(name = "saga_instances", indexes = { @Index(...), ... })`, plus JPA annotations: `@Id`, `@GeneratedValue`, `@Column`, `@Enumerated`, `@OneToMany(mappedBy = "saga", cascade = ALL, orphanRemoval = true, fetch = EAGER)`, `@OrderBy("stepIndex ASC")`, `@PrePersist`, `@PreUpdate`, `@Version`. The `cascade = ALL` + `orphanRemoval = true` means saving a saga also saves its steps.
- **What it calls** — `SagaStep`, `SagaStatus`, `SagaStepStatus`.
- **What calls it** — `SagaEngine.start` (creates), `SagaEngine.drive` / `compensate` (updates), `SagaPoller` (reads via `SagaRepository`), `OrderController.saga` (serializes for `/api/orders/{id}/saga`).
- **Configuration keys** — none.

#### `SagaStep.java`

- **What it is** — a JPA `@Entity` for the `saga_steps` table. One row per workflow step; tracks `stepType`, `status`, `payload` (free-form text, used for compensation data), `errorMessage`, `startedAt`, `completedAt`.
- **Why it exists** — the auditable step trail. `payload` is what makes compensations possible (e.g., the `paymentId` from `CHARGE_PAYMENT` is needed to call `refund`).
- **Spring annotations** — `@Entity`, `@Table(name = "saga_steps", indexes = ...)`, plus JPA: `@Id`, `@GeneratedValue`, `@Column`, `@ManyToOne(fetch = LAZY, optional = false)`, `@JoinColumn`, `@Enumerated`, `@PrePersist`. (Note: `payload` is mapped with `columnDefinition = "TEXT"` because Hibernate 6 + Postgres maps `@Lob String` to OID, which is not what the migration wants — see §7.)
- **What it calls** — `SagaInstance`, `SagaStepType`, `SagaStepStatus`. Lifecycle methods (`markRunning`, `markCompleted`, etc.) update the step's fields and timestamps.
- **What calls it** — `SagaEngine.runStep` and `compensateStep` (state transitions), `SagaRepository.findById` (read).
- **Configuration keys** — none.

#### `SagaStatus.java`

- **What it is** — an enum: `RUNNING, COMPLETED, COMPENSATING, FAILED`. The lifecycle of the orchestrator (distinct from the `OrderStatus` business state).
- **Why it exists** — separate state machine for "are we done driving the workflow?" vs. "is the order done?". During compensation the saga is `COMPENSATING` while the order may be `RESERVED`.
- **Spring annotations** — none.
- **What it calls** — nothing.
- **What calls it** — `SagaInstance.status`, `SagaEngine.drive` (sets to `COMPENSATING` on failure, `COMPLETED` on success), `SagaRepository.findByStatus(SagaStatus.RUNNING)` (poller).
- **Configuration keys** — none.

#### `SagaStepStatus.java`

- **What it is** — an enum: `PENDING, RUNNING, COMPLETED, FAILED, COMPENSATING, COMPENSATED, FAILED_COMPENSATION`. The per-step state machine.
- **Why it exists** — allows the engine to skip already-`COMPLETED` steps on retry and to know which completed steps still need compensating.
- **Spring annotations** — none.
- **What it calls** — nothing.
- **What calls it** — `SagaStep.status`, `SagaStep.markRunning/markCompleted/markFailed/markCompensating/markCompensated/markFailedCompensation`, `SagaEngine.nextPendingStep`.
- **Configuration keys** — none.

#### `SagaStepType.java`

- **What it is** — an enum: `VALIDATE_RESTAURANT, VALIDATE_ITEMS, RESERVE_INVENTORY, CHARGE_PAYMENT, CONFIRM_ORDER`. The names of the steps in the workflow.
- **Why it exists** — used as the discriminator on `SagaStep` rows. Adding a step means adding a value here AND adding a handler in `SagaEngine.dispatch`.
- **Spring annotations** — none.
- **What it calls** — nothing.
- **What calls it** — `SagaEngine.WORKFLOW` (the canonical list), `SagaStep.stepType`, `SagaEngine.dispatch` (the `switch`).
- **Configuration keys** — none.

#### `SagaRepository.java`

- **What it is** — a Spring Data JPA repository for `SagaInstance`. Two finder methods.
- **Why it exists** — encapsulates the read side. `SagaEngine.findInProgress` calls `findByStatus(SagaStatus.RUNNING)` to feed the poller.
- **Spring annotations** — none (inherits Spring Data wiring via `JpaRepository`).
- **What it calls** — `JpaRepository`, `SagaInstance`, `SagaStatus`.
- **What calls it** — `SagaEngine.start` (save), `SagaEngine.drive` (find by id, save), `SagaEngine.findInProgress` (find by status), `SagaEngine.findByOrderId`.
- **Configuration keys** — none.

#### `IdempotencyRecord.java`

- **What it is** — a JPA `@Entity` for `idempotency_records`. Stores `idempotencyKey`, `customerId`, `requestHash` (SHA-256 of the JSON body), `responseStatus`, `responseBody`, `orderId`, `createdAt`. Has a unique index on `idempotency_key` (note: in the code it's just `idempotency_key` without a per-customer compound — the repository method combines them, but the DB-level unique constraint is on the key alone; see §7).
- **Why it exists** — the de-duplication table. A client retrying with the same `Idempotency-Key` header gets the original response back, not a second order.
- **Spring annotations** — `@Entity`, `@Table(name = "idempotency_records", indexes = { @Index(name = "idx_idem_key", columnList = "idempotency_key", unique = true) })`, plus JPA annotations.
- **What it calls** — `UUID`, `Instant`.
- **What calls it** — `IdempotencyRepository.findByCustomerIdAndIdempotencyKey` (read); `OrderService.persistAndStartSaga` (insert).
- **Configuration keys** — none.

#### `IdempotencyRepository.java`

- **What it is** — a Spring Data JPA repository for `IdempotencyRecord`. One finder.
- **Why it exists** — the read path for idempotency replay.
- **Spring annotations** — none (extends `JpaRepository`).
- **What it calls** — `IdempotencyRecord`.
- **What calls it** — `OrderService.placeOrder` (replay lookup), `OrderService.persistAndStartSaga` (insert).
- **Configuration keys** — none.

#### `OutboxEvent.java`

- **What it is** — a JPA `@Entity` for `outbox_events`. The `payload` is a `byte[]` mapped as `BYTEA` (not `@Lob`, see §7). Has an index on `sent_at`.
- **Why it exists** — the outbox table. Rows are inserted in the same transaction as the business write; a poller drains them to Kafka.
- **Spring annotations** — `@Entity`, `@Table(name = "outbox_events", indexes = { @Index(name = "idx_outbox_unsent", columnList = "sent_at") })`, plus JPA annotations including `@Column(columnDefinition = "BYTEA")` for the payload.
- **What it calls** — `UUID`, `Instant`.
- **What calls it** — `OutboxPublisher` (insert via `repo.save`; read via `findUnsent`).
- **Configuration keys** — none.

#### `OutboxEventRepository.java`

- **What it is** — a Spring Data JPA repository for `OutboxEvent` with one custom JPQL query.
- **Why it exists** — `findUnsent` is the poller's only read. Note: the `@Query` uses `LIMIT 100` which is HQL — works on Hibernate 6 with PostgreSQL.
- **Spring annotations** — `@Query("SELECT e FROM OutboxEvent e WHERE e.sentAt IS NULL ORDER BY e.createdAt ASC LIMIT 100")` on `findUnsent()`. (The class itself extends `JpaRepository`.)
- **What it calls** — `JpaRepository`, `OutboxEvent`, `@Query`.
- **What calls it** — `OutboxPublisher.publishPending`.
- **Configuration keys** — none.

### `com.samato.orderservice.outbox`

#### `OutboxPublisher.java`

- **What it is** — a `@Component` that owns three "append" methods (one per topic) plus a `@Scheduled` poller.
- **Why it exists** — the bridge between the database and Kafka. Inserts are called from inside an active `@Transactional` method (so the row and the business write commit together); the poller is a separate, scheduled, transactional method that publishes.
- **Spring annotations** —
  - `@Component`: registers it as a bean.
  - `@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")`: poll interval. `fixedDelay` is the gap between the END of one invocation and the START of the next, so back-pressure is built in.
  - `@Transactional` (on `publishPending`): the whole tick runs in one transaction so a partial failure rolls back the `sent_at` updates.
  - Three topic constants: `TOPIC_ORDER_PLACED = "samato.order.placed"`, `TOPIC_ORDER_CONFIRMED = "samato.order.confirmed"`, `TOPIC_ORDER_CANCELLED = "samato.order.cancelled"`.
- **What it calls** —
  - `OrderPlacedEvent.newBuilder()` / `OrderConfirmedEvent.newBuilder()` / `OrderCancelledEvent.newBuilder()` — the Avro-generated event classes from `shared-kafka` (see [shared-and-kafka.md](shared-and-kafka.md)). `.toString()` on an Avro record produces a JSON string.
  - `OutboxEventRepository` (save + findUnsent).
  - `KafkaTemplate<String, byte[]>.send(ProducerRecord)` (the bean from `KafkaByteArrayConfig`).
  - `ObjectMapper` is injected but not used in this scan (the Avro `.toString()` is the wire format).
- **What calls it** —
  - `OrderService.persistAndStartSaga` calls `appendOrderPlaced` in the same transaction.
  - `SagaEngine.confirmOrder` calls `appendOrderConfirmed` (also transactional, via the step's `REQUIRES_NEW`).
  - `SagaEngine.compensate` calls `appendOrderCancelled` (in a `REQUIRES_NEW` step transaction).
  - The scheduler triggers `publishPending` every 500ms (configurable via `samato.outbox.poll-ms`).
- **Configuration keys** — `samato.outbox.poll-ms` (default 500ms) for the poller; the KafkaTemplate is built from `spring.kafka.*` keys (see `KafkaByteArrayConfig`).
- **Anomaly** — The payload is `event.toString().getBytes(StandardCharsets.UTF_8)`. This is the **JSON encoding of the Avro object**, NOT the Confluent Schema-Registry wire format. Downstream consumers reading these topics must therefore use a byte[] deserializer + Jackson, not `KafkaAvroDeserializer`. See §7.

### `com.samato.orderservice.saga`

#### `SagaEngine.java`

- **What it is** — the heart of the service. A `@Service` that owns the workflow (`WORKFLOW = List.of(VALIDATE_RESTAURANT, VALIDATE_ITEMS, RESERVE_INVENTORY, CHARGE_PAYMENT, CONFIRM_ORDER)`), the step dispatcher (`dispatch`), and the compensation walker (`compensate`).
- **Why it exists** — the orchestrator. The `OrderService.placeOrder` path calls `start` and `drive`; the poller also calls `drive`. Each step runs in its own `REQUIRES_NEW` transaction so a failure in one step doesn't roll back a prior step's commit (which would erase the audit trail needed for compensation).
- **Spring annotations** —
  - `@Service`: registers as a bean.
  - `@Transactional` on `start`, `drive`, `compensate`.
  - `@Transactional(propagation = Propagation.REQUIRES_NEW)` on `runStep` and `compensateStep` — these are the per-step transactions.
  - `@Lazy` on the `self` constructor parameter — a self-injection so internal calls go through the Spring proxy (otherwise `this.runStep(...)` would bypass the `@Transactional` annotation).
- **What it calls** —
  - `SagaRepository`, `OrderRepository`, `RestaurantClient`, `PaymentClient`, `StepHandlers`, `OutboxPublisher`, `ObjectMapper`, plus a self-reference (`@Lazy SagaEngine self`).
  - `dispatch` returns a `String` (the step payload, JSON). Each branch calls the appropriate Feign client and assembles a JSON payload via `writeJson` (ObjectMapper).
  - `compensate` walks `completed` steps in REVERSE order and calls `compensateStep` via `self` (so the proxy applies `@Transactional(REQUIRES_NEW)`).
- **What calls it** —
  - `OrderService.persistAndStartSaga` calls `start(orderId)`.
  - `OrderService.placeOrder` calls `drive(sagaId)` after persisting.
  - `SagaPoller.resumeInProgress` calls `drive(sagaId)` for every `RUNNING` saga.
  - `OrderService.sagaFor` calls `findByOrderId`.
- **Configuration keys** — none directly. Resilience4j on the `restaurant-client` and the Resilience4j `authService` policies are configured by name in `application.yml`; the engine itself doesn't read them.

#### `SagaPoller.java`

- **What it is** — a `@Component` with one `@Scheduled` method: `resumeInProgress`.
- **Why it exists** — the safety net. The request thread usually drives the saga synchronously, but if the JVM dies, the saga would be stuck. The poller finds every saga in `RUNNING` state and drives it.
- **Spring annotations** —
  - `@Component`.
  - `@Scheduled(fixedDelayString = "${samato.saga.poll-ms:1000}")`: 1s default. `fixedDelay` means a 1s gap AFTER each invocation, so a slow tick doesn't pile up.
- **What it calls** — `SagaEngine.findInProgress` and `SagaEngine.drive`.
- **What calls it** — the Spring scheduler.
- **Configuration keys** — `samato.saga.poll-ms` (default 1000ms).

#### `StepHandlers.java`

- **What it is** — a placeholder `@Component`. Three trivial helpers: `isCompleted`, `isFailed`, `describe`. No dispatch logic.
- **Why it exists** — a seam for future expansion. The docstring says the dispatch table is hard-coded in `SagaEngine` for now; this is the indirection point Phase 8 will use to add new step implementations without modifying the engine. (It's currently unused by `SagaEngine` in this scan.)
- **Spring annotations** —
  - `@Component`.
- **What it calls** — `SagaStep.getStatus`.
- **What calls it** — currently nothing in production code (it's a Phase 8 seam).
- **Configuration keys** — none.

### `com.samato.orderservice.security`

#### `SecurityConfig.java`

- **What it is** — the Spring Security config. Three beans: a `SecurityFilterChain`, a `JwtDecoder`, and a `Converter<Jwt, AbstractAuthenticationToken>` for the `roles` claim. Plus an inner `BearerTokenCaptureFilter`.
- **Why it exists** — defends the REST endpoints. Every request (except `/actuator/health/**`, `/actuator/info`, swagger paths) must carry a valid bearer token signed by auth-service. The role converter maps the JWT's `roles` claim (a list of strings) to Spring Security's `ROLE_<name>` authority, which `@PreAuthorize("hasRole('CUSTOMER')")` checks against.
- **Spring annotations** —
  - `@Configuration`.
  - `@Bean` on `filterChain`, `jwtDecoder`, `jwtAuthConverter`.
  - `@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")` on the `jwkSetUri` parameter of `jwtDecoder`.
- **What it calls** —
  - `HttpSecurity` (builder API: `csrf().disable()`, `cors().disable()`, `sessionCreationPolicy(STATELESS)`, `authorizeHttpRequests(...)`, `oauth2ResourceServer(o -> o.jwt(...))`).
  - `NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()` — Nimbus is the JWT library Spring Security uses; `withJwkSetUri` makes it fetch the public keys from the auth-service JWKS endpoint.
  - `JwtAuthenticationConverter` + `JwtGrantedAuthoritiesConverter` to translate the JWT.
  - Inner class `BearerTokenCaptureFilter extends OncePerRequestFilter` — captures the `Authorization: Bearer ...` header in a `ThreadLocal` (`FeignAuthForwarder.CURRENT_TOKEN`) so outbound Feign calls can re-use it.
- **What calls it** — Spring instantiates it during context startup; the resulting `SecurityFilterChain` bean is picked up by Spring Security.
- **Configuration keys** — `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (from `AUTH_JWKS_URI` env, default `http://localhost:9000/.well-known/jwks.json`).

### `com.samato.orderservice.service`

#### `OrderService.java`

- **What it is** — a `@Service` that orchestrates the request-time work: idempotency check, persist the order + outbox + saga in one transaction, drive the saga, return the final order.
- **Why it exists** — separates the request-handling "shape" (idempotency, hashing) from the saga mechanics. The controller is thin; this class holds the request-time policy.
- **Spring annotations** —
  - `@Service`.
  - `@Transactional` on `persistAndStartSaga` (which must be transactional so the order + outbox + saga rows commit together). Note: `placeOrder` itself is NOT `@Transactional` — it deliberately commits `persistAndStartSaga` before calling `sagaEngine.drive`, so the saga's reads see the committed order.
- **What it calls** —
  - `OrderRepository` (save, find).
  - `IdempotencyRepository` (lookup + save).
  - `OutboxPublisher.appendOrderPlaced` (in `persistAndStartSaga`).
  - `SagaEngine.start` (in `persistAndStartSaga`).
  - `SagaEngine.drive` (in `placeOrder`).
  - `SagaEngine.findByOrderId` (in `sagaFor`).
  - `ObjectMapper` for SHA-256 hashing of the request body.
  - `DomainException` (from shared module) for `ORDER_NOT_FOUND` and `IDEMPOTENCY_KEY_REUSED`.
- **What calls it** — `OrderController.place`, `OrderController.get`, `OrderController.list`, `OrderController.saga`.
- **Configuration keys** — none.

### `com.samato.orderservice.web`

#### `OrderController.java`

- **What it is** — the `@RestController` for `/api/orders`. Four endpoints: `place` (POST), `get` (GET by id), `list` (GET all for caller), `saga` (GET the saga for an order).
- **Why it exists** — the only HTTP entry point. All authentication and authorization live here at the method level (`@PreAuthorize`). The actual business logic is in `OrderService`; this class is a thin adapter.
- **Spring annotations** —
  - `@RestController`: a Spring stereotype marking this as a web controller whose methods return JSON bodies (as opposed to `@Controller` which returns view names).
  - `@RequestMapping("/api/orders")`: the base path for all methods.
  - `@PostMapping` (no path), `@GetMapping`, `@GetMapping("/{id}")`, `@GetMapping("/{id}/saga")`: HTTP verb + path.
  - `@PreAuthorize("hasRole('CUSTOMER')")` on `place` and `list`.
  - `@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")` on `get` and `saga` (the ownership check is a runtime check inside the method, not SpEL).
  - `@AuthenticationPrincipal Jwt jwt`: binds the JWT principal to a method parameter.
  - `@RequestHeader(value = "Idempotency-Key", required = false)`: optional header.
  - `@Valid @RequestBody OrderDtos.PlaceOrderRequest request`: validates the body using the constraints in `OrderDtos`.
- **What it calls** —
  - `OrderService.placeOrder`, `OrderService.get`, `OrderService.byCustomer`, `OrderService.sagaFor`.
  - `DomainException` (from `shared`) for `FORBIDDEN` and `SAGA_NOT_FOUND` errors.
  - `OrderDtos.OrderResponse` (return type).
- **What calls it** — Spring routes incoming HTTP requests; the api-gateway forwards `/api/orders/**` to this service via Eureka. A direct curl to `localhost:8083/api/orders` also reaches it.
- **Configuration keys** — none directly. The JWT decoding is wired by `SecurityConfig`.

### Application config (`application.yml`)

`E:/Learning/ollama-projects/springboot-app/samato/services/order-service/src/main/resources/application.yml` is 94 lines. Every key:

| Key | Value | Why it matters |
|---|---|---|
| `spring.application.name` | `samato-order-service` | Used as the Eureka service id. Other services (api-gateway, restaurant-service, payment-service) look us up by this name. |
| `spring.profiles.active` | `${SPRING_PROFILES_ACTIVE:default}` | The active Spring profile. The default is `default`; the docker-compose container sets this via env. |
| `spring.config.import` | `optional:configserver:http://localhost:8888` | Tries to fetch shared config from config-service; the `optional:` prefix means "don't fail if it's down". |
| `spring.cloud.config.enabled` | `false` | Disabled for local dev. Flip to `true` in environments where config-service is up. |
| `spring.datasource.url` | `${POSTGRES_URL:jdbc:postgresql://localhost:5432/order_service}` | PostgreSQL connection. Docker compose sets `POSTGRES_URL=jdbc:postgresql://postgres:5432/order_service`. |
| `spring.datasource.username` | `${POSTGRES_USER:fd}` | DB user. |
| `spring.datasource.password` | `${POSTGRES_PASSWORD:fd}` | DB password. |
| `spring.datasource.hikari.maximum-pool-size` | `20` | HikariCP pool ceiling. |
| `spring.datasource.hikari.minimum-idle` | `5` | HikariCP idle floor. |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate compares the entity classes to the schema at startup; throws on mismatch. We never let Hibernate auto-DDL. |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | Forces all `Timestamp` reads/writes through UTC. |
| `spring.jpa.properties.hibernate.format_sql` | `false` | Less log noise. |
| `spring.flyway.enabled` | `true` | Enables Flyway; migrations in `db/migration` run on startup. |
| `spring.flyway.locations` | `classpath:db/migration` | Where to look for `V*.sql` files. |
| `spring.flyway.baseline-on-migrate` | `true` | If the DB has no Flyway schema history table, baseline it. |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `${AUTH_JWKS_URI:http://localhost:9000/.well-known/jwks.json}` | The auth-service JWKS endpoint. Nimbus fetches the public keys from here to verify incoming JWTs. |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP:localhost:9092}` | Kafka broker. Docker compose sets `KAFKA_BOOTSTRAP=kafka:9094` (the INTERNAL listener). |
| `spring.kafka.producer.key-serializer` | `org.apache.kafka.common.serialization.StringSerializer` | Keys are UUID strings (the order id). |
| `spring.kafka.producer.value-serializer` | `org.apache.kafka.common.serialization.ByteArraySerializer` | Values are the pre-serialized Avro `toString()` JSON bytes from the outbox. |
| `spring.kafka.producer.acks` | `all` | Wait for all in-sync replicas to ack. |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Producer idempotence — no duplicate events on retry. |
| `spring.kafka.producer.properties.max.in.flight.requests.per.connection` | `5` | In-flight cap (idempotence keeps ordering with up to 5 in flight). |
| `spring.kafka.producer.properties.compression.type` | `lz4` | LZ4 compression on the wire. |
| `server.port` | `8083` | HTTP port. |
| `server.error.include-message` | `always` | Return error messages in the response body (useful in dev; not for prod). |
| `server.error.include-binding-errors` | `always` | Return validation errors in the response body. |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` | Actuator endpoints exposed. |
| `management.endpoint.health.show-details` | `when-authorized` | Detailed health info only for authenticated users. |
| `management.tracing.sampling.probability` | `1.0` | 100% of traces are sent to Zipkin. |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | Eureka server URL. |
| `eureka.instance.instance-id` | `${spring.application.name}:${server.port}` | Unique per-pod id in Eureka. |
| `eureka.instance.prefer-ip-address` | `true` | Register with the container's IP, not hostname. |
| `samato.outbox.poll-ms` | `500` | Outbox poller interval (read by `OutboxPublisher` via `@Scheduled(fixedDelayString = ...)`). |
| `samato.saga.poll-ms` | `1000` | Saga poller interval (read by `SagaPoller`). |
| `samato.resilience4j.restaurant-client.circuit-breaker.failure-rate-threshold` | `50` | Open the breaker when 50% of calls in the window fail. |
| `samato.resilience4j.restaurant-client.circuit-breaker.wait-duration-in-open-state` | `10s` | Stay open for 10s before half-open. |
| `samato.resilience4j.restaurant-client.circuit-breaker.sliding-window-size` | `10` | Window size in calls. |
| `samato.resilience4j.restaurant-client.circuit-breaker.minimum-number-of-calls` | `5` | Need at least 5 calls before the breaker can trip. |
| `samato.resilience4j.restaurant-client.retry.max-attempts` | `3` | Retry up to 3 times. |
| `samato.resilience4j.restaurant-client.retry.wait-duration` | `200ms` | Wait 200ms between retries. |
| `logging.level.root` | `INFO` | Root log level. |
| `logging.level.com.samato.orderservice` | `DEBUG` | This service at DEBUG. |
| `logging.level.org.springframework.kafka` | `INFO` | Kafka client at INFO. |
| `logging.pattern.console` | (custom pattern with traceId, spanId) | Includes MDC values for log correlation. |

**Important: the resilience4j config block is declared but no `@CircuitBreaker`/`@Retry` annotations are on the Feign interfaces.** See §7.

## 4. Endpoints (controllers)

All endpoints go through `OrderController` (`@RestController` `@RequestMapping("/api/orders")`). The route `/api/orders/**` is registered in `api-gateway`'s `GatewayRoutesConfig` (route id `samato-order-service`) with the JWT filter applied; the per-service `@PreAuthorize` is what actually enforces roles.

### `POST /api/orders` — place a new order

- **Handler** — `OrderController.place(jwt, idempotencyKey, request)` (`@PostMapping`, `@PreAuthorize("hasRole('CUSTOMER')")`).
- **Request** — `OrderDtos.PlaceOrderRequest`:
  ```json
  {
    "restaurantId": "8a1b1e0c-1234-4def-9abc-0123456789ab",
    "items": [
      { "menuItemId": "5e2c8e9b-...-..", "quantity": 2 }
    ],
    "notes": "extra napkins"
  }
  ```
  Validation: `restaurantId` must be non-null; `items` must be non-empty; each item's `menuItemId` must be non-null and `quantity >= 1`; `notes` is at most 500 chars. The body MUST NOT contain a `price` field — prices come from restaurant-service.
- **Optional header** — `Idempotency-Key: <string>`. If present, a replay returns the original order; same key + different body returns `422 IDEMPOTENCY_KEY_REUSED`.
- **Response** — `201 Created` with `OrderResponse`:
  ```json
  {
    "id": "...",
    "customerId": "...",
    "restaurantId": "...",
    "status": "PLACED" | "VALIDATED" | "RESERVED" | "PAID" | "CONFIRMED" | "CANCELLED",
    "totalAmount": 0.00,
    "currency": "USD",
    "cancellationReason": null,
    "items": [
      { "id": "...", "menuItemId": "...", "name": null, "quantity": 2, "unitPrice": null }
    ],
    "sagaId": "...",
    "createdAt": "...",
    "updatedAt": "..."
  }
  ```
  In a happy path the `status` will be `CONFIRMED` (because the saga is driven synchronously). In a failure path it will be `CANCELLED`.
- **What happens downstream** —
  1. `OrderService.placeOrder` runs the idempotency check.
  2. `OrderService.persistAndStartSaga` (in `@Transactional`) writes the `Order` row (no `total_amount` set yet), writes the `OutboxEvent` for `samato.order.placed`, creates the `SagaInstance` with 5 `SagaStep` rows (all PENDING), and writes the `IdempotencyRecord`. All commit together.
  3. `OrderService.placeOrder` then calls `SagaEngine.drive(sagaId)`, which:
     - `VALIDATE_RESTAURANT` — Feign call to `GET /api/restaurants/{id}`. Throws if `active=false`.
     - `VALIDATE_ITEMS` — Feign call to `GET /api/restaurants/{id}/menu?ids=<csv>`. Sets each `OrderItem.name` and `unitPrice` from the menu (server-side price wins). Sets `Order.totalAmount` = sum(unitPrice * qty).
     - `RESERVE_INVENTORY` — stub, returns a JSON payload with a fake `reservationId`.
     - `CHARGE_PAYMENT` — Feign call to `POST /api/payments/orders` with `Idempotency-Key: <saga-step-UUID>`. Stores `paymentId` in the step's `payload`.
     - `CONFIRM_ORDER` — `Order.transitionTo(CONFIRMED)`, save, `outbox.appendOrderConfirmed(order)`.
  4. Each step runs in `@Transactional(propagation = REQUIRES_NEW)`, so a failure in step N does not roll back step N-1.
  5. The `OutboxPublisher.publishPending` scheduler (every 500ms) sends the queued events to Kafka.
- **Allowed callers** — `CUSTOMER` role only. The `customerId` is the JWT subject; the customer cannot impersonate another customer.
- **Example curl** —
  ```bash
  TOKEN="eyJ..."  # bearer JWT for a CUSTOMER-role user
  curl -i -X POST http://localhost:8083/api/orders \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: my-order-1" \
    -d '{
      "restaurantId": "8a1b1e0c-1234-4def-9abc-0123456789ab",
      "items": [{ "menuItemId": "5e2c8e9b-2222-4def-9abc-0123456789ab", "quantity": 2 }],
      "notes": "no onions"
    }'
  ```

### `GET /api/orders/{id}` — fetch one order

- **Handler** — `OrderController.get(jwt, id)` (`@GetMapping("/{id}")`, `@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")`).
- **Ownership check** — the controller compares `jwt.getSubject()` to `order.customerId`; throws `DomainException("FORBIDDEN", ..., 403)` unless they match or the caller has the `ADMIN` role.
- **Response** — `200 OK` with `OrderResponse`, or `404 ORDER_NOT_FOUND` if the id is unknown, or `403 FORBIDDEN` if the caller isn't the owner (and not an admin).
- **Allowed callers** — the order's customer, or an `ADMIN`.
- **Example curl** —
  ```bash
  curl -i http://localhost:8083/api/orders/8a1b1e0c-1234-4def-9abc-0123456789ab \
    -H "Authorization: Bearer $TOKEN"
  ```

### `GET /api/orders` — list the caller's orders

- **Handler** — `OrderController.list(jwt)` (`@GetMapping`, `@PreAuthorize("hasRole('CUSTOMER')")`).
- **Response** — `200 OK` with `List<OrderResponse>` for `order.customerId == jwt.subject`, newest first.
- **Allowed callers** — any `CUSTOMER` (sees only their own).
- **Example curl** —
  ```bash
  curl -i http://localhost:8083/api/orders -H "Authorization: Bearer $TOKEN"
  ```

### `GET /api/orders/{id}/saga` — inspect the saga for an order

- **Handler** — `OrderController.saga(jwt, id)` (`@GetMapping("/{id}/saga")`, `@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")`).
- **Ownership check** — same as `get`.
- **Response** — `200 OK` with a `Map<String, Object>`:
  ```json
  {
    "sagaId": "...",
    "status": "RUNNING" | "COMPLETED" | "COMPENSATING" | "FAILED",
    "currentStepIndex": 0,
    "failureReason": "",
    "steps": [
      { "index": 0, "type": "VALIDATE_RESTAURANT", "status": "COMPLETED",   "error": "" },
      { "index": 1, "type": "VALIDATE_ITEMS",      "status": "COMPLETED",   "error": "" },
      { "index": 2, "type": "RESERVE_INVENTORY",   "status": "COMPENSATED", "error": "" },
      { "index": 3, "type": "CHARGE_PAYMENT",      "status": "FAILED",      "error": "Payment service unavailable" },
      { "index": 4, "type": "CONFIRM_ORDER",       "status": "PENDING",     "error": "" }
    ]
  }
  ```
- **What happens downstream** — none. This is a read-only diagnostic.
- **Allowed callers** — the order's customer, or an `ADMIN`.
- **Example curl** —
  ```bash
  curl -i http://localhost:8083/api/orders/8a1b1e0c-.../saga \
    -H "Authorization: Bearer $TOKEN"
  ```

## 5. Database schema

Database: `order_service` (a dedicated PostgreSQL database — see `init-databases.sh` referenced in the `postgres` container's volume mount). Flyway runs every `V*.sql` in `src/main/resources/db/migration` at startup. Two migrations exist.

### `V1__init.sql` — 6 tables

| Table | Columns | Indexes | Foreign keys | Written by | Read by |
|---|---|---|---|---|---|
| `orders` | `id UUID PK`, `customer_id UUID NOT NULL`, `restaurant_id UUID NOT NULL`, `status VARCHAR(20) NOT NULL`, `total_amount NUMERIC(10,2) NOT NULL`, `currency VARCHAR(3) NOT NULL`, `cancellation_reason VARCHAR(500)`, `saga_id UUID`, `created_at TIMESTAMP NOT NULL`, `updated_at TIMESTAMP NOT NULL`, `version BIGINT NOT NULL DEFAULT 0` | `idx_orders_customer` (customer_id), `idx_orders_restaurant` (restaurant_id), `idx_orders_status` (status) | none (customer and restaurant are cross-service, not FKs) | `OrderService.persistAndStartSaga` (insert), `SagaEngine` (update during step execution, transitionTo on CONFIRM_ORDER and CANCELLED) | `OrderController.get`/`list`/`saga`, `OrderService.get`/`byCustomer`/`byRestaurant`/`sagaFor`, `SagaEngine.runStep` (load order before each step) |
| `order_items` | `id UUID PK`, `order_id UUID NOT NULL` (`REFERENCES orders(id) ON DELETE CASCADE`), `menu_item_id UUID NOT NULL`, `name VARCHAR(200) NOT NULL`, `quantity INT NOT NULL`, `unit_price NUMERIC(10,2) NOT NULL`, `created_at TIMESTAMP NOT NULL` | `idx_order_items_order` (order_id) | `order_id` → `orders.id` CASCADE | `OrderService.persistAndStartSaga` (insert), `SagaEngine.validateItems` (update name + unitPrice per item) | (loaded via `Order.items` eager fetch) |
| `saga_instances` | `id UUID PK`, `order_id UUID NOT NULL`, `status VARCHAR(20) NOT NULL`, `current_step_index INT NOT NULL DEFAULT 0`, `failure_reason VARCHAR(1000)`, `created_at TIMESTAMP NOT NULL`, `updated_at TIMESTAMP NOT NULL`, `version BIGINT NOT NULL DEFAULT 0` | `idx_saga_order` (order_id), `idx_saga_status` (status) | none | `SagaEngine.start` (insert), `SagaEngine.drive` / `compensate` (update status + failureReason) | `SagaEngine.findInProgress` (the poller), `SagaEngine.findByOrderId` (called by `OrderService.sagaFor`) |
| `saga_steps` | `id UUID PK`, `saga_id UUID NOT NULL` (`REFERENCES saga_instances(id) ON DELETE CASCADE`), `step_index INT NOT NULL`, `step_type VARCHAR(50) NOT NULL`, `status VARCHAR(30) NOT NULL`, `payload TEXT`, `error_message VARCHAR(1000)`, `started_at TIMESTAMP`, `completed_at TIMESTAMP`, `created_at TIMESTAMP NOT NULL` | `idx_step_saga` (saga_id), `idx_step_status` (status) | `saga_id` → `saga_instances.id` CASCADE | `SagaEngine.start` (insert 5 PENDING), `SagaEngine.runStep` (markRunning / markCompleted), `SagaEngine.compensateStep` (markCompensating / markCompensated / markFailedCompensation) | `SagaEngine` (re-load to walk completed steps) |
| `idempotency_records` | `id UUID PK`, `idempotency_key VARCHAR(200) NOT NULL`, `customer_id UUID NOT NULL`, `request_hash VARCHAR(64) NOT NULL`, `response_status INT NOT NULL`, `response_body TEXT`, `order_id UUID`, `created_at TIMESTAMP NOT NULL` | `idx_idem_key` (idempotency_key, UNIQUE) | none | `OrderService.persistAndStartSaga` (insert when key provided) | `OrderService.placeOrder` (replay lookup) |
| `outbox_events` | `id UUID PK`, `aggregate_type VARCHAR(100) NOT NULL`, `aggregate_id UUID NOT NULL`, `topic VARCHAR(100) NOT NULL`, `event_type VARCHAR(200) NOT NULL`, `payload BYTEA NOT NULL`, `sent_at TIMESTAMP`, `created_at TIMESTAMP NOT NULL` | `idx_outbox_unsent` (sent_at) | none | `OutboxPublisher.appendOrderPlaced` (in `OrderService.persistAndStartSaga`), `OutboxPublisher.appendOrderConfirmed` (in `SagaEngine.confirmOrder`), `OutboxPublisher.appendOrderCancelled` (in `SagaEngine.compensate`) | `OutboxPublisher.publishPending` (poller) |

### `V2__relax_order_items_not_null.sql`

Alters `order_items` to allow NULL in `name` and `unit_price`. The order is persisted BEFORE the saga's `VALIDATE_ITEMS` step fetches prices; without this migration, the initial insert would fail because both columns were `NOT NULL`. The saga fills them in within a few hundred ms; readers that fetch the order before then will see nulls.

## 6. Kafka integration

### Topics published

| Topic | Key | Payload type | Source file |
|---|---|---|---|
| `samato.order.placed` | `aggregateId.toString()` (the order id) — used as the partition key so all events for one order go to the same partition (preserves ordering) | `OrderPlacedEvent` (Avro) — pre-serialized to UTF-8 JSON bytes via `event.toString().getBytes(...)` | `OutboxPublisher.appendOrderPlaced` |
| `samato.order.confirmed` | order id | `OrderConfirmedEvent` (Avro) — pre-serialized | `OutboxPublisher.appendOrderConfirmed` |
| `samato.order.cancelled` | order id | `OrderCancelledEvent` (Avro) — pre-serialized | `OutboxPublisher.appendOrderCancelled` |

**Important anomaly**: the payload is `event.toString().getBytes(StandardCharsets.UTF_8)`. The Avro `toString()` method on a `SpecificRecord` emits **JSON** (a human-readable form), not the Confluent Schema-Registry wire format. So the bytes on the topic are JSON, not binary Avro. Downstream consumers must use `ByteArrayDeserializer` + Jackson, **not** `KafkaAvroDeserializer`. (This is documented in [shared-and-kafka.md](shared-and-kafka.md) as the "byte[] template path" anomaly.)

### Topics consumed

**None.** No `@KafkaListener` is declared in this service in the current scan. The poller (`SagaPoller`) reads from the database, not from Kafka.

### Outbox

- **Table** — `outbox_events` (described in §5). The "marker" field for unsent is `sent_at` (`NULL` = unsent).
- **Poller** — `OutboxPublisher.publishPending`, `@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")`, `@Transactional`. Default interval is **500ms**.
- **Per-tick behavior** — fetches up to 100 unsent rows via `OutboxEventRepository.findUnsent()` (JPQL `WHERE sent_at IS NULL ORDER BY created_at ASC LIMIT 100`). For each, builds a `ProducerRecord<String, byte[]>` with `key = aggregateId` and `value = payload`, calls `kafka.send(record)`, then sets `sent_at = NOW()` and saves. If `send` throws, the row stays unsent and is retried on the next tick.
- **Failure mode** — there is no DLQ or retry counter. If a single event repeatedly fails to send, it blocks the poller's view of older events with the same `aggregate_id` only in ordering, not in queueing (the JPQL sorts by `created_at`, not by `aggregate_id`).
- **Idempotency** — at-least-once. The producer config sets `enable.idempotence=true`, so within a single producer session there are no duplicates, but a crash between `kafka.send` and `repo.save(sent_at)` causes a re-send on the next tick. Downstream consumers must dedupe (the search-service uses upsert semantics).

## 7. Common "if you change X, also update Y" notes

These are the gotchas — cross-file dependencies that imports don't show.

1. **Order prices are NEVER set by the client.** The client posts `{ menuItemId, quantity }` only. The server pulls the price from restaurant-service in `SagaEngine.validateItems`. If you add a `unitPrice` field to `OrderDtos.PlaceOrderRequest`, you will silently break the "don't trust the client" invariant — and `OrderService.persistAndStartSaga` deliberately does NOT call `order.recomputeTotal()` because items have no price yet (see comment at `OrderService.java:135-138`). Don't add a price field to the request DTO.

2. **`OrderItem.name` and `OrderItem.unitPrice` are nullable.** The JPA entity says `@Column(length = 200) private String name;` and `@Column(...) private BigDecimal unitPrice;` — both without `nullable = false`. The original `V1__init.sql` declared them `NOT NULL`; `V2__relax_order_items_not_null.sql` relaxed the constraint. If you regenerate the schema or hand-edit `V1`, you must keep `V2` in place. Likewise, don't add a `@NotNull` to the JPA field; the early-insert path would fail.

3. **The Avro payload on the wire is JSON, not binary.** `OutboxPublisher.save` does `avroEvent.toString().getBytes(UTF_8)`. This bypasses the Confluent Schema Registry. If you add a new event, the schema goes in `shared-kafka/src/main/avro/<Name>.avsc` (for the IDE and any re-encoding), but the wire bytes are JSON. Any consumer in another language will need to parse JSON, not read Avro binary. See [shared-and-kafka.md](shared-and-kafka.md) for the cross-service topic cross-reference.

4. **`@Lob` is forbidden on `byte[]`/`String`/`String payload` columns.** `OutboxEvent.payload`, `IdempotencyRecord.responseBody`, and `SagaStep.payload` are all mapped with `columnDefinition = "BYTEA"` / `columnDefinition = "TEXT"`. Hibernate 6 + Postgres maps `@Lob byte[]` to OID (a deprecated large-object type) by default; the migration uses BYTEA/TEXT, so the entity must match. See the docstring in `OutboxEvent.java:38-40` and the INTERVIEW-NOTES bug history.

5. **Resilience4j is configured by name, NOT annotated.** `application.yml` declares `samato.resilience4j.restaurant-client.{circuit-breaker, retry}` with thresholds, but the Feign interface `RestaurantClient` has **no** `@CircuitBreaker(name = "restaurant-client", ...)` annotation. Same for `PaymentClient`. The fallback is via the `fallbackFactory` (so a Feign call that throws is replaced by a stub that throws `RestaurantUnavailableException` / `PaymentUnavailableException`). The INTERVIEW-NOTES explains why: the saga has its own retry semantics (the poller re-drives), and adding Resilience4j retries on top would cause double retries. So if you want to ADD `@CircuitBreaker` annotations, first reason about how the breaker and the saga's compensation interact.

6. **`KafkaByteArrayConfig` is the only producer factory.** The `shared-kafka` module's `KafkaProducerConfig` produces a `KafkaTemplate<String, SpecificRecord>`, but order-service does NOT autowire that template anywhere in this scan. If you add code that needs to send `SpecificRecord` values directly (rather than pre-serialized bytes), you'd need to also wire that template (e.g., `@Qualifier("kafkaTemplate")`). The outbox ALWAYS uses the byte-array template.

7. **`SagaEngine` self-injection via `@Lazy`.** `SagaEngine` injects `SagaEngine self` with `@Lazy` so that `self.runStep(...)` and `self.compensateStep(...)` go through the Spring proxy. A plain `this.runStep(...)` would bypass the `@Transactional` and `@Transactional(propagation = REQUIRES_NEW)` annotations. Don't refactor this to remove the self-field unless you also have a plan for the transaction semantics.

8. **`@Transactional` on `placeOrder` is intentionally absent.** `OrderService.placeOrder` is **not** `@Transactional`. The method calls `persistAndStartSaga` (which IS `@Transactional`), waits for the commit, then calls `sagaEngine.drive(sagaId)`. If `placeOrder` were `@Transactional`, the saga's `sagaRepository.findById(...)` wouldn't see the just-persisted saga row (because it'd be in the same transaction, and the saga's `REQUIRES_NEW` step transactions would create issues). See the comment at `OrderService.java:64-68`.

9. **`IdempotencyRecord.idempotency_key` is UNIQUE globally, not per-customer.** The migration has `CONSTRAINT uq_idem UNIQUE (idempotency_key)` — a global uniqueness on the key string. The repository method `findByCustomerIdAndIdempotencyKey` filters at the application level, but the unique constraint is on the key alone. So if two different customers happen to use the same key string, the second insert will fail with `DataIntegrityViolationException`. (The docstring claims the constraint is per-customer; the SQL says otherwise. This is a latent bug; see INTERVIEW-NOTES on concurrent requests.)

10. **`StepHandlers` is a Phase 8 seam.** It's a `@Component` with three trivial helpers; `SagaEngine` does NOT call it. If you add a new step type, edit `SagaStepType`, the `WORKFLOW` list in `SagaEngine`, the `dispatch` switch, and the `compensate` switch — all in `SagaEngine`. `StepHandlers` is for future use.

11. **`BearerTokenCaptureFilter` clears the thread-local on `finally`.** If you copy this pattern to another service, remember to clear the ThreadLocal in a `finally` block — otherwise, thread-pool reuse will leak tokens across requests. See `SecurityConfig.java:114-118`.

12. **`ServiceTokenProvider` parses the dev-token response by hand.** `fetchAtStartup` does a substring search for `"access_token":"` and reads until the next `"`. It does NOT use Jackson. If the auth-service ever changes the response shape (e.g., wraps in `{"data": {...}}`), the token will silently be null and background Feign calls will 401. The service should switch to `ObjectMapper.readTree(...)`.

13. **`SagaStatus` and `OrderStatus` are different state machines.** Don't try to unify them. The order state is the business view; the saga state is the orchestration view. During compensation the order can be in `RESERVED` while the saga is `COMPENSATING`. Mixing them would confuse the audit trail.

14. **`Eureka` registration fails silently if `discovery-service` is down.** The service still starts; you just can't reach it via the gateway by name. The compose `depends_on: discovery-service: service_started` is non-blocking (service_started, not service_healthy), so the service comes up before discovery-service is ready. After about 30s the registration eventually succeeds.

15. **Prometheus does NOT scrape order-service.** The `prometheus/prometheus.yml` `spring-boot-services` job lists only `config`, `discovery`, and `api-gateway` (via `host.docker.internal`). Order-service exposes `/actuator/prometheus` but it is not in the static target list. You'll see metrics in Zipkin traces but not in Prometheus. See [ARCHITECTURE.md](../../ARCHITECTURE.md) and the infrastructure notes.

16. **`@RestControllerAdvice GlobalExceptionHandler` is in `shared` but is NEVER explicitly imported in order-service.** The shared class is picked up by the `@ComponentScan(basePackages = {"com.samato.shared", ...})` in `OrderServiceApplication`. The `DomainException` → JSON mapping is automatic. If you remove `com.samato.shared` from the scan, all `DomainException` throws will produce 500s with a generic body. See [shared-and-kafka.md](shared-and-kafka.md) for the global exception handler.

17. **The `feigns` depend on `openfeign` in `shared`.** `FeignAuthForwarder` works because the `shared` module pulls `spring-cloud-starter-openfeign` and order-service depends on `shared`. Don't move the interceptor without keeping the dependency.

18. **Saga step ordering is by `stepIndex` ASC.** `SagaInstance.steps` is `@OrderBy("stepIndex ASC")`. Compensation walks the list in REVERSE Java order (via `Collections.reverse(completed)`). Don't change the `@OrderBy` without also changing the compensation walker.

19. **The Kafka template injection in `OutboxPublisher` is `KafkaTemplate<String, byte[]>`.** If you accidentally inject the shared `KafkaTemplate<String, SpecificRecord>`, the `.send(ProducerRecord<String, byte[]>)` call won't compile. The `byteArrayKafkaTemplate` bean name is documented in [shared-and-kafka.md](shared-and-kafka.md) (note: it is local to order-service, defined in `KafkaByteArrayConfig`).

20. **`OrderController` enforces ownership in Java, not in `@PreAuthorize` SpEL.** The `@PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")` allows any CUSTOMER or ADMIN; the `if (!jwt.getSubject().equals(...))` check in the method body is what actually prevents one customer from seeing another's order. If you move the check to SpEL, remember to use `#id` (the path variable) and `authentication.name`.

## 8. See also

- **Per-service designer note** (the in-depth design walkthrough by the original author): [services/order-service/docs/INTERVIEW-NOTES.md](../../services/order-service/docs/INTERVIEW-NOTES.md)
- **Shared library & Kafka module** (DomainException, GlobalExceptionHandler, CorrelationIdFilter, MdcKeys, Avro events, Kafka producer/consumer config): [shared-and-kafka.md](shared-and-kafka.md)
- **Architecture guide**: [../01-architecture-guide.md](../01-architecture-guide.md)
- **How auth works** (JWKs, JWT validation, role claim): [../02-how-auth-works.md](../02-how-auth-works.md)
- **Glossary of terms** (Spring annotations, microservice patterns): [../00-glossary.md](../00-glossary.md)
- **Use case — Place an order** (the full end-to-end walkthrough): [../use-cases/01-place-an-order.md](../use-cases/01-place-an-order.md)
- **Use case — Refund flow** (the saga's compensation path): [../use-cases/04-refund-flow.md](../use-cases/04-refund-flow.md)
- **Repo-level docs**: [ARCHITECTURE.md](../../ARCHITECTURE.md), [PROJECT-STATUS.md](../../PROJECT-STATUS.md), [RUN-THE-BIBLE.md](../../RUN-THE-BIBLE.md), [INTERVIEW-CHEATSHEET.md](../INTERVIEW-CHEATSHEET.md)

### Anomalies documented in this guide

- Outbox payload is `event.toString().getBytes(UTF_8)` — JSON on the wire, not Confluent Schema-Registry Avro. Downstream consumers must use `ByteArrayDeserializer` + Jackson, not `KafkaAvroDeserializer`.
- Resilience4j is configured by name in `application.yml` but the Feign interfaces do NOT have `@CircuitBreaker` / `@Retry` annotations; only `fallbackFactory` is used. This is deliberate (saga poller does the retries).
- `KafkaByteArrayConfig` is a service-local producer factory because the shared `KafkaTemplate<String, SpecificRecord>` is not used by the outbox.
- The `IdempotencyRecord.idempotency_key` UNIQUE constraint is global, not per-customer; the docstring in the entity claims it is per-customer.
- Prometheus does not scrape `order-service` (the `spring-boot-services` job lists only config, discovery, api-gateway).
- `GlobalExceptionHandler` and `DomainException` are imported only via `@ComponentScan` — no explicit `@Import` or import statement in order-service.
- `StepHandlers` is unused (a Phase 8 seam).
- `OrderItem.name` and `OrderItem.unitPrice` are nullable in the JPA entity despite the original migration declaring them `NOT NULL`; `V2__relax_order_items_not_null.sql` is required.
