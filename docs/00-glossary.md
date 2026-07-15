# Samato Glossary

A beginner-friendly reference for every Spring Boot, Spring Cloud, microservice, Kafka, and auth term you will hit while reading the per-service docs in `docs/services/`. Each entry has a plain-English definition, a "where it shows up in Samato" pointer to a real service and file (with link), and a short "Why it matters" note when the concept is non-obvious.

Read this once, then keep it open in a tab. Every other doc assumes these terms.

> **Samato-specific anomaly to keep in mind throughout**: there are two on-the-wire formats for Kafka events in this codebase. `restaurant-service` ships true Confluent Avro (magic byte + schema id + Avro binary) via the `shared-kafka` `KafkaTemplate<String, SpecificRecord>`. `order-service` and `payment-service` ship `byte[]` that is actually `Avro.toString(event).getBytes(UTF_8)` — JSON text, not Avro wire format. Three of the payment topics (`samato.payment.created`, `samato.payment.refund.initiated`, `samato.payment.expired`) have **no** `.avsc` file at all — they are pure JSON. See [shared-and-kafka.md](./services/shared-and-kafka.md) for the full anomaly breakdown.

---

## Spring Boot fundamentals

### @Async

Tells Spring to run a method on a background thread pool instead of the caller's thread. The caller gets a `CompletableFuture` back and never blocks.
**Where it shows up in Samato**: not used directly. `KafkaTemplate.send(...)` in `Outbox` pollers is **synchronous** on purpose (`.get(5, SECONDS)`) so the poller can mark the row sent only after the broker acks.
**Why it matters**: in this codebase "background work" is done by a `@Scheduled` poller, not `@Async`. The two patterns look similar but have different failure semantics.

### @Autowired

The original "Spring, please inject this dependency" annotation. Constructor injection is preferred today; `@Autowired` survives on legacy fields and setters.
**Where it shows up in Samato**: rarely — most services use constructor injection (e.g. `RestaurantService(RestaurantRepository repo, Outbox outbox)`). You will see it on a handful of older test fixtures.

### @Bean

Marks a method as "Spring, call me once and put the result in the application context." The return type and method name become the bean name by default.
**Where it shows up in Samato**: `KafkaProducerConfig#kafkaTemplate`, `KafkaConsumerConfig#kafkaListenerContainerFactory`, `CacheConfig#cacheManager`, every `SecurityConfig#filterChain / jwtDecoder / jwtAuthenticationConverter`, `OpenSearchConfig#openSearchClient`. See [shared-and-kafka.md](./services/shared-and-kafka.md#kafkaproducerconfig).

### @Column

JPA annotation that pins a field to a specific table column (and optionally overrides the SQL type).
**Where it shows up in Samato**: every `@Entity`. In `OutboxEvent`, `@Column(columnDefinition = "BYTEA")` on `payload` is the documented fix for the Hibernate 6 + Postgres OID-storage bug — see [restaurant-service.md](./services/restaurant-service.md#36-domainoutboxevent).

### @Component

The generic "this is a Spring-managed bean" annotation. Specializations like `@Service`, `@Repository`, `@Configuration`, and `@RestController` are all `@Component` under the hood.
**Where it shows up in Samato**: `CorrelationIdFilter` and `RestaurantOwnership` are bare `@Component`s. `Outbox` in `restaurant-service` is a `@Component` because it does both the write side and the scheduled publish.

### @ComponentScan

Tells Spring which packages to search for beans. Without it Spring only scans the package of the `@SpringBootApplication` class.
**Where it shows up in Samato**: every service main class — `@ComponentScan(basePackages = {"com.samato.<service>","com.samato.shared","com.samato.sharedkafka"})`. The two shared packages are the key: without them the `KafkaTemplate`, `GlobalExceptionHandler`, and `DomainException` are invisible to the service.

### @Configuration

A `@Component` whose purpose is to host `@Bean` methods. Spring instantiates it once at startup and calls each `@Bean` method to populate the context.
**Where it shows up in Samato**: every `SecurityConfig`, every `*Config` (CacheConfig, OpenSearchConfig, FeignConfig, KafkaProducerConfig, KafkaConsumerConfig).

### @ConfigurationProperties

Binds a group of YAML keys to a Java record/class. Type-safe alternative to scattering `@Value` everywhere.
**Where it shows up in Samato**: used sparingly. Most services read config via `@Value` or via the auto-bound `spring.*` keys. The pattern would apply if a service grew a `samato.*` block larger than a handful of keys.

### @Controller / @RestController

`@Controller` returns a view name; `@RestController` is `@Controller` + `@ResponseBody` so methods serialize their return value as JSON straight into the response body. Samato is an API, so every controller is `@RestController`.
**Where it shows up in Samato**: `RestaurantController`, `OrderController`, `CustomerProfileController`, `SearchController`, etc.

### @DataJpaTest

JUnit slice annotation that boots only JPA + an in-memory (or Testcontainers) database, no MVC, no Kafka. Faster than `@SpringBootTest`.
**Where it shows up in Samato**: repository tests (e.g. `RestaurantRepositoryTest`).

### @EnableAutoConfiguration

Part of `@SpringBootApplication`. Turns on Spring Boot's "if you have X on the classpath, auto-configure the matching beans" magic. You almost never write this directly.
**Where it shows up in Samato**: implicitly, on every `@SpringBootApplication`.

### @EnableScheduling

Turns on the `@Scheduled` task executor. Without it, `@Scheduled` methods are silently ignored.
**Where it shows up in Samato**: every service that has an outbox poller — `order-service`, `payment-service`, `restaurant-service`. See the `@EnableScheduling` on `RestaurantServiceApplication` in [restaurant-service.md](./services/restaurant-service.md#31-comsamatorestaurantservicerestaurantserviceapplication).

### @Entity

Marks a class as a JPA entity. Hibernate will manage instances and map them to a table.
**Where it shows up in Samato**: every `domain/*` class — `Restaurant`, `MenuItem`, `OutboxEvent`, `CustomerProfile`, `Order`, `Payment`, `EventStoreEntry`.

### @FeignClient

The Spring Cloud OpenFeign annotation. Put it on an interface and Spring generates a runtime proxy that does the HTTP call for you.
**Where it shows up in Samato**: `AuthClient` (user-service → auth-service), `RestaurantClient` (order-service → restaurant-service), `PaymentClient` (order-service → payment-service). See [user-service.md](./services/user-service.md#33-clientauthclientjava).

### @GeneratedValue

Tells JPA to generate the primary key value (strategy: `AUTO`, `IDENTITY`, `SEQUENCE`, `UUID`).
**Where it shows up in Samato**: every `@Id` field, paired with `@Column(columnDefinition = "uuid")` so the database column is a true UUID.

### @GetMapping / @PostMapping / @PutMapping / @DeleteMapping

HTTP verb + path shorthand for `@RequestMapping(method = ..., value = ...)`. One annotation per handler method.
**Where it shows up in Samato**: every controller method. See the seven mappings on `RestaurantController` in [restaurant-service.md](./services/restaurant-service.md#314-comsamatorestaurantservicewebrestaurantcontroller).

### @Id

Marks a field as the JPA primary key.
**Where it shows up in Samato**: every `@Entity`. Always a `UUID` in Samato.

### @KafkaListener

Marks a method as a Kafka consumer. Spring Kafka starts a thread that polls the topic and invokes the method for each record.
**Where it shows up in Samato**: `RestaurantEventListener#onEvent` in `search-service` is the **only** `@KafkaListener` in the whole monorepo. It consumes `samato.restaurant.created` and `samato.restaurant.updated` with `ack-mode: manual_immediate`. See [search-service.md](./services/search-service.md#35-projectionrestauranteventlistenerjava).

### @Lob

Tells JPA a field is a large object. **Avoid it in Samato** for `byte[]` on Postgres — Hibernate 6 will switch to OID storage and break reads. Use `@Column(columnDefinition = "BYTEA")` instead.
**Where it shows up in Samato**: deliberately not used. The `OutboxEvent.payload` field uses the explicit `BYTEA` override for exactly this reason.

### @PathVariable

Binds a `{name}` segment of the URL path to a method parameter.
**Where it shows up in Samato**: every `GET /{id}`, `PUT /{id}`, `DELETE /{id}` handler. See `RestaurantController.update(@PathVariable UUID id, ...)`.

### @PreAuthorize

Method-level security. SpEL expression is evaluated before the method runs; throws `AccessDeniedException` (→ 403) if it returns false.
**Where it shows up in Samato**: every controller. Examples: `@PreAuthorize("hasRole('DRIVER')")`, `@PreAuthorize("hasRole('ADMIN') or @restaurantOwnership.isOwner(#id, authentication)")`. Requires `@EnableMethodSecurity` on the `SecurityConfig`.

### @Profile

Activates a bean only when a specific Spring profile is set (e.g. `dev`, `prod`, `docker`).
**Where it shows up in Samato**: not currently used. The convention is to override config keys via environment variables in compose instead.

### @Repository

A specialization of `@Component` for data-access beans. Spring also translates JPA exceptions into Spring's `DataAccessException` hierarchy.
**Where it shows up in Samato**: every Spring Data `JpaRepository` interface — `RestaurantRepository`, `MenuItemRepository`, `OrderRepository`, `PaymentRepository`. Spring Data picks them up by interface inheritance, so the annotation is implicit.

### @RequestBody

Binds the HTTP request body (JSON) to a method parameter. Pairs with `@Valid` to trigger Bean Validation.
**Where it shows up in Samato**: every `POST`/`PUT` body. `CreateRestaurantRequest`, `UpdateProfileRequest`, `PlaceOrderRequest`, etc. See [restaurant-service.md](./services/restaurant-service.md#314-comsamatorestaurantservicewebrestaurantcontroller).

### @RequestHeader

Binds an HTTP header to a method parameter.
**Where it shows up in Samato**: `Idempotency-Key` header on order placement (`OrderController.placeOrder(@RequestHeader("Idempotency-Key") String key, ...)`).

### @RequestMapping

Class- or method-level URL prefix. Use the verb-specific siblings for clarity.
**Where it shows up in Samato**: class-level on every controller (`@RequestMapping("/api/restaurants")` etc.).

### @RequestParam

Binds a query string parameter to a method parameter. Optional with `required = false` or boxed types.
**Where it shows up in Samato**: `SearchController` (`@RequestParam String q`, `@RequestParam(required=false) String cuisine`, ...). Also `OrderController` paging.

### @RestControllerAdvice

A `@Component` whose `@ExceptionHandler` methods run around every `@RestController` in the application. Samato's `GlobalExceptionHandler` uses it to render one uniform JSON error envelope.
**Where it shows up in Samato**: `shared/GlobalExceptionHandler` plus per-service copies. See [shared-and-kafka.md](./services/shared-and-kafka.md#32-sharedsrcmainjavacomsamtosharederrorsglobalexceptionhandlerjava).

### @Scheduled

Marks a method to be invoked on a timer. The parameters `fixedDelay`, `fixedRate`, `cron` are the common scheduling knobs.
**Where it shows up in Samato**: the outbox pollers. `Outbox.publishPending` runs every 1 s (`fixedDelay = 1000L`) in `restaurant-service`; `order-service` and `payment-service` use `fixedDelayString = "${samato.outbox.poll-ms:500}"`. See [restaurant-service.md](./services/restaurant-service.md#313-comsamatorestaurantserviceserviceoutbox).

### @Secured

The older (pre-3.0) method-security annotation. `@PreAuthorize` is preferred today because it accepts SpEL.
**Where it shows up in Samato**: legacy/optional. `@PreAuthorize` is the standard everywhere.

### @Service

A specialization of `@Component` for business-logic beans. Semantically identical at runtime; the annotation just documents intent.
**Where it shows up in Samato**: `RestaurantService`, `OrderService`, `PaymentService`, etc.

### @SpringBootApplication

The umbrella annotation. Combines `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` (rooted at the annotated class's package).
**Where it shows up in Samato**: every service main class. See `RestaurantServiceApplication` in [restaurant-service.md](./services/restaurant-service.md#31-comsamatorestaurantservicerestaurantserviceapplication).

### @SpringBootTest

Full-context integration test. Boots the entire application on a random port. Slow but real.
**Where it shows up in Samato**: end-to-end tests that span MVC + JPA + (sometimes) Testcontainers for Kafka/Postgres.

### @Table

JPA annotation that pins a class to a specific table and optionally declares indexes.
**Where it shows up in Samato**: every `@Entity`. Note: the `@Index` on `OutboxEvent` does not capture the partial `WHERE sent_at IS NULL` predicate — the Flyway migration is the source of truth.

### @Transactional

Wraps a method (or class) in a database transaction. Read methods use `@Transactional(readOnly = true)` for a small Hibernate optimization.
**Where it shows up in Samato**: every write method in `RestaurantService`, `OrderService`, `PaymentService`. This is what makes the outbox pattern atomic — the business write and the `outbox_events` insert commit together.

### @Value

Inject a single property from the `Environment` (YAML, env vars, system properties). Has a default via `:`.
**Where it shows up in Samato**: `@Value("${spring.kafka.bootstrap-servers:localhost:9092}")`, `@Value("${samato.opensearch.host:localhost}")`, etc.

### @Version

JPA optimistic locking. Hibernate bumps the field on every update and refuses the write if the in-memory copy is stale.
**Where it shows up in Samato**: `Restaurant.version` (long). Two concurrent updates to the same restaurant will fail the second with `OptimisticLockException` — see [restaurant-service.md](./services/restaurant-service.md#33-comsamatorestaurantservicedomainrestaurant).

### @WebMvcTest

JUnit slice that boots only the Spring MVC layer. Fast for controller tests; no JPA, no Kafka.
**Where it shows up in Samato**: controller-slice tests.

---

## Spring Cloud

### API Gateway

A single network entry point that fronts every microservice. Handles routing, auth, rate limiting, CORS, correlation IDs.
**Where it shows up in Samato**: `api-gateway` (port 8080), a Spring Cloud Gateway reactive app. Routes by Eureka service id (`lb://samato-<service>`). See [api-gateway.md](./services/api-gateway.md).

### @EnableDiscoveryClient

Tells Spring Cloud to register this service with the service registry and to look up other services by id.
**Where it shows up in Samato**: every service main class except `discovery-service` itself (which uses `@EnableEurekaServer`).

### @EnableEurekaServer

Turns a Spring Boot app into a Eureka server (the registry other services register with).
**Where it shows up in Samato**: `discovery-service` only. See [discovery-service.md](./services/discovery-service.md).

### @EnableFeignClients

Turns on Spring Cloud OpenFeign. Without it, `@FeignClient` interfaces are ignored.
**Where it shows up in Samato**: every service that makes outbound HTTP calls — `order-service`, `payment-service`, `user-service`. `restaurant-service` and `search-service` do not call other services (they're event-driven).

### Eureka

Netflix's service registry. Services register on startup; clients look up other services by id. The lookup returns a list of `host:port` instances; the load balancer (Ribbon, now Spring Cloud LoadBalancer) picks one.
**Where it shows up in Samato**: `discovery-service` is the Eureka server (port 8761). Every other service registers with it. See [discovery-service.md](./services/discovery-service.md).

### Resilience4j

A fault-tolerance library: circuit breaker, retry, bulkhead, rate limiter, time limiter. Pure Java, no Hystrix-style sidecar.
**Where it shows up in Samato**: `application.yml` blocks in `order-service`, `payment-service`, `user-service` define `resilience4j.circuitbreaker.instances.<name>.*` policies. Note: the policies are **configured but not annotated** on the call sites in some services (see anomaly in [user-service.md](./services/user-service.md#71-authclient-is-defined-but-not-injected-anywhere)). The annotations are `@CircuitBreaker`, `@Retry`, `@Bulkhead`, `@TimeLimiter`.

### Spring Cloud Config Server

Centralized config server. Services fetch their `application.yml` from it at startup instead of shipping a local copy.
**Where it shows up in Samato**: `config-service` (port 8888) serves `config-repo/application.yml` to all services. Currently every service has `spring.cloud.config.enabled: false` in dev — the file is on disk for future use. See [config-service.md](./services/config-service.md).

### Spring Cloud Gateway

Reactive (WebFlux) API gateway built on Spring WebFlux and Netty. Routes by predicate (path, method, header) and applies filters.
**Where it shows up in Samato**: `api-gateway` only. The `JwtAuthFilter`, `CorsConfig`, `RateLimitConfig` are all gateway filters. Note: it does **not** use the servlet `CorrelationIdFilter` from `shared`; it has its own reactive `CorrelationIdWebFilter`.

### @EnableConfigServer

Turns a Spring Boot app into a Config Server.
**Where it shows up in Samato**: `config-service` only. See [config-service.md](./services/config-service.md).

---

## Microservice patterns

### API gateway
See API Gateway in the Spring Cloud section.

### Bulkhead

A fault-isolation pattern: cap the number of concurrent calls to a dependency so a slow downstream can't exhaust your thread pool.
**Where it shows up in Samato**: Resilience4j `@Bulkhead` is on the classpath but currently unused. The configured `resilience4j.*` blocks are circuit-breaker and retry only.

### Circuit breaker

A fault-isolation pattern: after N consecutive failures, stop calling the downstream for a while ("open" state); probe with one call after a cooldown; if it succeeds, close again.
**Where it shows up in Samato**: `AuthClient` (user-service) and `RestaurantClient` (order-service) wrap their downstream calls. Configured in `application.yml` under `resilience4j.circuitbreaker.instances.<name>.*`.

### CQRS

Command-Query Responsibility Segregation: the write model and the read model live in different stores optimized for their access pattern.
**Where it shows up in Samato**: `payment-service` (write model = event-sourced Postgres, read model = `PaymentView` Postgres table built by a projector). See [payment-service.md](./services/payment-service.md).
`restaurant-service` is "CQRS-lite" — the write side hits Postgres + outbox, the read side is a Redis cache.
`search-service` is the third instance — write model in `restaurant-service`'s Postgres, read model in OpenSearch, synced via Kafka.

### Correlation ID

A per-request UUID that's logged with every line and forwarded on every outbound call (HTTP, Kafka, Feign). Lets you `grep` one request across services.
**Where it shows up in Samato**: `CorrelationIdFilter` (servlet, from `shared`), `CorrelationIdWebFilter` (reactive, in `api-gateway`), `FeignCorrelationIdInterceptor` (outbound Feign), `KafkaMdcProducerInterceptor` (outbound Kafka), `MdcContext.fromKafka(record)` (inbound Kafka). All five read the same `X-Correlation-Id` constant from `MdcKeys`. See [shared-and-kafka.md](./services/shared-and-kafka.md#33-sharedsrcmainjavacomsamtosharedobservabilitycorrelationidfilterjava).

### Database per service

Each service owns its own database. No shared schemas, no cross-service joins, no cross-service foreign keys.
**Where it shows up in Samato**: 12 Postgres databases — one per service. `init-databases.sh` provisions them. Joins across services are done in code (Feign call, event projection, or gateway composition).

### Distributed tracing

Following one request across all the services it touches. Samato uses Micrometer + Brave + Zipkin.
**Where it shows up in Samato**: every `application.yml` sets `management.tracing.sampling.probability: 1.0` (dev) and `management.zipkin.tracing.endpoint`. The trace id shows up in every log line via the `logging.pattern.level` format. See `OrderController` → `payment-service` → Kafka → `search-service` as the full trace shape.

### Event sourcing

Persist the sequence of state-changing events, not the current state. The current state is derived by replaying the events (optionally with periodic snapshots).
**Where it shows up in Samato**: `payment-service` only. The `events` table is the source of truth; `PaymentView` is the materialized read model. `Snapshotter` periodically saves the latest state to avoid full replays. See [payment-service.md](./services/payment-service.md).

### Idempotency

A request can be safely retried (network blip, client retry, Kafka redelivery) and produce the same result.
**Where it shows up in Samato**: 
- HTTP side: `Idempotency-Key` header on `POST /api/orders` → `IdempotencyRecord` table in `order-service`.
- Command side: `IdempotencyGuard.executeOnce(key, ...)` in `payment-service` checks the `processed_commands` table.
- Event side: every event has a unique `eventId`; consumers use it for dedup.

### MDC

SLF4J's Mapped Diagnostic Context. A per-thread `Map<String, String>` that is automatically included in every log line via the configured pattern.
**Where it shows up in Samato**: `MdcKeys` defines the four keys (`userId`, `correlationId`, `traceId`, `spanId`). The log pattern `"%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"` is the same across every service. The `try (var ctx = MdcContext.fromKafka(record))` pattern in consumers guarantees cleanup.

### Saga

A long-running business transaction that spans multiple services. Either all the steps succeed, or compensating actions undo the partial work.
**Where it shows up in Samato**: `order-service` has the saga. `SagaEngine` runs `SagaInstance` through `SagaStep`s (e.g. `VALIDATE_RESTAURANT`, `VALIDATE_ITEMS`, `RESERVE`, `PAY`, `CONFIRM`). Failure triggers the compensating step. See the saga tables in `order-service`.

### Saga (choreography vs orchestration)

- **Choreography**: each service reacts to events published by others, no central coordinator. Pros: loose coupling. Cons: hard to see the flow.
- **Orchestration**: a central saga service tells each participant what to do. Pros: visible flow, easier error handling. Cons: the orchestrator becomes the smart service.
**Where it shows up in Samato**: `order-service` is the orchestrator. It calls `restaurant-service` (via Feign), waits, then calls `payment-service` (via Feign), waits, then publishes the `OrderConfirmedEvent`. Other services are passive participants.

### Service registry
See Eureka.

### Transactional outbox

Solve the "DB write succeeded, Kafka publish failed" problem by inserting the event as a row in the same database transaction, then shipping it asynchronously.
**Where it shows up in Samato**: every publishing service has an `outbox_events` table. The pattern is identical across `order-service`, `payment-service`, `restaurant-service`: the business write + the outbox insert happen in the same `@Transactional`, then a `@Scheduled` poller ships the rows. See [restaurant-service.md](./services/restaurant-service.md#37-domainoutboxevent) and the Outbox class in [order-service](./services/order-service.md).

---

## Messaging / Kafka

### @KafkaListener
See @KafkaListener in Spring Boot fundamentals.

### AckMode (manual_immediate / manual / batch / record / count / time)

How a Kafka consumer commits offsets. `manual` and `manual_immediate` require the listener to call `Acknowledgment.acknowledge()`. `manual_immediate` acks after each record (fine-grained redelivery); `manual` acks after the whole batch.
**Where it shows up in Samato**: `search-service` uses `manual_immediate`. The shared-kafka `KafkaConsumerConfig` sets `MANUAL` but the search-service `application.yml` overrides with `listener.ack-mode: manual_immediate`. Note the anomaly: both are in play, intentionally.

### At-least-once delivery

The broker will redeliver a record if the consumer crashes before committing the offset. The consumer may therefore see the same record more than once. Consumers must be **idempotent** to handle this safely.
**Where it shows up in Samato**: every consumer. `restaurant-service` producer sets `enable.idempotence=true` and `acks=all` to guarantee no producer-side duplicates; the consumer side uses `enable.auto.commit=false` so a crash mid-processing triggers redelivery. The `restaurantId` is the OpenSearch document `_id` so re-delivery is a no-op upsert.

### Avro

A schema-first binary serialization format. Schemas live in `.avsc` files; the `avro-maven-plugin` generates Java classes; the `KafkaAvroSerializer` encodes the binary; `KafkaAvroDeserializer` decodes.
**Where it shows up in Samato**: eight `.avsc` files in `shared-kafka/src/main/avro/` (`OrderPlacedEvent`, `OrderConfirmedEvent`, `OrderCancelledEvent`, `PaymentChargedEvent`, `PaymentFailedEvent`, `PaymentRefundedEvent`, `RestaurantCreatedEvent`, `RestaurantUpdatedEvent`). The generated classes are in `com.samato.events` (note: not in the `com.samato.sharedkafka.events` package you might expect — that's the `DomainEvent` anomaly).

### ByteArrayDeserializer / ByteArraySerializer

Treat each message as a raw `byte[]`. The publisher and consumer are responsible for encoding/decoding the payload.
**Where it shows up in Samato**: `order-service` and `payment-service` use `KafkaTemplate<String, byte[]>` (not the `SpecificRecord` one from `shared-kafka`). Their `application.yml` sets `spring.kafka.producer.value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer`. The bytes are `Avro.toString(event).getBytes(UTF_8)` — **JSON, not Avro wire format**. See [shared-and-kafka.md](./services/shared-and-kafka.md#kafkatemplatestringbyte-in-orderpayment-services-vs-kafkatemplatestringspecificrecord-in-restaurant-service).

### Consumer

A client that subscribes to one or more Kafka topics and processes records. Samato's only consumer is `RestaurantEventListener` in `search-service`.

### Consumer group

A set of consumers that share the work for a topic. Kafka assigns each partition to exactly one consumer in the group. Adding a second consumer to the group rebalances and splits partitions.
**Where it shows up in Samato**: `samato-search-service` is the group id for the only consumer.

### Exactly-once semantics (EOS)

The strong guarantee that a record is processed exactly once. In practice, this is achieved with idempotent producers + transactional reads + idempotent consumers. End-to-end EOS in Kafka also requires using `read_committed` isolation and writing results back to Kafka transactionally.
**Where it shows up in Samato**: **not** implemented. Samato has at-least-once + idempotent consumers (see [search-service.md](./services/search-service.md#31-topics-consumed)). The Avro wire format supports it; the JSON-on-the-wire path in `order-service` and `payment-service` does not.

### Idempotent consumer

A consumer that is safe under redelivery. Typically achieved by keying writes on a stable id (record id, aggregate id) so the same input produces the same write.
**Where it shows up in Samato**: `RestaurantProjector` uses `restaurantId` as the OpenSearch document `_id`, so re-delivery is an upsert with the same content.

### KafkaTemplate.send

The high-level API for publishing. Returns a `CompletableFuture<SendResult>`; `.get()` blocks until the broker acks.
**Where it shows up in Samato**: `Outbox.publishPending` uses `kafkaTemplate.send(...).get(5, SECONDS)` — synchronous on purpose, so the poller knows whether the send succeeded before marking the row sent. `.whenComplete(...)` would be the async alternative.

### Key (Kafka)

The partitioning key. Two records with the same key go to the same partition and are processed in order. This is the only way to guarantee per-key ordering.
**Where it shows up in Samato**: every event uses the aggregate id as the key (`orderId`, `paymentId`, `restaurantId`). This is what makes per-restaurant ordering work in `search-service`.

### Offset

A per-partition cursor that records which records the consumer group has processed. On restart, the consumer resumes from the committed offset.
**Where it shows up in Samato**: stored by the consumer group on the broker; `auto-offset-reset: earliest` means a brand-new group with no committed offset starts from the beginning.

### Outbox poller

A scheduled task that reads unsent outbox rows, ships them to Kafka, and marks them sent. The bridge between the database transaction and the broker.
**Where it shows up in Samato**: every publishing service has one. `restaurant-service` polls every 1 s; `order-service` and `payment-service` poll every 500 ms (configurable via `samato.outbox.poll-ms`). See `Outbox` in [restaurant-service.md](./services/restaurant-service.md#313-comsamatorestaurantserviceserviceoutbox).

### Outbox table

The database table that holds events to be published. The schema is `id, aggregate_type, aggregate_id, topic, event_type, payload (bytes), sent_at (nullable), created_at`.
**Where it shows up in Samato**: `outbox_events` in `order-service`, `payment-service`, `restaurant-service`. `sent_at IS NULL` is the poller's predicate; the partial index `idx_outbox_unsent` is the index that makes the poll fast.

### Partition

A unit of parallelism within a Kafka topic. Records in a partition are totally ordered; ordering across partitions is not guaranteed. Topics are split across brokers as partitions.
**Where it shows up in Samato**: not directly configured; the broker defaults apply. The choice of key (aggregate id) is what matters.

### Producer

A client that publishes records. Samato producers are wired via `KafkaTemplate` from `shared-kafka` (`restaurant-service`) or via a hand-rolled `KafkaTemplate<String, byte[]>` (`order-service`, `payment-service`).

### Schema Registry

A service that stores Avro (or Protobuf/JSON-Schema) schemas and gives each a numeric id. Producers embed the id in the wire format; consumers fetch the schema by id and decode.
**Where it shows up in Samato**: Confluent Schema Registry (port 8081 in compose). Used by `restaurant-service` and `search-service` via the Avro serializer/deserializer. **Not** used by `order-service` and `payment-service` (their JSON bytes don't carry a schema id).

### SpecificRecord

An Avro generated class (vs `GenericRecord`). Has typed getters like `getRestaurantId()` and a builder.
**Where it shows up in Samato**: every generated class in `com.samato.events` is a `SpecificRecord`. The `KafkaTemplate<String, SpecificRecord>` from `shared-kafka` is the producer; `KafkaAvroDeserializer` with `specific.avro.reader=true` is the consumer.

### Topic

A named feed of records. Samato topic names use the prefix `samato.<service>.<event>` — e.g. `samato.restaurant.created`, `samato.order.placed`, `samato.payment.charged`.

---

## Auth

### /.well-known/jwks.json

A standard URL path that a JWT issuer (the authorization server) serves its public signing keys at. Resource servers fetch the keys from here to verify token signatures.
**Where it shows up in Samato**: `auth-service` exposes it; every resource server (gateway, user-service, restaurant-service, order-service, search-service, payment-service) fetches it via `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.

### Access token / Refresh token

- **Access token**: short-lived JWT sent on every API call in the `Authorization: Bearer ...` header.
- **Refresh token**: long-lived token used to mint a new access token when the old one expires.
**Where it shows up in Samato**: `auth-service` issues both via Spring Authorization Server. The bible focuses on the access-token path; the refresh path is via the standard `/oauth2/token` endpoint.

### Authority / Role / Scope

Spring Security's three flavors of "what can you do?":
- **Authority**: a single string like `ROLE_DRIVER`.
- **Role**: an authority with the `ROLE_` prefix.
- **Scope**: an OAuth2 scope like `orders:read`.
**Where it shows up in Samato**: roles are extracted from the JWT's `roles` claim and prefixed with `ROLE_` in every service's `SecurityConfig#jwtAuthenticationConverter`. `hasRole('DRIVER')` then works. Scopes are also supported by the converter but not currently used in `@PreAuthorize` expressions.

### client_credentials grant

OAuth2 flow for service-to-service auth. The client authenticates with id+secret, gets a token, no user involved.
**Where it shows up in Samato**: `auth-service` exposes it. Used by `order-service` and `payment-service` when they call each other over Feign (with a machine-to-machine token). The `OAuthClient` entity in `auth-service` stores the registered client.

### CORS (Cross-Origin Resource Sharing)

The browser-side rule for whether JS on `app.samato.com` can call `api.samato.com`. The server returns `Access-Control-Allow-*` headers saying "yes" or "no".
**Where it shows up in Samato**: `api-gateway` has `CorsConfig` that adds the headers. Every service is reachable from the browser only through the gateway, so the CORS config lives in one place.

### HMAC / HMAC-SHA256

A symmetric signature: same secret signs and verifies. Faster than asymmetric (RSA) but both sides need the secret.
**Where it shows up in Samato**: `payment-service` verifies Razorpay webhooks using HMAC-SHA256. The signature is in the `X-Razorpay-Signature` header; the secret is shared between Razorpay and the service. The `webhookFilterChain` runs with `@Order(HIGHEST_PRECEDENCE)` so it bypasses the JWT filter. See [payment-service.md](./services/payment-service.md).

### JWK / JWKS

- **JWK**: a single JSON Web Key (one public key).
- **JWKS**: a JSON Web Key Set — the document served at `/.well-known/jwks.json`, containing all currently valid public keys.
**Where it shows up in Samato**: `auth-service` exposes the JWKS; every other service downloads and caches it.

### JWT (JSON Web Token)

A signed, base64-encoded JSON object. Three parts: header, payload (claims), signature. Verify the signature with the issuer's public key.
**Where it shows up in Samato**: every authenticated request. Format: `Authorization: Bearer <token>`. Verified twice in `search-service` — once at the gateway, once at the service (defence in depth).

### JWT claims

Fields in the payload. Samato uses:
- `sub` — the user's UUID (the join key across services).
- `iss` — the issuer (`http://auth-service:9000` in compose).
- `aud` — the audience.
- `exp` — expiry (epoch seconds).
- `iat` — issued at.
- `roles` — the array of role names, used by Spring Security after `ROLE_` prefixing.
**Where it shows up in Samato**: `UserService` reads `sub` for the user id; `SecurityConfig` reads `roles` and turns each into a `GrantedAuthority`.

### JwtDecoder

The Spring Security class that validates an incoming JWT. Samato uses `NimbusJwtDecoder.withJwkSetUri(...)` which fetches the JWKS on first use and caches the keys.
**Where it shows up in Samato**: every `SecurityConfig#jwtDecoder` bean.

### OAuth2 / OIDC

- **OAuth2**: the protocol for delegated authorization ("let this app act on my behalf").
- **OIDC**: OAuth2 plus a standard identity layer (an `id_token` that proves who you are).
**Where it shows up in Samato**: Spring Authorization Server implements OAuth2 + OIDC. The bible uses the OAuth2 token endpoint; the OIDC userinfo endpoint is also available at `/api/auth/me`.

### RegisteredClient

Spring Authorization Server's model of an OAuth2 client. Has id, secret, grant types, scopes, redirect URIs.
**Where it shows up in Samato**: `OAuthClient` entity in `auth-service` plus a Spring `RegisteredClient` in-memory registration for the standard `client` client.

### Resource server

A service that **accepts** JWTs (vs. an authorization server that **issues** them). It validates the token, extracts the subject, and enforces authZ.
**Where it shows up in Samato**: every service except `auth-service` is a resource server. The api-gateway, user-service, restaurant-service, order-service, search-service, and payment-service all configure a `JwtDecoder` against auth-service's JWKS.

### RS256

A JWT signing algorithm — RSA with SHA-256, asymmetric. The issuer signs with a private key; verifiers check with the public key from the JWKS.
**Where it shows up in Samato**: `auth-service` uses RS256. The key pair is generated at startup; the public key is exposed via `/.well-known/jwks.json`.

### Scope

An OAuth2 permission string like `orders:read`. Distinct from a Role.
**Where it shows up in Samato**: the `scope` claim is parsed by the default `JwtGrantedAuthoritiesConverter` and turned into authorities prefixed `SCOPE_`. Currently the `@PreAuthorize` expressions use roles, not scopes.

### Spring Authorization Server

A Spring project that implements the OAuth2 authorization server spec. Issue tokens, serve JWKS, register clients, manage consent.
**Where it shows up in Samato**: `auth-service` (port 9000) is a Spring Authorization Server. See [auth-service.md](./services/auth-service.md).

---

## Domain concepts

### Menu / MenuItem

- **Menu**: the list of `MenuItem`s for one restaurant.
- **MenuItem**: one sellable product (name, description, price, available).
**Where it shows up in Samato**: `MenuItem` entity in `restaurant-service`. There is no `Menu` entity — the menu is just `MenuItemRepository.findByRestaurantId(restaurantId)`. See [restaurant-service.md](./services/restaurant-service.md#35-domainmenuitem).

### Money

A monetary amount. Samato uses `BigDecimal` everywhere (never `double` or `float`) and a `Money` record value object in `payment-service` (Razorpay uses paise — see `toPaise`).
**Where it shows up in Samato**: `MenuItem.price` is `BigDecimal(precision=10, scale=2)`. `Payment.amount` is also `BigDecimal`; the wire format to Razorpay is in paise (1 INR = 100 paise) as a string.

### Order / OrderItem

- **Order**: the customer's purchase intent. Has `id`, `customerId`, `restaurantId`, `totalAmount`, `status`, list of `OrderItem`.
- **OrderItem**: one line on an order — which `MenuItem`, how many, unit price at order time.
**Where it shows up in Samato**: `Order` and `OrderItem` entities in `order-service`. The order goes through `OrderStatus`: `PLACED → VALIDATED → RESERVED → PAID → CONFIRMED`, or `CANCELLED` on any failure.

### OrderStatus

The state machine for an order. Samato values: `PENDING`, `RESERVED`, `CONFIRMED`, `PAID`, `CAPTURED`, `CANCELLED`, `FAILED` (the exact set varies per service — `order-service` has `PLACED/VALIDATED/RESERVED/PAID/CONFIRMED/CANCELLED`, `payment-service` has `ORDER_CREATED/PAYMENT_INITIATED/CAPTURED/FAILED/REFUND_INITIATED/REFUNDED/EXPIRED`).
**Where it shows up in Samato**: enum in `order-service.domain` and `payment-service.domain`. The state machine is enforced in the saga and the payment command handler.

### Payment

A record of money movement. Has `id`, `orderId`, `customerId`, `razorpayOrderId`, `razorpayPaymentId`, `amount`, `currency`, `status`.
**Where it shows up in Samato**: `payment-service` is event-sourced — the `events` table is the source of truth; the `PaymentView` table is the projection.

### PaymentEvent

A state-change event for a payment. Types: `Charged`, `Refunded`, `Failed`. Each is published to a Kafka topic and persisted in the event store.
**Where it shows up in Samato**: see [shared-and-kafka.md](./services/shared-and-kafka.md#46-avro-schemas-sharedkafkasrcmainavroavsc). Six payment topics in code: `samato.payment.created`, `samato.payment.charged`, `samato.payment.failed`, `samato.payment.refund.initiated`, `samato.payment.refunded`, `samato.payment.expired`. Only three have `.avsc` files.

### Razorpay

The Indian payment gateway Samato integrates with. Provides order creation, capture, refund, and webhook callbacks.
**Where it shows up in Samato**: `payment-service` calls Razorpay's REST API. Webhook callbacks come in on a separate filter chain (HMAC-verified, not JWT-protected). See [payment-service.md](./services/payment-service.md).

### Restaurant

A business that sells food via the platform. Has owner, name, address, location, active flag, menu.
**Where it shows up in Samato**: `Restaurant` entity in `restaurant-service`. The `active` flag is a soft-delete — `DELETE /api/restaurants/{id}` sets `active = false`, it doesn't remove the row.

---

## Data / infrastructure

### AvroBytes

A package-private utility in `restaurant-service` that round-trips an Avro `SpecificRecord` through `DataFileWriter`/`DataFileReader` to bytes. Used to store the exact bytes in the `outbox_events.payload` column so the schema is pinned at write time.
**Where it shows up in Samato**: `restaurant-service/.../service/AvroBytes.java`. See [restaurant-service.md](./services/restaurant-service.md#312-comsamatorestaurantserviceserviceavrobytes).

### Cache-aside

The standard pattern: app reads from the cache first; on miss, reads from the DB and populates the cache. Writes invalidate the cache.
**Where it shows up in Samato**: `RestaurantService.get` is `@Cacheable`; `create/update/deactivate` are `@CacheEvict`. The `CacheManager` is Redis-backed.

### Flyway

The schema-migration tool. Reads SQL files from `classpath:db/migration` named `V1__init.sql`, `V2__add_column.sql`, etc. Runs them in order at startup and records applied migrations in `flyway_schema_history`.
**Where it shows up in Samato**: every service with a database. `spring.flyway.enabled: true` in `application.yml`, migrations under `src/main/resources/db/migration/`. The `baseline-on-migrate: true` setting lets Flyway handle pre-existing DBs on first run.

### Hibernate

The JPA implementation. Translates entity operations to SQL, manages the session/lifecycle, caches, dirty checking.
**Where it shows up in Samato**: `spring.jpa.hibernate.ddl-auto: validate` in every service — Hibernate checks that entities match the schema at startup but **never** alters tables. Schema is owned by Flyway.

### hibernate-types (hypersistence-utils)

A third-party library that adds JPA type mappings Hibernate doesn't ship with — JSON columns, ranges, etc.
**Where it shows up in Samato**: `payment-service` uses `@Type(JsonType.class)` to map `Map<String, Object>` and `List<...>` to Postgres `JSONB` columns. The `EventStoreEntry.payload` is one such field.

### JPA

Java Persistence API. The standard ORM spec. Hibernate is Samato's implementation.
**Where it shows up in Samato**: every `@Entity` and every `JpaRepository`.

### JSONB

Postgres's binary JSON column type. Indexable, queryable, but not free.
**Where it shows up in Samato**: `customer_profiles.preferences_json` in user-service; `EventStoreEntry.payload` and `PaymentView.metadata` in payment-service. All mapped via `@Type(JsonType.class)`.

### Liquibase

An alternative to Flyway for schema migrations. Uses XML/YAML/SQL changelogs. **Not used in Samato** — the bible standardizes on Flyway.

### OpenSearch

A search engine (Elasticsearch fork) for full-text, filter, and geo queries.
**Where it shows up in Samato**: `search-service` projects restaurant events into the `restaurants` OpenSearch index. `OpenSearchIndexInitializer` creates the index and mapping at startup. See [search-service.md](./services/search-service.md#33-configopensearchindexinitializerjava).

### Postgres (PostgreSQL)

The OLTP database. JSONB, partial indexes, foreign keys, transactions.
**Where it shows up in Samato**: 12 databases (one per service) on a single `postgres` container, provisioned by `init-databases.sh`. Postgres 16 in compose.

### Rate limiter

Caps request rate per client/IP to protect against abuse.
**Where it shows up in Samato**: `api-gateway` has `RateLimitConfig` (uses Redis as the counter backend).

### Redis

An in-memory data store used here as cache and rate-limit counter.
**Where it shows up in Samato**: `samato-redis:6379` in compose. Used by `restaurant-service` for the `restaurants` cache (`@Cacheable` via `CacheConfig#cacheManager`). Used by `api-gateway` for rate limiting. Future: will back `user-service` driver location writes.

### Schema Registry
See Schema Registry in the Messaging / Kafka section.

---

## Testing / Build

### @DataJpaTest
See @DataJpaTest in Spring Boot fundamentals.

### JUnit 5 (Jupiter)

The test framework. `@Test`, `@BeforeEach`, `@ParameterizedTest`, etc.
**Where it shows up in Samato**: every test class.

### Maven multi-module

One root `pom.xml` that lists `<modules>`; each module has its own `pom.xml` and inherits shared `<properties>` and `<dependencyManagement>`. Build with `mvn -pl <module> -am <goal>` (the `-am` also builds module dependencies).
**Where it shows up in Samato**: the root `pom.xml` lists `shared`, `shared-kafka`, `discovery-service`, `config-service`, `auth-service`, `api-gateway`, `user-service`, `restaurant-service`, `order-service`, `payment-service`, `search-service`, and the `services/` aggregator. Every service is `<packaging>jar</packaging>`.

### Mockito

The mocking framework. `mock(Class)`, `when(...).thenReturn(...)`, `verify(...)`.
**Where it shows up in Samato**: unit tests for service classes; Feign clients are mocked at the interface level, not over HTTP.

### Parent pom

The root `pom.xml` that defines `<properties>` (Java version, Spring Cloud version, Avro version, Flyway version) and `<dependencyManagement>` for BOMs.
**Where it shows up in Samato**: `pom.xml` at the repo root. Versions: `java.version=21`, `spring-cloud.version=2023.0.3`, `avro.version=1.11.3`, `schema-registry.version=7.6.1`, `flyway.version=10.20.0`. See the inventory.

### Spring Boot starter

A curated set of dependencies for a specific use case — `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-cloud-starter-openfeign`, etc.
**Where it shows up in Samato**: every service's `pom.xml` lists a handful of starters. The `shared` library itself depends on `spring-boot-starter-web` and `spring-boot-starter-actuator` so consumers get those transitively.

### Testcontainers

A library that spins up Docker containers (Postgres, Kafka, Redis) per test class. Real services, not mocks.
**Where it shows up in Samato**: integration tests that need a real Postgres or Kafka. Each test class typically uses `@Container static PostgreSQLContainer<?> POSTGRES = ...`.

### WireMock

A library that stubs HTTP endpoints. Used to fake Feign dependencies in tests.
**Where it shows up in Samato`: integration tests that exercise the Feign path (e.g. order-service tests stub `restaurant-service` and `payment-service`).

---

## See also

- [ARCHITECTURE.md](../ARCHITECTURE.md) — the system map.
- [01-architecture-guide.md](./01-architecture-guide.md) — pattern-by-pattern architecture tour.
- [02-how-auth-works.md](./02-how-auth-works.md) — the JWT lifecycle in detail.
- [services/](./services/) — the per-service walkthroughs that use every term above.
- [INTERVIEW-CHEATSHEET.md](./INTERVIEW-CHEATSHEET.md) — quick-reference answers.
