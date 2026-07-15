# shared-and-kafka (library — not a deployable service)

> Plain-English purpose: this page covers **two library modules** that every Java service in the Samato monorepo pulls in as a Maven dependency. There is **no running service** by this name and **no port**: the modules are pure JARs built into other services' classpaths. The page is split into a section per module.
>
> 1. `shared` — cross-cutting cross-service code: a uniform error type, a uniform JSON error response, a servlet `Filter` that stamps an `X-Correlation-Id` on every request, an Feign interceptor that propagates the same id on every outbound call, and the `MdcKeys` enum/constants used to log per-user/per-trace fields.
> 2. `shared-kafka` — Kafka producer/consumer beans wired with Avro + Confluent Schema Registry, an MDC-stamping `ProducerInterceptor`, an `MdcContext` helper for `@KafkaListener` methods, and the `.avsc` event schemas whose generated Java classes are the `SpecificRecord` payloads every topic carries.
>
> Design patterns demonstrated: **library-as-Maven-module** (no Spring `@SpringBootApplication` here), **cross-cutting observability via SLF4J MDC + Kafka headers**, **out-of-the-box Avro producer/consumer factories** (so every service gets identical reliability knobs), and the **outbox pattern** (services that publish events do so from a row in their own Postgres — see the notes on `byte[]` vs `SpecificRecord` at the bottom).

## 1. Where it sits in the system

ASCII diagram. Square brackets are other services / artifacts; arrows show how `shared` and `shared-kafka` feed them. `*` is "all services".

```
                        [root pom.xml]
                        version 1.0.0-SNAPSHOT, parent: spring-boot-starter-parent 3.3.4
                                  |
                  +---------------+---------------+
                  |                               |
            [shared.jar]                   [shared-kafka.jar]
            com.samato:shared              com.samato:shared-kafka
            (depends on: spring-web,       (depends on: shared,
             validation, actuator,         spring-kafka, avro,
             micrometer-tracing,           confluent kafka-avro-serializer)
             zipkin, openfeign)                       |
                  |                                   |
        +---------+---------+                         |
        |         |         |                         |
   [api-gateway] [order-   [user-                   (avro-maven-plugin
    uses HEADER  service]  service]                  generates Java from
    constant     uses      uses                       src/main/avro/*.avsc
    from its     Feign-    FeignCorrId               into
    own reactive CorrelationId                        target/generated-sources/avro/)
    WebFilter,   Interceptor                              |
    but the     bean                                  [OrderPlacedEvent,
    constant is                                          OrderConfirmedEvent,
    imported                                             OrderCancelledEvent,
    from this lib                                        PaymentChargedEvent,
                                                        PaymentFailedEvent,
                                                        PaymentRefundedEvent,
                                                        RestaurantCreatedEvent,
                                                        RestaurantUpdatedEvent]
                                                              |
   [restaurant-service] produces Avro events from its outbox via |
                         KafkaTemplate<String,SpecificRecord> --+
                         (bean defined in shared-kafka's
                          KafkaProducerConfig)

   [order-service]     publishes byte[]-encoded events to samato.order.*
                       (does NOT use the shared-kafka KafkaTemplate;
                        see "Anomalies" at the bottom)
   [payment-service]   publishes byte[] JSON to samato.payment.*
                       (also does NOT use the shared-kafka KafkaTemplate)

   [search-service]    consumes samato.restaurant.{created,updated}
                       using the ConcurrentKafkaListenerContainerFactory
                       from shared-kafka's KafkaConsumerConfig
```

Two important facts the diagram hides:

1. `shared` and `shared-kafka` are **library** Maven modules (no `main` method, no `@SpringBootApplication`, no port). The two `pom.xml` files live at the repo root: `shared/pom.xml` and `shared-kafka/pom.xml`. The task brief calls the combined unit "shared-and-kafka" but the on-disk path is `services/...`-less — see the **Anomalies** section at the end.
2. Only one service (`search-service`) actually consumes events. Three services (`order-service`, `payment-service`, `restaurant-service`) publish events. `order-service` and `payment-service` use a hand-rolled `KafkaTemplate<String,byte[]>` rather than the one in `shared-kafka`; the inventory flags this in §7.

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module(s) | `shared` (groupId `com.samato`, version `1.0.0-SNAPSHOT`) and `shared-kafka` (same groupId/version, depends on `shared`) |
| Port | none — these are library JARs, not deployable Spring Boot apps |
| Database(s) | none (no JPA, no Flyway, no datasource configured) |
| Publishes topics | none directly. Topic payloads are the Avro `SpecificRecord` classes in `shared-kafka/target/generated-sources/avro/`, published by the consuming services via the beans defined here. |
| Consumes topics | none directly. `shared-kafka` only ships the listener container factory; `@KafkaListener` methods live in `services/search-service/.../RestaurantEventListener.java` |
| REST endpoints | none (no `@RestController` in either module) |
| Depends on | (intra-repo) `shared` is depended on by every service in `services/`; `shared-kafka` is depended on by `restaurant-service`, `search-service`, `order-service`, `payment-service`. (external) `shared` pulls in `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `micrometer-tracing-bridge-brave`, `zipkin-reporter-brave`, `spring-cloud-starter-openfeign`. `shared-kafka` adds `spring-kafka`, `io.confluent:kafka-avro-serializer`, `org.apache.avro:avro`, plus the `avro-maven-plugin` build step. |
| Spring Boot app | none — these modules are classpath-only. There is no `main` method and no `application.yml` (neither module has a `src/main/resources/application.yml`). |

## 3. File-by-file walkthrough — `shared/` module

The `shared/` module has five Java files, in two packages: `com.samato.shared.errors` and `com.samato.shared.observability`.

### 3.1 `shared/src/main/java/com/samato/shared/errors/DomainException.java`

- **What it is** — a single `RuntimeException` subclass that carries a stable error **code** (string) and a target **HTTP status** (int) alongside the human-readable message.
- **Why it exists** — without it every service would invent its own exception hierarchy and clients would have to learn 12 different error shapes. Throwing a `DomainException` lets `GlobalExceptionHandler` (next file) render one consistent JSON error envelope to the caller.
- **Spring annotations** — none. It is a plain Java class, not a bean.
- **What it calls** — `super(message)` / `super(message, cause)` (the `RuntimeException` constructor).
- **What calls it** — explicitly `extends`d by `services/order-service/.../api/RestaurantUnavailableException.java`; constructed via `throw new DomainException("CODE", "...", 404)` from at least these services:
  - `services/order-service/.../service/OrderService.java`
  - `services/order-service/.../web/OrderController.java`
  - `services/auth-service/.../web/RegistrationController.java`
  - `services/restaurant-service/.../service/RestaurantService.java`
  - `services/user-service/.../client/AuthClient.java` (a Feign fallback)
  - `services/user-service/.../client/AuthClientFallback.java`
  - `services/user-service/.../web/CustomerProfileController.java`
- **Configuration keys** — none (no `@Value` or `@ConfigurationProperties`).

### 3.2 `shared/src/main/java/com/samato/shared/errors/GlobalExceptionHandler.java`

- **What it is** — a `@RestControllerAdvice` that turns any uncaught exception thrown by a `@RestController` method into a JSON error body.
- **Why it exists** — single place to standardize error responses. Without it, `RestaurantNotFoundException` in `restaurant-service` would render as a Spring default HTML page, while the same condition in `order-service` would render differently. Now they all return the same envelope.
- **Spring annotations** —
  - `@RestControllerAdvice`: registers this class as a global advice that runs around every `@RestController` method; its return values are written straight to the HTTP response.
- **What it calls** — `org.springframework.http.ResponseEntity`, `org.springframework.http.HttpStatus`, `org.springframework.web.bind.MethodArgumentNotValidException`, `org.slf4j.Logger`. Body is a `Map<String,Object>` with keys `timestamp`, `status`, `code`, `message`. The Javadoc mentions a `traceId` but the current implementation does **not** read Micrometer's `Tracer.currentSpan()` — see **Anomalies** below.
- **What calls it** — Spring's MVC dispatcher invokes it automatically when any controller in a service on the classpath throws an exception matching the three `@ExceptionHandler` methods:
  - `DomainException` → renders `code` + `httpStatus` from the exception.
  - `MethodArgumentNotValidException` (Bean Validation failures on `@RequestBody` DTOs) → renders `400 VALIDATION_FAILED` plus a comma-joined list of `field: message`.
  - `Exception` (catch-all) → renders `500 INTERNAL_ERROR` and logs the stack.
- **Configuration keys** — none.

### 3.3 `shared/src/main/java/com/samato/shared/observability/CorrelationIdFilter.java`

- **What it is** — a `OncePerRequestFilter` (i.e. runs once per HTTP request) that reads or generates an `X-Correlation-Id` and stuffs it into SLF4J MDC for the lifetime of the request, then writes the same id back on the response.
- **Why it exists** — lets you `grep "correlationId=<id>"` across all log files to follow one request through every service. Without it, log lines from the same saga step would look unrelated.
- **Spring annotations** —
  - `@Component`: tells Spring to discover and instantiate this class as a bean. Servlet containers then auto-register any `Filter` bean into the filter chain.
  - `@Order(Ordered.HIGHEST_PRECEDENCE)`: run this filter before any other, so the MDC is populated by the time anything else logs.
- **What it calls** — `org.slf4j.MDC.put/remove`, `jakarta.servlet.http.HttpServletRequest/Response`, `jakarta.servlet.FilterChain`. Exposes one public constant: `public static final String HEADER = "X-Correlation-Id"`.
- **What calls it** — the servlet container picks it up automatically because it is a `Filter` bean. Note: **it does NOT run on the WebFlux stack** — the API gateway (which is reactive) has its own mirror in `services/api-gateway/src/main/java/com/samato/apigateway/config/CorrelationIdWebFilter.java` that imports this class only to use the same `HEADER` constant string. See the "Anomalies" section for why this matters.
- **Configuration keys** — none (header name is hard-coded as the constant `HEADER`).

### 3.4 `shared/src/main/java/com/samato/shared/observability/FeignCorrelationIdInterceptor.java`

- **What it is** — a `feign.RequestInterceptor` that copies the current thread's MDC correlation id (and Micrometer's `traceId` / `spanId`) onto every outgoing Feign HTTP call.
- **Why it exists** — services that call each other via Feign (e.g. `order-service` calling `restaurant-service`) would otherwise generate independent correlation ids in each downstream service. The interceptor chains the same id forward so the saga stays correlatable.
- **Spring annotations** — none. It is instantiated as a bean by the service that wants to use it (e.g. `order-service`'s `FeignAuthForwarder#correlationIdInterceptor()` returns `new FeignCorrelationIdInterceptor()`).
- **What it calls** — `feign.RequestTemplate.header`, `org.slf4j.MDC.get/put`, `MdcKeys.CORRELATION_ID/TRACE_ID/SPAN_ID`, `CorrelationIdFilter.HEADER`. If MDC is empty (e.g. on a background scheduler thread) it generates a fresh UUID and stores it in MDC so the downstream call still has one.
- **What calls it** — explicitly instantiated and exposed as a `@Bean RequestInterceptor` in:
  - `services/order-service/src/main/java/com/samato/orderservice/api/FeignAuthForwarder.java` (line 69)
  - `services/user-service/src/main/java/com/samato/userservice/config/FeignConfig.java`
- **Configuration keys** — none (header names are hard-coded: `X-Correlation-Id`, `X-traceId`, `X-spanId`).

### 3.5 `shared/src/main/java/com/samato/shared/observability/MdcKeys.java`

- **What it is** — a `final` class with four `public static final String` constants for MDC keys (`userId`, `correlationId`, `traceId`, `spanId`) and one helper `newCorrelationId()`.
- **Why it exists** — one place to rename an MDC key if you ever need to. Also documents the four log-line fields every service can rely on. The Javadoc is the source of truth for "what is in our log lines".
- **Spring annotations** — none (pure utility class, private constructor).
- **What it calls** — `org.slf4j.MDC.put`, `java.util.UUID.randomUUID`.
- **What calls it** — referenced by:
  - `CorrelationIdFilter` (puts `CORRELATION_ID`)
  - `FeignCorrelationIdInterceptor` (reads `CORRELATION_ID`, `TRACE_ID`, `SPAN_ID`; writes `CORRELATION_ID` if missing)
  - `services/api-gateway/.../CorrelationIdWebFilter.java` (reads/writes `CORRELATION_ID`)
  - `shared-kafka/.../observability/KafkaMdcProducerInterceptor.java` (reads/writes all four)
  - `shared-kafka/.../observability/MdcContext.java` (reads/writes the three id fields)
- **Configuration keys** — none.

### 3.6 `shared/src/main/resources/` — empty (no `application.yml`, no `db/migration`)

`shared` is a library and ships no configuration of its own. Every consuming service has its own `application.yml`; the Micrometer/Zipkin tracing config lives in `config-repo/application.yml` (served by `config-service`) and is documented per service.

### 3.7 `shared/pom.xml` highlights

- `groupId`: `com.samato`, `artifactId`: `shared`, `version`: `1.0.0-SNAPSHOT`, parent: `com.samato:samato` (the root pom).
- Key dependencies (versions inherited from Spring Boot's BOM or the root `<properties>`):
  - `org.springframework.boot:spring-boot-starter-web`
  - `org.springframework.boot:spring-boot-starter-validation`
  - `org.springframework.boot:spring-boot-starter-actuator`
  - `io.micrometer:micrometer-tracing-bridge-brave` (set in root properties to `1.3.4`)
  - `io.zipkin.reporter2:zipkin-reporter-brave` (set in root properties to `3.4.0`)
  - `org.springframework.cloud:spring-cloud-starter-openfeign` (BOM at `2023.0.3`)
  - `org.springframework.boot:spring-boot-starter-test` (test scope)
- No plugins, no `<build>` block.

## 4. File-by-file walkthrough — `shared-kafka/` module

The `shared-kafka/` module has five Java files plus eight `.avsc` schemas whose generated Java classes are dropped into `target/generated-sources/avro/`. Java packages: `com.samato.sharedkafka.config`, `com.samato.sharedkafka.events`, `com.samato.sharedkafka.observability`.

### 4.1 `shared-kafka/src/main/java/com/samato/sharedkafka/config/KafkaProducerConfig.java`

- **What it is** — a `@Configuration` class that exposes two beans: a `ProducerFactory<String,SpecificRecord>` (the raw Kafka producer factory) and a `KafkaTemplate<String,SpecificRecord>` (the high-level helper most service code uses to send messages).
- **Why it exists** — every service that publishes Kafka events should be using the **same** serializer, schema-registry URL, idempotence settings, compression type, and interceptor. Duplicating this in 12 services means a "tune reliability" change has to be applied 12 times. With this bean, a service just `private final KafkaTemplate<String,SpecificRecord> kafkaTemplate;` and the right defaults come along.
- **Spring annotations** —
  - `@Configuration`: marks this class as a source of `@Bean` definitions; Spring will scan it on startup.
- **What it calls** —
  - `io.confluent.kafka.serializers.KafkaAvroSerializer` (Confluent's Avro encoder that talks to the Schema Registry)
  - `org.apache.kafka.common.serialization.StringSerializer` (encodes the message key as a UTF-8 string)
  - `KafkaMdcProducerInterceptor` — registered via `ProducerConfig.INTERCEPTOR_CLASSES_CONFIG` (it stamps `X-Correlation-Id` on every outgoing record)
  - `MdcKeys` (via the interceptor)
- **What calls it** — Spring Boot's auto-configuration scans the classpath, finds this `@Configuration`, and registers the two beans. They are auto-wired into:
  - `services/restaurant-service/.../service/Outbox.java` — `KafkaTemplate<String, SpecificRecord> kafkaTemplate` field, used to publish `RestaurantCreatedEvent` / `RestaurantUpdatedEvent` to `samato.restaurant.{created,updated}`. This is the **only** service that uses the shared-kafka `KafkaTemplate` for sending.
- **Configuration keys it reads** —
  - `spring.kafka.bootstrap-servers` (default `localhost:9092` via `@Value("${spring.kafka.bootstrap-servers:localhost:9092}")`)
  - `spring.kafka.properties.schema.registry.url` (default `http://localhost:8085`)
  - Hard-coded reliability knobs (in the bean body): `acks=all`, `enable.idempotence=true`, `retries=Integer.MAX_VALUE`, `max.in.flight.requests.per.connection=5`, `compression.type=lz4`, `linger.ms=20`, `batch.size=32KB`, `auto.register.schemas=true`, `use.latest.version=true`.

### 4.2 `shared-kafka/src/main/java/com/samato/sharedkafka/config/KafkaConsumerConfig.java`

- **What it is** — a `@Configuration` class that exposes two beans: a `ConsumerFactory<String,SpecificRecord>` and a `ConcurrentKafkaListenerContainerFactory<String,SpecificRecord>`. The latter is what `@KafkaListener` methods use to receive messages.
- **Why it exists** — same reasoning as the producer config: a single place to set the consumer's reliability knobs (manual ack, earliest offset, max-poll-records, session timeout).
- **Spring annotations** —
  - `@Configuration`.
- **What it calls** —
  - `io.confluent.kafka.serializers.KafkaAvroDeserializer` (uses the `SpecificAvroReader` so that payloads are returned as concrete `SpecificRecord` subclasses, not `GenericRecord`).
  - `org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL` — the listener must call `Acknowledgment.acknowledge()` itself once processing succeeds.
- **What calls it** — auto-wired into:
  - `services/search-service/.../projection/RestaurantEventListener.java` — the only `@KafkaListener` in the system. It listens on `samato.restaurant.created` and `samato.restaurant.updated` (group `samato-search-service`) and acks manually after the OpenSearch projection succeeds.
- **Configuration keys it reads** —
  - `spring.kafka.bootstrap-servers` (default `localhost:9092`)
  - `spring.kafka.properties.schema.registry.url` (default `http://localhost:8085`)
  - Hard-coded consumer knobs: `auto.offset.reset=earliest`, `enable.auto.commit=false`, `max.poll.records=50`, `session.timeout.ms=30_000`. The `search-service` `application.yml` overrides some of these (e.g. it sets `ack-mode: manual_immediate` at the listener-property level rather than using the `MANUAL` enum set here — see **Anomalies**).

### 4.3 `shared-kafka/src/main/java/com/samato/sharedkafka/events/DomainEvent.java`

- **What it is** — an empty marker `interface` that just extends `org.apache.avro.specific.SpecificRecord`.
- **Why it exists** — gives every Avro-generated event class a common supertype so the producer's `KafkaTemplate<String, SpecificRecord>` works uniformly. Also serves as a documented extension point if you ever want to add `eventId()` / `aggregateId()` to the contract (the Javadoc explains why we deliberately do not add abstract methods — Avro's code generator does not let you declare a custom parent interface in `.avsc`).
- **Spring annotations** — none.
- **What it calls** — `org.apache.avro.specific.SpecificRecord` (the Avro type).
- **What calls it** — **no Java class imports `DomainEvent` at the time of writing.** Every service that publishes or consumes events uses the concrete generated class (e.g. `com.samato.events.OrderPlacedEvent`) directly. This is the second anomaly flagged by the inventory — see "Anomalies" at the bottom.
- **Configuration keys** — none.

### 4.4 `shared-kafka/src/main/java/com/samato/sharedkafka/observability/KafkaMdcProducerInterceptor.java`

- **What it is** — a Kafka `ProducerInterceptor<Object,Object>` (a class Kafka calls on every outgoing record before it goes on the wire) that copies the current thread's MDC values onto the record's headers.
- **Why it exists** — without it, an event published by service A would have a correlation id of A's choice; service B consuming the event would have a *different* correlation id of B's choice. The interceptor copies the **producer's** correlation id as a header (`X-Correlation-Id`) so the consumer can read it back via `MdcContext.fromKafka(record)` and pick up the same id in its own MDC.
- **Spring annotations** — none. It is registered with the producer purely by class name in `KafkaProducerConfig#producerFactory()` (Kafka's `ProducerConfig.INTERCEPTOR_CLASSES_CONFIG` is a `List<String>`).
- **What it calls** — `org.apache.kafka.clients.producer.ProducerRecord.headers().add(name, value.getBytes())`, `MDC.get/put`, `MdcKeys.CORRELATION_ID/TRACE_ID/SPAN_ID`. If MDC has no correlation id, it generates a fresh UUID (e.g. for background poller threads).
- **What calls it** — the Kafka client itself, on every `send()`. Wired in via the producer factory above.
- **Configuration keys** — none. (Header names are hard-coded: `X-Correlation-Id`, `X-Trace-Id`, `X-Span-Id`.)

### 4.5 `shared-kafka/src/main/java/com/samato/sharedkafka/observability/MdcContext.java`

- **What it is** — a small `AutoCloseable` helper class used inside `@KafkaListener` methods to set MDC values for the duration of one record's processing and then restore the prior MDC state.
- **Why it exists** — Kafka consumer threads are pooled and reused. If you set MDC and forget to clear it, the next record processed on the same thread will see stale correlation data. Wrapping the work in `try (var ctx = MdcContext.fromKafka(record)) { ... }` guarantees cleanup. (This is the "try-with-resources for MDC" pattern.)
- **Spring annotations** — none.
- **What it calls** — reads Kafka record headers `X-Correlation-Id`, `X-Trace-Id`, `X-Span-Id` (the same names the producer interceptor writes), `MDC.put/remove`, `MdcKeys.CORRELATION_ID/TRACE_ID/SPAN_ID`.
- **What calls it** — `services/search-service/src/main/java/com/samato/searchservice/projection/RestaurantEventListener.java` — the only consumer in the system. The Javadoc in this file shows the exact try-with-resources shape.
- **Configuration keys** — none.

### 4.6 Avro schemas (`shared-kafka/src/main/avro/*.avsc`)

Each `.avsc` file in `shared-kafka/src/main/avro/` is the source of truth for one event's shape. The `avro-maven-plugin` (configured in `shared-kafka/pom.xml` with `stringType=String`) runs during the `generate-sources` phase and writes the corresponding Java class into `shared-kafka/target/generated-sources/avro/com/samato/events/<Name>.java`. The **namespace is `com.samato.events`** for every schema. Below is a per-schema reference.

| `.avsc` file | Generated Java class | Topic | Key on wire | Producer (caller) | Consumer |
|---|---|---|---|---|---|
| `OrderPlacedEvent.avsc` | `com.samato.events.OrderPlacedEvent` | `samato.order.placed` | `orderId` | `services/order-service/.../outbox/OutboxPublisher#appendOrderPlaced` (via `KafkaTemplate<String,byte[]>` — see anomaly) | none |
| `OrderConfirmedEvent.avsc` | `com.samato.events.OrderConfirmedEvent` | `samato.order.confirmed` | `orderId` | `order-service/OutboxPublisher#appendOrderConfirmed` | none |
| `OrderCancelledEvent.avsc` | `com.samato.events.OrderCancelledEvent` | `samato.order.cancelled` | `orderId` | `order-service/OutboxPublisher#appendOrderCancelled` | none |
| `PaymentChargedEvent.avsc` | `com.samato.events.PaymentChargedEvent` | `samato.payment.charged` | `paymentId` | `payment-service/OutboxPublisher` (raw JSON bytes — no Avro on the wire here) | none |
| `PaymentFailedEvent.avsc` | `com.samato.events.PaymentFailedEvent` | `samato.payment.failed` | `paymentId` | `payment-service/OutboxPublisher` (raw JSON) | none |
| `PaymentRefundedEvent.avsc` | `com.samato.events.PaymentRefundedEvent` | `samato.payment.refunded` | `paymentId` | `payment-service/OutboxPublisher` (raw JSON) | none |
| `RestaurantCreatedEvent.avsc` | `com.samato.events.RestaurantCreatedEvent` | `samato.restaurant.created` | `restaurantId` | `restaurant-service/Outbox#enqueueRestaurantCreated` via the `shared-kafka` `KafkaTemplate<String,SpecificRecord>` | `search-service/RestaurantEventListener#onEvent` |
| `RestaurantUpdatedEvent.avsc` | `com.samato.events.RestaurantUpdatedEvent` | `samato.restaurant.updated` | `restaurantId` | `restaurant-service/Outbox#enqueueRestaurantUpdated` | `search-service/RestaurantEventListener#onEvent` |

#### 4.6.1 Field reference (one per schema)

- **`OrderPlacedEvent.avsc`** — `eventId: string`, `occurredAt: long` (epoch millis), `orderId: string`, `customerId: string`, `restaurantId: string`, `totalAmount: double`, `currency: string`, `itemCount: int`. Doc: "Emitted when an order is first placed. Kitchen / payment / delivery services react."
- **`OrderConfirmedEvent.avsc`** — `eventId`, `occurredAt`, `orderId`, `customerId`, `restaurantId`, `totalAmount`, `currency`.
- **`OrderCancelledEvent.avsc`** — `eventId`, `occurredAt`, `orderId`, `customerId`, `reason: string`.
- **`PaymentChargedEvent.avsc`** — `eventId`, `occurredAt`, `paymentId`, `orderId`, `customerId`, `razorpayOrderId`, `razorpayPaymentId`, `amount: string` (note: string, not numeric — preserves Razorpay's paise precision), `currency: string`.
- **`PaymentFailedEvent.avsc`** — `eventId`, `occurredAt`, `paymentId`, `orderId`, `customerId`, `razorpayOrderId`, `errorCode: string`, `errorDescription: string`, `amount: string`, `currency: string`.
- **`PaymentRefundedEvent.avsc`** — `eventId`, `occurredAt`, `paymentId`, `orderId`, `customerId`, `razorpayOrderId`, `razorpayRefundId`, `amount: string`, `currency: string`.
- **`RestaurantCreatedEvent.avsc`** — `eventId`, `occurredAt`, `restaurantId`, `ownerId`, `name`, `description: [null, string]` (nullable union with `default: null`), `cuisine`, `address`, `city`, `latitude: double`, `longitude: double`. Doc: "Emitted when a new restaurant is created. Search-service projects this into OpenSearch."
- **`RestaurantUpdatedEvent.avsc`** — same as above minus `ownerId`.

#### 4.6.2 Example payload (Avro-encoded)

The generated `OrderPlacedEvent` class is at `shared-kafka/target/generated-sources/avro/com/samato/events/OrderPlacedEvent.java`. It is a `SpecificRecordBase`. Code that builds one looks like this:

```java
OrderPlacedEvent ev = OrderPlacedEvent.newBuilder()
    .setEventId(UUID.randomUUID().toString())
    .setOccurredAt(Instant.now().toEpochMilli())
    .setOrderId("9c1b...")
    .setCustomerId("u-42")
    .setRestaurantId("r-7")
    .setTotalAmount(249.50)
    .setCurrency("INR")
    .setItemCount(3)
    .build();
```

When published via the `shared-kafka` `KafkaTemplate<String, SpecificRecord>`, the value is wrapped in Confluent's wire format (`magic byte + 4-byte schema id + Avro binary`) — the Schema Registry guarantees the schema id is resolvable. When `order-service` and `payment-service` publish (see anomaly below), the value is just `Avro.toString(ev).getBytes(UTF_8)` (JSON), no magic byte, no schema id.

#### 4.6.3 What calls the generated classes

- Restaurant Avro classes (`RestaurantCreatedEvent`, `RestaurantUpdatedEvent`) — produced by `restaurant-service/Outbox` and consumed by `search-service/RestaurantEventListener`.
- Order Avro classes (`OrderPlacedEvent`, `OrderConfirmedEvent`, `OrderCancelledEvent`) — produced by `order-service/OutboxPublisher` (as bytes, not via the shared-kafka `KafkaTemplate`). **No consumer exists yet.**
- Payment Avro classes — schemas are defined here; the on-the-wire payload is a separate JSON event from `payment-service`'s event store, so the generated classes are not actually serialized on the wire. `payment-service/PaymentCommandHandler.topicFor(...)` does a `switch (eventType)` that maps the internal event-type name to the topic name; the Avro class is unused at runtime (the inventory calls these "topics declared in code but not in shared schemas" — see the cross-reference file `kafkaTopicCrossReference.topicsDeclaredInCodeButNotInSharedSchemas`).

### 4.7 `shared-kafka/pom.xml` highlights

- `groupId`: `com.samato`, `artifactId`: `shared-kafka`, `version`: `1.0.0-SNAPSHOT`, parent: `com.samato:samato`.
- Dependencies:
  - `com.samato:shared` (intra-repo, must be on the classpath before `KafkaProducerConfig` can reference `KafkaMdcProducerInterceptor`)
  - `org.springframework.kafka:spring-kafka`
  - `io.confluent:kafka-avro-serializer` (version `${schema-registry.version}` = `7.6.1` from root `<properties>`)
  - `org.apache.avro:avro` (version `${avro.version}` = `1.11.3`)
  - `org.springframework.boot:spring-boot-starter-actuator`
- Build plugin: `org.apache.avro:avro-maven-plugin` bound to the `generate-sources` phase, `sourceDirectory = ${project.basedir}/src/main/avro/`, `outputDirectory = ${project.build.directory}/generated-sources/avro/`, `stringType=String` (so the generated getters return `java.lang.String`, not `org.apache.avro.util.Utf8`).

## 5. Endpoints (controllers)

There are no `@RestController` classes in either module. `shared` and `shared-kafka` are libraries; they expose beans and constants to other services, not HTTP routes. The `GlobalExceptionHandler` is a `@RestControllerAdvice` — that is a Spring MVC exception translator, not a request handler, so it does not define any endpoints.

For a list of every endpoint in the system, see `../01-architecture-guide.md` (or the per-service guides in this folder).

## 6. Database schema

Neither module has a database. There is no `db/migration` folder under either `shared/src/main/resources/` or `shared-kafka/src/main/resources/`, and no Flyway / JPA / JDBC dependency is declared in either `pom.xml`. The only "store" of any kind is the **outbox table in each publishing service** (e.g. `outbox_events` in `order-service`, `payment-service`, `restaurant-service`) — but those tables live in those services' migrations, not here. See the `call-graph.json` field `outboxTables` for the full schema.

## 7. Kafka integration

### Topics

- **No producer code in this module.** The producer *configuration* lives in `KafkaProducerConfig`, but no `kafkaTemplate.send(...)` call lives in `shared-kafka`. Every actual `.send(...)` happens in a service that depends on `shared-kafka`:
  - `services/restaurant-service/.../service/Outbox.java` — sends `SpecificRecord` payloads to `samato.restaurant.{created,updated}`. Polls every 1 second (`@Scheduled(fixedDelay = 1000L)`), batch size 100, sync send with 5s timeout (`kafkaTemplate.send(...).get(5, SECONDS)`).
  - `services/order-service/.../outbox/OutboxPublisher.java` — sends **byte[]** payloads to `samato.order.{placed,confirmed,cancelled}`. The bytes are `Avro.toString(event).getBytes(UTF_8)` (JSON, NOT Confluent wire format). Polls every 500 ms (configurable via `samato.outbox.poll-ms`).
  - `services/payment-service/.../outbox/OutboxPublisher.java` — sends **byte[]** JSON payloads to `samato.payment.{created,charged,failed,refund.initiated,refunded,expired}`. Polls every 500 ms (configurable).
- **No consumer code in this module.** The consumer *configuration* lives in `KafkaConsumerConfig`. The only `@KafkaListener` in the system is `services/search-service/.../projection/RestaurantEventListener.java#onEvent`, which subscribes to `samato.restaurant.created` and `samato.restaurant.updated` (group id `samato-search-service`) and uses `MdcContext.fromKafka(record)` to restore the producer's correlation id into MDC.

### Outbox table

This module ships **no outbox table**. Each service that needs one defines its own — see the per-service docs for `order-service`, `payment-service`, and `restaurant-service` for the `outbox_events` table schemas and the `OutboxPublisher` poller. The `shared-kafka` module simply provides the `KafkaTemplate` that those outbox pollers call into.

### Idempotency / at-least-once

- Producer side: `enable.idempotence=true` + `acks=all` (in `KafkaProducerConfig`) — Kafka guarantees no duplicate writes from a single producer session. Combined with the outbox in the publisher service, the system has at-least-once delivery with deduplication at the producer.
- Consumer side: `enable.auto.commit=false` + `AckMode.MANUAL` — the listener must call `Acknowledgment.acknowledge()` after the work succeeds. The `search-service` listener projects into OpenSearch and only acks after the upsert succeeds, so a crash mid-processing will redeliver the same record. OpenSearch upsert is idempotent by `restaurantId`, so the projection is safe under at-least-once.

## 8. Common "if you change X, also update Y" notes

The non-obvious cross-file dependencies — these are the gotchas that cost a beginner hours.

1. **If you change a header name in `KafkaMdcProducerInterceptor.onSend`, you must change it in `MdcContext.fromKafka` too.** The producer writes `X-Correlation-Id`, `X-Trace-Id`, `X-Span-Id`. The consumer reads exactly those same three header names. They are not defined as constants — they are literal strings in two files.
2. **If you add a new MDC key to `MdcKeys`, also add a `copyIfPresent` line in `FeignCorrelationIdInterceptor`** (and decide if you want it on outbound Feign calls) **and an entry in `MdcContext` for inbound Kafka records.** All three places must agree.
3. **If you bump the `stringType` setting in the avro-maven-plugin (it is currently `String`), all generated classes change.** `getEventId()` will return `Utf8` instead of `String`. Any service that uses `String` directly on the returned value will break.
4. **If you add a new field to any `.avsc`, the new field must be added with a default value OR every producer must be updated in the same release.** Otherwise the deserializer on the consumer side will fail with a "missing field" error. The restaurant schemas already use the `["null","string"]` union with `default: null` pattern for nullable fields — follow that convention.
5. **`CorrelationIdFilter` does NOT run on the WebFlux stack.** If you ever turn any other service into a reactive app, you must add a reactive `WebFilter` mirror like `api-gateway`'s `CorrelationIdWebFilter`. The `HEADER` constant is imported for that reason.
6. **`GlobalExceptionHandler` does not include `traceId` in its response body** even though the Javadoc says it does. If you need traceId in error responses, call `Tracer.currentSpan().context().traceId()` and add it to the `body()` map. The inventory flagged this.
7. **`KafkaConsumerConfig` sets `AckMode.MANUAL`, but `search-service/application.yml` overrides with `listener.ack-mode: manual_immediate`** at the Spring Boot property level. If you change the shared-kafka default, the override will keep working (it is set explicitly in the service), but be aware there are two ack modes in play.
8. **`DomainEvent` is currently unused.** No Java class imports it. It exists for the future. If you want to use it, the right pattern is to update the `avro-maven-plugin` to a custom `interface` parameter (the plugin does support this), or to add abstract methods via a `specificData.addLogicalType` hack. The Javadoc explains why the author chose not to do this.
9. **The `services/shared-and-kafka/` path mentioned in the task brief does not exist on disk.** The actual modules are at the repo root: `shared/` and `shared-kafka/`. This doc is at `docs/services/shared-and-kafka.md` (the standard docs path), but the `INTERVIEW-NOTES.md` it would link to (`services/shared-and-kafka/docs/INTERVIEW-NOTES.md`) does not exist either — see the **Anomalies** section.
10. **`order-service` and `payment-service` publish via `KafkaTemplate<String,byte[]>`, not the `KafkaTemplate<String,SpecificRecord>` from this module.** Their outbox bytes are `Avro.toString(ev).getBytes(UTF_8)`, which is JSON — NOT Confluent wire format. So a future consumer of `samato.order.placed` cannot use the `shared-kafka` `KafkaAvroDeserializer` directly; it would have to JSON-decode and then `new OrderPlacedEvent(record)` (Avro supports JSON decoding). The inventory's `kafkaTopicCrossReference.topicsDeclaredInCodeButNotInSharedSchemas` field is a related symptom.

## Anomalies (and how to interpret them)

These are the things the inventory agent flagged. I'm calling them out so a future maintainer doesn't get confused.

- **`GlobalExceptionHandler` is not imported by any service in the on-disk scan.** The phase-1 inventory says `importedBy: []` for this class. Yet every service that throws a `DomainException` clearly works in production. The reason: most services have **their own** `GlobalExceptionHandler` in their own `errors` package (the inventory also finds copies in `order-service/.../web/`, `user-service/.../web/`, etc.). The shared class is the *reference implementation*. If you add a new error-mapping method, decide whether to put it in the shared class (and have services depend on it) or in each service's local copy.
- **`CorrelationIdFilter` is auto-registered by Spring because it is a `@Component` and a `Filter`.** There is no explicit `FilterRegistrationBean` anywhere. That means **every service that pulls in `shared` as a dependency gets the filter for free** — including services that don't want it. The api-gateway deliberately does NOT use it (it is reactive) and instead defines its own `CorrelationIdWebFilter`. If you want to opt out of the servlet filter, you would have to either: (a) exclude the class via `@ComponentScan(excludeFilters = ...)`, or (b) make `shared` a non-`@Component` and have each service declare its own `FilterRegistrationBean`. As of this writing, no service opts out.
- **`DomainEvent` is never implemented by any class on disk.** The Avro Maven plugin does not have an option to set a custom super-interface for the generated classes (you'd have to use a custom Avro `Protocol` or a manual post-processor). The shared-kafka author documented this choice in the Javadoc; treat the interface as a placeholder / a "documented intent".
- **`KafkaTemplate<String,byte[]>` in order/payment services vs `KafkaTemplate<String,SpecificRecord>` in restaurant service.** This is the biggest operational anomaly. Three things to know:
  1. `restaurant-service` is the only one that actually uses Confluent's Avro wire format on the wire (because it's the only one that uses the `shared-kafka` `KafkaTemplate`). Its `Outbox` class even has a method called `AvroBytes.encode` / `decode` that round-trips the `SpecificRecord` through a `ByteBuffer` for storage in the outbox row.
  2. `order-service` and `payment-service` are the legacy / pre-Avro path: their `OutboxPublisher` stores `Avro.toString(event).getBytes(UTF_8)` (JSON text) in the `payload` bytea column. The on-wire format is **JSON text**, not Avro. Schema Registry is not involved for these topics.
  3. The `spring.kafka.producer.value-serializer` in those two services' `application.yml` is `org.apache.kafka.common.serialization.ByteArraySerializer`, which is consistent with the byte[]-on-the-wire approach. In contrast, `restaurant-service` overrides nothing — it relies on `shared-kafka`'s `KafkaAvroSerializer`.
  4. The Avro classes in `shared-kafka/target/generated-sources/avro/com/samato/events/` for `OrderPlacedEvent`, `OrderConfirmedEvent`, `OrderCancelledEvent`, `PaymentChargedEvent`, `PaymentFailedEvent`, `PaymentRefundedEvent` are therefore used at *build time* (for `.toString()` shape) but not at *transport time*. The schemas are documentation; the wire format is JSON.
- **The `MdcContext` Javadoc references a `KafkaMdcConsumerInterceptor`** that does not exist on disk. The intent — restore MDC on the consumer side — is actually implemented by `MdcContext.fromKafka(record)` plus the try-with-resources pattern shown in the Javadoc. The "interceptor" name is a stale comment from an earlier design.
- **The `shared-kafka` `KafkaConsumerConfig` is currently consumed by exactly one service** (`search-service`). The other three Kafka-touching services (`restaurant-service`, `order-service`, `payment-service`) define their own consumer/producer configuration inline. This means a change to `KafkaConsumerConfig` (e.g. switch to `enable.auto.commit=true`) only affects `search-service`. When a second service needs to consume, port the bean to its own config or wire it in there too.

## 9. See also

- Per-service designer note (link target does not exist on disk today — see **Anomalies**): `services/shared-and-kafka/docs/INTERVIEW-NOTES.md`. The file is not under `services/shared-and-kafka/` because that directory does not exist; the actual modules are at the repo root (`shared/`, `shared-kafka/`). If/when a per-module INTERVIEW-NOTES.md is added at `shared/docs/INTERVIEW-NOTES.md` or `shared-kafka/docs/INTERVIEW-NOTES.md`, link it from here.
- The relevant use-case walkthroughs:
  - `../use-cases/01-place-an-order.md` — uses the order-event outbox (covered in §8 anomaly 4)
  - `../use-cases/02-auth-flow.md` — uses `DomainException` / `GlobalExceptionHandler` for error rendering
  - `../use-cases/03-browse-and-search.md` — uses the only consumer (`search-service/RestaurantEventListener`) wired through `KafkaConsumerConfig`
  - `../use-cases/04-refund-flow.md` — uses the payment-event outbox
- The architecture guide: `../01-architecture-guide.md`
- The "how auth works" doc: `../02-how-auth-works.md` (covers the JWT-based call path that `FeignAuthForwarder` plumbs via the shared `FeignCorrelationIdInterceptor`)
- Repo-level docs: `../../../ARCHITECTURE.md`, `../../../PROJECT-STATUS.md`, `../../../RUN-THE-BIBLE.md`, `../../../docs/INTERVIEW-CHEATSHEET.md`
- Glossary (terms used here that may be unfamiliar to a beginner): `../00-glossary.md` — look for: **MDC**, **Avro**, **Schema Registry**, **`SpecificRecord`**, **outbox pattern**, **idempotent producer**, **`AckMode.MANUAL`**, **`OncePerRequestFilter`**, **`@RestControllerAdvice`**, **`RequestInterceptor` (Feign)**, **`@KafkaListener`**, **Producer/Consumer/Listener-Container factory**, **`pom.xml` dependency**, **`.avsc` schema**, **Maven multi-module build**.
