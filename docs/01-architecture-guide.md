# Samato Architecture

> Samato is a 9-service food-delivery platform built to teach microservice patterns on a real, runnable codebase. This document is the **beginner-friendly system map** — a one-stop tour of how the services fit together, how data moves between them, and where each microservice pattern lives. It sits between the [glossary](./00-glossary.md) and the use-case walkthroughs (`docs/use-cases/`) and links out to the per-service docs in [services/](./services/) for depth.

---

## 1. The 30-second tour

Before you dive in, here is the whole system at a glance. The diagram shows the **9 Spring Boot services** (white boxes) and the **9 infrastructure containers** (gray boxes) that the bible's `docker compose up` brings up. Every box is a real container; arrows show the kind of traffic that flows.

```
                          +------------------------+
                          |       browser /        |
                          |     mobile client      |
                          +-----------+------------+
                                      |
                                      |  HTTPS + Bearer JWT
                                      v
                          +------------------------+
                          |    api-gateway  :8080  |  <-- 1 entry point
                          |  Spring Cloud Gateway  |
                          +---+----+----+----+-----+
                              |    |    |    |    \
                              |    |    |    |     \--- (routes below)
                              v    v    v    v      v
        +-------------+  +---------+ +-----------+ +-----------+ +-------------+ +-----------+
        | auth-service|  |  user-  | | restaurant| |  search-  | |    order-   | |  payment- |
        |   :9000     |  | service | |  service  | |  service  | |   service   | |  service  |
        | SAS / JWKS  |  |  :8081  | |   :8082   | |   :8087   | |    :8083    | |   :8084   |
        +------+------+  +----+----+ +-----+-----+ +-----+-----+ +------+------+ +-----+-----+
               |              |             |              |              |              |
               |              |             |              |              | Feign        | Feign
               |              |             |              |              +--------------+--------+
               |              |             |              |              |                       |
               |              |             |  OpenSearch  |              v                       v
               |              |             +--------------+        +-----------+         +-----------+
               |              |             |  OpenSearch  |        | restaurant|         |  payment- |
               |              |             |   :9200      |        |  -service |         |  service  |
               |              |             +--------------+        |  :8082    |         |  :8084    |
               |              |                                    +-----------+         +-----------+
               |              |                                            |                    |
               |              |                                  Avro events out              events out
               |              |                                            v                    v
               |              |                                    +-----------+        +-----------+
               |              +------> JWKS public keys <----------+  Kafka    |        |  Kafka    |
               +---------------------> JWKS public keys <---------+  :9094    |        |  :9094    |
                                                                          |                |
                                                                          +-------+--------+
                                                                                  |
                                                                       Avro SpecificRecord +
                                                                       byte[] JSON events
                                                                                  |
                                                                          +-------v-------+
                                                                          |   Schema      |
                                                                          |   Registry    |
                                                                          |   :8085       |
                                                                          +---------------+

   +------------+  +------------+  +-----------+  +---------+  +---------+  +-------+  +--------+
   | Postgres   |  | Postgres   |  | Postgres  |  |  Redis  |  | Zipkin  |  |Prom.  |  |  3     |
   |   :5432    |  |   :5432    |  |   :5432   |  |  :6379  |  |  :9411  |  | :9090 |  | unused |
   | (one DB    |  | (one DB    |  | (one DB   |  | cache + |  | traces  |  |metr.  |  |  DBs   |
   | per svc)   |  | per svc)   |  | per svc)  |  | rate-   |  +---------+  +-------+  | for    |
   | 9 DBs in   |  |            |  |           |  | limit   |                          | Phase  |
   | use today  |  |            |  |           |  +---------+                          |  6+     |
   +------------+  +------------+  +-----------+                                          +--------+
```

**The 9 services, one line each:**

| # | Service | Port | What it does | Headline pattern |
|---|---|---:|---|---|
| 1 | [api-gateway](./services/api-gateway.md) | 8080 | The single front door — routing, JWT validation, CORS, correlation id, rate limit (planned). | [API Gateway](./00-glossary.md#api-gateway) (Spring Cloud Gateway, WebFlux). |
| 2 | [auth-service](./services/auth-service.md) | 9000 | Spring Authorization Server — issues RS256 JWTs, exposes the JWKS, owns the `users` + `oauth_clients` tables. | [OAuth2 / OIDC issuer](./00-glossary.md#oauth2--oidc) + asymmetric [JWT](./00-glossary.md#jwt-json-web-token). |
| 3 | user-service | 8081 | Customer / driver / restaurant-owner profiles. | [Database-per-service](./00-glossary.md#database-per-service) + a Feign call to `auth-service` for `me` lookups. |
| 4 | [restaurant-service](./services/restaurant-service.md) | 8082 | Restaurant + menu catalog, write side. Redis cache in front of Postgres. | [Cache-aside](./00-glossary.md#cache-aside), [Transactional Outbox](./00-glossary.md#transactional-outbox), [Optimistic Locking](./00-glossary.md#version). |
| 5 | [order-service](./services/order-service.md) | 8083 | Order lifecycle and the **saga orchestrator** that calls restaurant + payment. | [Saga (orchestration)](./00-glossary.md#saga-choreography-vs-orchestration), [Transactional Outbox](./00-glossary.md#transactional-outbox), [Idempotency-Key](./00-glossary.md#idempotency). |
| 6 | [payment-service](./services/payment-service.md) | 8084 | Razorpay wrapper + event-sourced payment ledger. HMAC-verified webhooks. | [Event Sourcing](./00-glossary.md#event-sourcing), [CQRS](./00-glossary.md#cqrs), [Transactional Outbox](./00-glossary.md#transactional-outbox), [Idempotency](./00-glossary.md#idempotency). |
| 7 | [search-service](./services/search-service.md) | 8087 | OpenSearch read model of restaurants; consumes Kafka, serves `GET /api/search/restaurants`. | [CQRS read model](./00-glossary.md#cqrs), [Idempotent Consumer](./00-glossary.md#idempotent-consumer). |
| 8 | discovery-service | 8761 | Netflix Eureka — the service registry every Spring service registers with. | [Eureka](./00-glossary.md#eureka). |
| 9 | config-service | 8888 | Spring Cloud Config — serves `config-repo/application.yml`. Currently **opted out** by every service in dev (see [anomaly §7](#7-cross-cutting-concerns)). | [Spring Cloud Config Server](./00-glossary.md#spring-cloud-config-server). |

**The 9 infra containers** (one-line purposes; full per-service docs are linked above):

| Infra | Port | Role |
|---|---:|---|
| Postgres 16 | 5432 | Hosts **9 service-owned databases** + **3 unused** for Phase 6+ (delivery / notification / analytics). |
| Redis 7 | 6379 | Restaurant cache + future rate-limit / driver location store. |
| Kafka (KRaft) | 9092/9094 | Event backbone. Three publishing services, one consuming service. |
| Schema Registry | 8085 | Stores Avro schemas for the Confluent wire format (used by `restaurant-service` + `search-service`). |
| OpenSearch | 9200 | Search index for `restaurants`. |
| Zipkin | 9411 | Distributed tracing UI (Micrometer → Brave). |
| Prometheus | 9090 | Metrics scraper — currently scrapes **only 3 of 9** Spring services (see [§7](#7-cross-cutting-concerns)). |
| Kafka UI | 8091 | Dev tool to inspect topics/messages. |
| Grafana | 3000 | Dashboards for Prometheus. |

> The first time you read this, you can stop here and skim [§2](#2-how-a-request-flows-the-lifecycle), then jump to [docs/use-cases/01-place-an-order.md](./use-cases/01-place-an-order.md) for a concrete flow. Come back to [§3](#3-the-data-story)–[§5](#5-the-pattern-map) when you need the deeper picture.

---

## 2. How a request flows (the lifecycle)

A user places an order. The request hits 5 services and 1 Kafka topic before a confirmation comes back. Here is the **end-to-end sequence** for `POST /api/orders`. Each step is grounded in a real file (links to per-service docs).

```
   browser            api-gateway            order-service         restaurant-service     payment-service        Postgres       Kafka       Zipkin
      |                    |                       |                       |                     |                  |            |            |
      |  POST /api/orders  |                       |                       |                     |                  |            |            |
      |  Bearer JWT        |                       |                       |                     |                  |            |            |
      | -----------------> |                       |                       |                     |                  |            |            |
      |                    | 1. TLS termination    |                       |                     |                  |            |            |
      |                    | 2. CorrelationIdWebFilter: read or gen X-Correlation-Id            |                  |            |            |
      |                    | 3. Spring Security:   |                       |                     |                  |            |            |
      |                    |    NimbusReactiveJwtDecoder.decode(token) via JWKS                |                  |            |            |
      |                    | 4. JwtAuthFilter: inject X-User-Id, X-User-Roles                |                  |            |            |
      |                    | 5. Route by path prefix -> lb://SAMATO-ORDER-SERVICE               |                  |            |            |
      |                    |    (Eureka lookup)     |                       |                     |                  |            |            |
      |                    |---------------------> |                       |                     |                  |            |            |
      |                    |                       | 6. ServletCorrelationIdFilter: same X-Correlation-Id     |            |            |
      |                    |                       | 7. Spring Security:    |                     |                  |            |            |
      |                    |                       |    NimbusJwtDecoder.decode(token) via JWKS                |            |            |
      |                    |                       | 8. OrderController.place: @PreAuthorize("hasRole('CUSTOMER')")
      |                    |                       | 9. OrderService.placeOrder (idempotency replay check)      |            |            |
      |                    |                       |10. OrderService.persistAndStartSaga @Transactional:       |            |            |
      |                    |                       |    INSERT Order (PLACED)                                  |            |            |
      |                    |                       |    INSERT SagaInstance + 5 SagaStep (PENDING)             |            |            |
      |                    |                       |    INSERT OutboxEvent (samato.order.placed, payload=Avro) |            |            |
      |                    |                       |    INSERT IdempotencyRecord (if Idempotency-Key sent)    |            |            |
      |                    |                       |---------------------->|                  |                  |            |            |
      |                    |                       |                       | 11. SagaEngine.VALIDATE_RESTAURANT                |            |            |
      |                    |                       |                       |     Feign GET /api/restaurants/{id}                |            |            |
      |                    |                       |                       |     FeignAuthForwarder + FeignCorrelationIdInterceptor
      |                    |                       |                       | 12. RestaurantService.get (Redis hit) |          |            |            |
      |                    |                       |                       |<------------------                                  |            |            |
      |                    |                       | 13. SagaEngine.VALIDATE_ITEMS                              |            |            |
      |                    |                       |     Feign GET /api/restaurants/{id}/menu?ids=...           |            |            |
      |                    |                       |     Sets OrderItem.name + unitPrice from menu              |            |            |
      |                    |                       | 14. SagaEngine.RESERVE_INVENTORY (stub)                   |            |            |
      |                    |                       | 15. SagaEngine.CHARGE_PAYMENT                              |            |            |
      |                    |                       |     Feign POST /api/payments/orders  (with Idempotency-Key = saga-step-uuid)            |
      |                    |                       |---------------------->|                  |                  |            |            |
      |                    |                       |                       |                  | 16. PaymentService.createRazorpayOrder         |            |            |
      |                    |                       |                       |                  | 17. IdempotencyGuard.executeOnce               |            |            |
      |                    |                       |                       |                  | 18. PaymentCommandHandler @Transactional:      |            |            |
      |                    |                       |                       |                  |     load Payment, decide, append events,      |            |            |
      |                    |                       |                       |                  |     project to PaymentView,                    |            |            |
      |                    |                       |                       |                  |     maybeSnapshot,                            |            |            |
      |                    |                       |                       |                  |     enqueue OutboxEvent (samato.payment.created)|           |            |
      |                    |                       |                       |                  | 19. RazorpayClient.createOrder (HTTPS to Razorpay)         |            |            |
      |                    |                       |<----------------------|------------------|                  |            |            |
      |                    |                       | 20. SagaEngine.CONFIRM_ORDER (synchronous happy path)       |            |            |
      |                    |                       |     Order.transitionTo(CONFIRMED)                          |            |            |
      |                    |                       |     outbox.appendOrderConfirmed                            |            |            |
      |                    |                       | 21. Response: 201 OrderResponse(JSON)                      |            |            |
      |                    | <--------------------|                       |                  |                  |            |            |
      |  201 Created       |                       |                       |                  |                  |            |            |
      | <------------------|                       |                       |                  |                  |            |            |
      |                    |                       |                       |                  |                  |            |            |
      |                    |                       | 22. OutboxPublisher.publishPending @Scheduled(500ms)       |            |            |
      |                    |                       |     for each unsent row: kafka.send(...).get(5, SECONDS)  |            |            |
      |                    |                       |     on ack, set sent_at = now()                           |            |            |
      |                    |                       |--------------------->|                |                  |          |            |
      |                    |                       |                       |                |  23.  Outbox poller:  samato.payment.created, charged, ...              |
      |                    |                       |                       |                |                  |          |-->samato.payment.charged
      |                    |                       |                       |                |                  |            |            |
      |                    |                       |                       |                |                  |            | 24. Zipkin receives spans at end of request:        |
      |                    |                       |                       |                |                  |            |     order-service -> restaurant-service           |
      |                    |                       |                       |                |                  |            |     -> payment-service -> outbox poller           |
      |                    |                       |                       |                |                  |            |     one trace id, one correlation id throughout    |
```

**What happens at each step — the terms you should know:**

- **Step 1: TLS termination.** The container's edge proxy (or local-dev curl) terminates TLS. Inside the cluster, traffic is HTTP on the docker network.
- **Step 2: Correlation ID creation.** `CorrelationIdWebFilter` ([api-gateway/CorrelationIdWebFilter.java](./services/api-gateway.md)) reads the inbound `X-Correlation-Id` header or generates a fresh UUID. It puts the id in SLF4J's [MDC](./00-glossary.md#mdc) under `MdcKeys.CORRELATION_ID`. Every log line emitted during this request will carry the id.
- **Step 3: JWT validation at the gateway.** `SecurityConfig#springSecurityFilterChain` ([api-gateway/SecurityConfig.java](./services/api-gateway.md)) calls the `NimbusReactiveJwtDecoder` bean (built in [JwtConfig.java](./services/api-gateway.md)) which fetches the public keys from `auth-service` at `/.well-known/jwks.json` and verifies the bearer token's RS256 signature **locally**. No per-request call to `auth-service` is made — see [§7 Authentication](#7-cross-cutting-concerns).
- **Step 4: Inject user headers.** [`JwtAuthFilter`](./services/api-gateway.md) (Spring Cloud Gateway `GlobalFilter`) decorates the forwarded request with `X-User-Id` and `X-User-Roles` so downstream services can authorize without re-parsing the JWT.
- **Step 5: Route by path.** `GatewayRoutesConfig#routes` ([api-gateway/GatewayRoutesConfig.java](./services/api-gateway.md)) matches the path prefix `/api/orders/**` to the URI `lb://SAMATO-ORDER-SERVICE`. The `lb://` prefix tells Spring Cloud Gateway's `DiscoveryClient` route locator to ask Eureka for a healthy instance of `samato-order-service` and load-balance across them. (Note: at the time of writing, each service runs as a single instance, so load-balancing is moot, but the wiring is in place.)
- **Step 6: Correlation ID at the destination.** The downstream service's `CorrelationIdFilter` ([shared/observability/CorrelationIdFilter.java](./services/shared-and-kafka.md)) picks up the same `X-Correlation-Id` (or generates one if missing) and puts it in MDC.
- **Step 7: JWT validation at the service (defence in depth).** `NimbusJwtDecoder.withJwkSetUri(...)` ([order-service/SecurityConfig.java](./services/order-service.md)) re-validates the token using the same JWKS. Every service does this; the gateway trust is **not** transitive.
- **Step 8: `@PreAuthorize`.** Spring Security evaluates the SpEL expression against the JWT-derived authorities. If the user doesn't have the `CUSTOMER` role, Spring returns 403 before any business code runs. See [`@PreAuthorize`](./00-glossary.md#preauthorize) in the glossary.
- **Step 9: Idempotency replay check.** `OrderService.placeOrder` looks up the `Idempotency-Key` header in the `idempotency_records` table ([schema in order-service.md](./services/order-service.md#5-database-schema)). If a record exists with the same key + same request hash, the cached response is returned (no second order). If the key was used with a *different* body, the service throws `DomainException("IDEMPOTENCY_KEY_REUSED", 422)`. See [Idempotency](./00-glossary.md#idempotency).
- **Step 10: DB transaction.** `OrderService.persistAndStartSaga` is annotated `@Transactional`. The order row, the `SagaInstance` + 5 `SagaStep` rows, the outbox event, and the idempotency record all commit **together** (or none of them do). This is what makes the [Outbox pattern](./00-glossary.md#transactional-outbox) atomic — the business write and the "intent to publish" commit in one shot.
- **Steps 11–15: Service-to-service calls.** [`SagaEngine`](./services/order-service.md#3-15-comsamatoorderservicesagasagaenginejava) calls `restaurant-service` and `payment-service` via [OpenFeign](./00-glossary.md#feignclient). [`FeignAuthForwarder`](./services/order-service.md) plumbs the user's bearer token into the outbound call, and [`FeignCorrelationIdInterceptor`](./services/shared-and-kafka.md#34-sharedsrcmainjavacomsamtosharedobservabilityfeigncorrelationidinterceptorjava) propagates the correlation id. The two Feign clients are: [`PaymentClient`](./services/order-service.md) (`POST /api/payments/orders`, `POST /api/payments/{id}/refunds`, `GET /api/payments/{id}`) and [`RestaurantClient`](./services/order-service.md) (`GET /api/restaurants/{id}`, `GET /api/restaurants/{id}/menu?ids=...`). See [call-graph.json — feignClients](./inventory/call-graph.json).
- **Step 16: Payment service entry.** `PaymentService.createRazorpayOrder` wraps the work in `IdempotencyGuard.executeOnce("CreateRazorpayOrder", key, ...)` ([payment-service.md](./services/payment-service.md)) which dedupes on the `processed_commands` table. A retry returns the cached result.
- **Step 17: Event-sourced write.** [`PaymentCommandHandler`](./services/payment-service.md) is the **only** place that mutates the `Payment` aggregate. It runs the decider (`Payment.decide(command)`), which returns 0+ new events; the events are appended to the `events` table ([Event Sourcing](./00-glossary.md#event-sourcing)). The same transaction projects the events to the `payment_view` ([CQRS](./00-glossary.md#cqrs) read model), maybe-snapshots every 50 events, and enqueues an `outbox_events` row for Kafka.
- **Step 18: Razorpay call.** `RazorpayClientImpl` ([payment-service.md](./services/payment-service.md)) wraps the `com.razorpay:razorpay-java` SDK. Money crosses the wire in **paise** (1 INR = 100 paise). The resulting Razorpay `razorpayOrderId` is stored in the `RazorpayOrderCreated` event.
- **Step 19: Response.** `OrderController.place` returns 201 with `OrderResponse` JSON. On the failure path, the saga's `compensate` walker runs the compensating steps (e.g. `refund` for a successful charge that later fails confirmation) and returns 422 with the failure reason.
- **Step 20: Outbox poller.** `OutboxPublisher.publishPending` is a `@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")` method that fires every 500 ms. It reads up to 100 unsent rows, calls `kafkaTemplate.send(...).get(5, SECONDS)` (synchronous so a failure leaves the row unsent), and on ack sets `sent_at = now()`. The Avro events go out on `samato.order.{placed,confirmed,cancelled}`; the payment events on `samato.payment.{created,charged,failed,refund.initiated,refunded,expired}`.
- **Step 21: Spans to Zipkin.** Every HTTP call, every Kafka producer/consumer, and every Feign call is auto-instrumented by Micrometer Tracing → Brave. The trace id is the same across all services; the span id is per-call. Set `management.tracing.sampling.probability: 1.0` in dev (100% sampling) and view at `http://localhost:9411`.

> **What to take away**: the gateway does edge auth; each service re-validates; the saga drives the workflow synchronously; the outbox poller delivers the events eventually; Zipkin stitches the whole thing together by trace id.

---

## 3. The data story

Samato is **12 databases** deep (one per service, plus 3 unused for Phase 6+). Each service owns its DB; there are no cross-service foreign keys, no shared schemas, no cross-service joins. The composition happens in code (Feign call, event projection, or gateway response aggregation). This is the [Database-per-service](./00-glossary.md#database-per-service) pattern.

| # | Database | Owner | What it stores | Where the schema lives |
|---:|---|---|---|---|
| 1 | `auth` | [auth-service](./services/auth-service.md) | `users`, `user_roles`, `oauth_clients`. BCrypt password hashes, roles, registered OAuth2 clients. | `auth-service/src/main/resources/db/migration/V1__init.sql` |
| 2 | `user_service` | user-service | `customer_profiles`, `driver_profiles`, `restaurant_owner_profiles`. Address, preferences (JSONB), last-known driver location. | `user-service/src/main/resources/db/migration/` |
| 3 | `restaurant_service` | [restaurant-service](./services/restaurant-service.md) | `restaurants`, `menu_items`, `outbox_events`. | `restaurant-service/src/main/resources/db/migration/V1__init.sql` |
| 4 | `order_service` | [order-service](./services/order-service.md) | `orders`, `order_items`, `saga_instances`, `saga_steps`, `idempotency_records`, `outbox_events`. The saga state machine lives here. | `order-service/src/main/resources/db/migration/V1__init.sql` + `V2__relax_order_items_not_null.sql` |
| 5 | `payment_service` | [payment-service](./services/payment-service.md) | `events` (append-only event store, JSONB), `payment_snapshots` (every 50 events), `payment_view` (CQRS read model), `processed_commands` (idempotency), `outbox_events`. | `payment-service/src/main/resources/db/migration/V1__init_event_store.sql` + `V2__processed_commands_and_views.sql` |
| 6 | `search_service` | (none — unused) | Empty database. `search-service` is OpenSearch-only; the DB is provisioned for parity. | (no Flyway folder) |
| 7 | `delivery_service` | (none — **unused**) | Provisioned for Phase 6. | (no schema) |
| 8 | `notification_service` | (none — **unused**) | Provisioned for Phase 6. | (no schema) |
| 9 | `analytics_service` | (none — **unused**) | Provisioned for Phase 6. | (no schema) |
| 10 | `config` | n/a | The [Spring Cloud Config Server](./00-glossary.md#spring-cloud-config-server)'s git-backed config files (YAML). | `config-repo/application.yml` |
| 11 | `eureka` | n/a | Not a DB; Eureka is in-memory. | n/a |

> **Three databases are provisioned but no service uses them today**: `delivery_service`, `notification_service`, `analytics_service`. The `init-databases.sh` script creates them so the Postgres container is ready when Phase 6 ships the delivery/notification/analytics services. See `ARCHITECTURE.md` "Planned (Phase 6+) but not implemented."

### Two on-the-wire Kafka formats

Not all Kafka payloads look the same. The bible has **two formats in flight**, and you have to know which is which:

| Format | Where it's used | Producer | Consumer |
|---|---|---|---|
| **Confluent Avro** (`magic byte + 4-byte schema id + Avro binary`) | `restaurant-service` → `search-service` (the only consumer today) for `samato.restaurant.created` and `samato.restaurant.updated`. | `KafkaTemplate<String, SpecificRecord>` from [shared-kafka/KafkaProducerConfig](./services/shared-and-kafka.md#41-sharedkafkasrcmainjavacomsamtosharedkafkaconfigkafkaproducerconfigjava) + `KafkaAvroSerializer` + Schema Registry. | `search-service/RestaurantEventListener` ([search-service.md](./services/search-service.md)) via the consumer factory in [shared-kafka/KafkaConsumerConfig](./services/shared-and-kafka.md#42-sharedkafkasrcmainjavacomsamtosharedkafkaconfigkafkaconsumerconfigjava) + `KafkaAvroDeserializer`. |
| **`byte[]` = `Avro.toString(event).getBytes(UTF_8)`** (JSON text, NOT Avro wire format) | `order-service` for `samato.order.{placed,confirmed,cancelled}` and `payment-service` for `samato.payment.{created,charged,failed,refund.initiated,refunded,expired}`. | Service-local `KafkaTemplate<String, byte[]>` from each service's `KafkaByteArrayConfig`; `application.yml` sets `value-serializer: ByteArraySerializer`. | (no consumers today — see [§4](#4-the-message-story)) |

**What this means in practice.** If a future consumer wants to read `samato.order.placed`, it must use `ByteArrayDeserializer` + Jackson — *not* the `KafkaAvroDeserializer`. The Avro `.avsc` files in `shared-kafka/src/main/avro/` (`OrderPlacedEvent.avsc`, etc.) are the **build-time shape** (used to generate the Java class for the producer) but are *not* the wire format. This is the **biggest operational anomaly** in the codebase; see [shared-and-kafka.md — Anomalies](./services/shared-and-kafka.md#anomalies-and-how-to-interpret-them).

> **Three payment topics have no `.avsc` schema at all**: `samato.payment.created`, `samato.payment.refund.initiated`, `samato.payment.expired`. The outbox publishes the raw JSON bytes from the event store's `events.event_data` JSONB column. The Avro class `PaymentChargedEvent` etc. exists at build time but is not serialised on the wire for these three. See [`inventory/shared-and-kafka.json` — `topicsDeclaredInCodeButNotInSharedSchemas`](./inventory/shared-and-kafka.json).

---

## 4. The message story

Samato has **11 Kafka topics** (today, in code) and **3 publishing services**. There is exactly **1 consumer** in the entire monorepo ([`search-service`](./services/search-service.md#35-projectionrestauranteventlistenerjava)). The other 8 topics are published but unconsumed — the contract is forward-looking, ready for Phase 6/7/8 (notification, analytics, delivery).

| # | Topic | Carries | Produced by | Consumed by | Producer template | Consumer key |
|---:|---|---|---|---|---|---|
| 1 | `samato.restaurant.created` | A new restaurant's full data (`name`, `cuisine`, `city`, `lat`/`lon`, `ownerId`). | [restaurant-service](./services/restaurant-service.md) `Outbox.enqueueRestaurantCreated`. | [search-service](./services/search-service.md) `RestaurantEventListener.onEvent` (idempotent upsert to OpenSearch). | `KafkaTemplate<String, SpecificRecord>` from [shared-kafka](./services/shared-and-kafka.md#41-sharedkafkasrcmainjavacomsamtosharedkafkaconfigkafkaproducerconfigjava). | `restaurantId` (UUID string). |
| 2 | `samato.restaurant.updated` | An updated restaurant's snapshot (no `ownerId`). | [restaurant-service](./services/restaurant-service.md) `Outbox.enqueueRestaurantUpdated` (fires on both update and deactivate). | search-service same listener. | Same SpecificRecord template. | `restaurantId`. |
| 3 | `samato.order.placed` | `OrderPlacedEvent` as JSON bytes (from `Avro.toString`). | [order-service](./services/order-service.md) `OutboxPublisher.appendOrderPlaced`. | **No consumer — Phase 6+ work.** | `KafkaTemplate<String, byte[]>` from `order-service/KafkaByteArrayConfig`. | `orderId`. |
| 4 | `samato.order.confirmed` | `OrderConfirmedEvent` as JSON bytes. | order-service `OutboxPublisher.appendOrderConfirmed`. | No consumer. | Same byte[] template. | `orderId`. |
| 5 | `samato.order.cancelled` | `OrderCancelledEvent` as JSON bytes (carries a `reason`). | order-service `OutboxPublisher.appendOrderCancelled`. | No consumer. | Same byte[] template. | `orderId`. |
| 6 | `samato.payment.created` | A Razorpay order was created (JSONB bytes, **no Avro schema**). | [payment-service](./services/payment-service.md) `OutboxPublisher` (via `topicFor(RazorpayOrderCreated)`). | No consumer. | `KafkaTemplate<String, byte[]>` from `payment-service/KafkaByteArrayConfig`. | `paymentId`. |
| 7 | `samato.payment.charged` | `PaymentCaptured` event, raw JSONB bytes. | payment-service `OutboxPublisher`. | No consumer. | Same byte[] template. | `paymentId`. |
| 8 | `samato.payment.failed` | `PaymentFailed` event, raw JSONB bytes. | payment-service `OutboxPublisher`. | No consumer. | Same byte[] template. | `paymentId`. |
| 9 | `samato.payment.refund.initiated` | `RefundInitiated` event, raw JSONB bytes (**no Avro schema**). | payment-service `OutboxPublisher`. | No consumer. | Same byte[] template. | `paymentId`. |
| 10 | `samato.payment.refunded` | `RefundCompleted` event, raw JSONB bytes. | payment-service `OutboxPublisher`. | No consumer. | Same byte[] template. | `paymentId`. |
| 11 | `samato.payment.expired` | `PaymentExpired` event, raw JSONB bytes (**no Avro schema**). | payment-service `OutboxPublisher`. | No consumer. | Same byte[] template. | `paymentId`. |

> All source-of-truth data for this table: [`docs/inventory/shared-and-kafka.json` — `kafkaProducers`, `kafkaListeners`, `kafkaTopicCrossReference`](./inventory/shared-and-kafka.json) and the per-service docs. All three publishing services use the [Transactional Outbox](./00-glossary.md#transactional-outbox) pattern — the business write and the outbox insert commit in the same DB transaction, and a scheduled poller ships the rows to Kafka. The Avro `.avsc` files live in [`shared-kafka/src/main/avro/`](./services/shared-and-kafka.md#46-avro-schemas-sharedkafkasrcmainavroavsc); only `restaurant-service` and `search-service` participate in the Confluent wire format end-to-end.

### The Outbox pattern (3 pollers)

| Service | Outbox table | Poller | Interval | Send mode |
|---|---|---|---|---|
| [order-service](./services/order-service.md) | `outbox_events` (Flyway `V1__init.sql` line 81) | `OutboxPublisher.publishPending` | `@Scheduled(fixedDelayString = "${samato.outbox.poll-ms:500}")` — default **500 ms** | `kafkaTemplate.send(...).get(5, SECONDS)` (sync, 5 s timeout). |
| [payment-service](./services/payment-service.md) | `outbox_events` (Flyway `V2__processed_commands_and_views.sql` line 66) | `OutboxPublisher.publishPending` | Same `@Scheduled` — default **500 ms**, batch size 50, sync send. | Same `.get(5, SECONDS)`. |
| [restaurant-service](./services/restaurant-service.md) | `outbox_events` (Flyway `V1__init.sql` line 38) | `Outbox.publishPending` | `@Scheduled(fixedDelay = 1000L)` — hard-coded **1 s**. | Same sync send. |

**Why use the outbox?** The "DB write succeeded, Kafka send failed" problem. If the business write commits but the Kafka send later throws, the system is split-brain. The outbox solves this by **inserting the event as a row in the same DB transaction** as the business write, then shipping it asynchronously. Either both happen or neither does. The poller's job is to be **at-least-once** (with a partial index `idx_outbox_unsent WHERE sent_at IS NULL` for fast poll queries), and the **consumer's job is to be idempotent** (e.g. OpenSearch upsert by `restaurantId`).

> **One subtle gotcha across all 3 pollers**: the column annotation uses `columnDefinition = "BYTEA"` (not `@Lob`). Hibernate 6 + Postgres maps `@Lob byte[]` to the deprecated OID storage type, which breaks reads — the explicit override fixes a bring-up bug documented in [`ARCHITECTURE.md` — Bring-up summary](../ARCHITECTURE.md).

---

## 5. The pattern map

This is the cheat-sheet a beginner can scan to see "where does pattern X live?". One row per pattern, one column per service. A checkmark (✓) means the pattern is **implemented in code today**; a link takes you to the per-service doc for the details.

| Pattern | api-gateway | auth-service | user-service | restaurant-service | order-service | payment-service | search-service | discovery-service | config-service |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| [API Gateway](./00-glossary.md#api-gateway) | [✓](./services/api-gateway.md) | | | | | | | | |
| [Database-per-service](./00-glossary.md#database-per-service) | n/a | [✓](./services/auth-service.md) | ✓ | [✓](./services/restaurant-service.md) | [✓](./services/order-service.md) | [✓](./services/payment-service.md) | n/a | n/a | n/a |
| [Service registry (Eureka)](./00-glossary.md#eureka) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | [✓](./services/discovery-service.md) | ✓ |
| [Centralised config](./00-glossary.md#spring-cloud-config-server) | ✗ (opts out) | ✗ (opts out) | ✗ (opts out) | ✗ (opts out) | ✗ (opts out) | ✗ (opts out) | ✗ (opts out) | ✗ (opts out) | [✓](./services/config-service.md) |
| [OAuth2 / OIDC issuer](./00-glossary.md#oauth2--oidc) | | [✓](./services/auth-service.md) | | | | | | | |
| [JWT (RS256) resource server](./00-glossary.md#jwt-json-web-token) | ✓ | n/a (issuer) | ✓ | [✓](./services/restaurant-service.md) | [✓](./services/order-service.md) | [✓](./services/payment-service.md) | [✓](./services/search-service.md) | | |
| [Saga (orchestration)](./00-glossary.md#saga-choreography-vs-orchestration) | | | | | [✓](./services/order-service.md#3-15-comsamatoorderservicesagasagaenginejava) | | | | |
| [Saga compensation](./00-glossary.md#saga) | | | | | [✓](./services/order-service.md) | | | | |
| [Saga poller (resumability)](./00-glossary.md#saga) | | | | | [✓](./services/order-service.md#3-16-comsamatoorderservicesagasagapollerjava) | | | | |
| [Transactional Outbox](./00-glossary.md#transactional-outbox) | | | | [✓](./services/restaurant-service.md#313-comsamatorestaurantserviceserviceoutbox) | [✓](./services/order-service.md#3-12-comsamatoorderserviceoutboxoutboxpublisherjava) | [✓](./services/payment-service.md#327-outboxoutboxpublisherjava) | | | |
| [CQRS (write/read split)](./00-glossary.md#cqrs) | | | | ✓ (cache-aside) | | [✓](./services/payment-service.md) (event-sourced + view) | [✓](./services/search-service.md) (OpenSearch read model) | | |
| [Event Sourcing](./00-glossary.md#event-sourcing) | | | | | | [✓](./services/payment-service.md) (events + snapshot) | | | |
| [Idempotency (HTTP `Idempotency-Key`)](./00-glossary.md#idempotency) | | | | | [✓](./services/order-service.md) (`idempotency_records`) | [✓](./services/payment-service.md) (`processed_commands`) | | | |
| [Idempotent consumer](./00-glossary.md#idempotent-consumer) | | | | | | | [✓](./services/search-service.md) (upsert by `restaurantId`) | | |
| [At-least-once delivery](./00-glossary.md#at-least-once-delivery) | | | | ✓ | ✓ | ✓ | ✓ | | |
| [Idempotent producer (`enable.idempotence=true`)](./00-glossary.md#transactional-outbox) | | | | ✓ | ✓ | ✓ | n/a | | |
| [Optimistic locking (`@Version`)](./00-glossary.md#version) | | | | [✓](./services/restaurant-service.md) | [✓](./services/order-service.md) (`Order`, `SagaInstance`) | | | | |
| [Distributed tracing (Zipkin)](./00-glossary.md#distributed-tracing) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| [Correlation ID (MDC + headers)](./00-glossary.md#correlation-id) | ✓ (reactive) | ✓ (servlet) | ✓ | ✓ (servlet) | ✓ (servlet) | ✓ (servlet) | ✓ (servlet) | n/a | n/a |
| [Cache-aside (Redis)](./00-glossary.md#cache-aside) | | | | [✓](./services/restaurant-service.md) | | | (planned) | | |
| [Circuit breaker (Resilience4j)](./00-glossary.md#circuit-breaker) | (planned) | | (configured, not annotated) | (classpath only) | (configured, not annotated) | (configured, not annotated) | | | |
| [Retry (Resilience4j)](./00-glossary.md#resilience4j) | (planned) | | (configured, not annotated) | (classpath only) | (configured, not annotated) | (configured, not annotated) | | | |
| [Time limiter (Resilience4j)](./00-glossary.md#resilience4j) | (planned) | | ✓ (configured) | | | | | | |
| [Rate limiting (Redis token bucket)](./00-glossary.md#rate-limiter) | (configured, not activated) | | | | | | | | |
| [CORS](./00-glossary.md#cors-cross-origin-resource-sharing) | [✓](./services/api-gateway.md) | | | | | | | | |
| [HMAC webhook verification](./00-glossary.md#hmac--hmac-sha256) | | | | | | [✓](./services/payment-service.md) (Razorpay) | | | |
| [Soft delete](./00-glossary.md#domain-concepts) | | | | [✓](./services/restaurant-service.md) (`active = false`) | | | | | |
| [Server-side pricing (don't trust the client)](./00-glossary.md#domain-concepts) | | | | | [✓](./services/order-service.md) (`SagaEngine.VALIDATE_ITEMS`) | | | | |

> **The Resilience4j anomaly**: every resilience4j config is **declared in YAML but NOT annotated on the call sites**. The exception is `user-service`'s `AuthClientFallback` (which is wired as the Feign fallback factory) — see the [inventory resilience4j block](./inventory/shared-and-kafka.json). The fallback classes exist (e.g. `PaymentClientFallback`, `RestaurantClientFallback`, `AuthClientFallback`); Resilience4j policies are in `application.yml` blocks; but the `@CircuitBreaker` / `@Retry` annotations on the Feign interfaces are deliberately absent. The reasoning: the saga poller (`SagaPoller.resumeInProgress`, 1s tick) already does retry; layering a circuit breaker on top would cause double retries. See the per-service `§7 gotchas` in [order-service.md](./services/order-service.md), [payment-service.md](./services/payment-service.md), [restaurant-service.md](./services/restaurant-service.md).

---

## 6. The 9 services, one paragraph each

For depth, see each per-service doc. For quick orientation:

- **api-gateway (port 8080)** — the single front door. A reactive (WebFlux) Spring Cloud Gateway app. It routes `/api/**` to downstream services by Eureka service id, validates the JWT at the edge using auth-service's JWKS, decorates the request with `X-User-Id` and `X-User-Roles`, and stamps an `X-Correlation-Id`. The CORS config lives here. The rate-limit filter is wired but not yet active (Phase 7). **Pattern**: [API Gateway](./00-glossary.md#api-gateway). See [api-gateway.md](./services/api-gateway.md).

- **auth-service (port 9000)** — the trust anchor. A [Spring Authorization Server](./00-glossary.md#spring-authorization-server) that issues RS256 JWTs (15-min access tokens, 7-day refresh tokens), exposes the JWKS at `/.well-known/jwks.json`, and stores `users` + `oauth_clients`. The custom controllers (`/api/auth/register`, `/api/auth/me`, `/api/auth/dev-token`) sit alongside the framework's `/oauth2/**` endpoints. The RSA keypair is generated on startup — see [auth-service.md](./services/auth-service.md) for the prod gotcha. **Pattern**: [OAuth2 / OIDC issuer](./00-glossary.md#oauth2--oidc).

- **user-service (port 8081)** — the profile store. Customer / driver / restaurant-owner profiles live here, kept separate from `auth-service`'s credential store. One Feign call to `auth-service` for `me` lookups. **Pattern**: [Database-per-service](./00-glossary.md#database-per-service) + a single Feign dependency.

- **restaurant-service (port 8082)** — the catalog write side. Owns `restaurants` and `menu_items`. Reads go through a Redis cache; writes commit to Postgres + outbox in one transaction. Publishes `samato.restaurant.created` and `samato.restaurant.updated` as Confluent Avro. **Patterns**: [Cache-aside](./00-glossary.md#cache-aside), [Transactional Outbox](./00-glossary.md#transactional-outbox), [Optimistic Locking](./00-glossary.md#version). See [restaurant-service.md](./services/restaurant-service.md).

- **order-service (port 8083)** — the heart of the system. Owns the order lifecycle, runs the [Saga (orchestration)](./00-glossary.md#saga-choreography-vs-orchestration) that calls `restaurant-service` (Feign) → `payment-service` (Feign), persists each step in `saga_instances` + `saga_steps` for auditability, and has a separate `SagaPoller` that resumes stuck sagas on the next tick. Idempotency-Key handling on `POST /api/orders`. Publishes 3 order topics as JSON bytes (no consumer today). **Patterns**: [Saga](./00-glossary.md#saga), [Transactional Outbox](./00-glossary.md#transactional-outbox), [Idempotency](./00-glossary.md#idempotency). See [order-service.md](./services/order-service.md).

- **payment-service (port 8084)** — the money half. Razorpay integration (HTTPS out), event-sourced ledger (the `events` table is the source of truth; `PaymentView` is the CQRS read model), snapshot every 50 events, idempotency on `processed_commands`, separate security chain for the Razorpay HMAC-verified webhook. **Patterns**: [Event Sourcing](./00-glossary.md#event-sourcing), [CQRS](./00-glossary.md#cqrs), [Transactional Outbox](./00-glossary.md#transactional-outbox), [Idempotency](./00-glossary.md#idempotency), [HMAC verification](./00-glossary.md#hmac--hmac-sha256). See [payment-service.md](./services/payment-service.md).

- **search-service (port 8087)** — the read side of CQRS for restaurants. Consumes the 2 restaurant topics, projects them into an OpenSearch index (`restaurants`) with a `geo_point` mapping, and serves `GET /api/search/restaurants` with full-text + filter + geo queries. Has **the only `@KafkaListener` in the entire monorepo**. **Patterns**: [CQRS read model](./00-glossary.md#cqrs), [Idempotent Consumer](./00-glossary.md#idempotent-consumer). See [search-service.md](./services/search-service.md).

- **discovery-service (port 8761)** — Netflix Eureka. The registry every other service registers with. Heartbeats every 5 s; lease expires after 15 s. The api-gateway uses `lb://SERVICE-NAME` URIs; OpenFeign does the same. **Pattern**: [Eureka](./00-glossary.md#eureka). See [services/discovery-service.md](./services/discovery-service.md).

- **config-service (port 8888)** — Spring Cloud Config Server. Reads YAML from `config-repo/application.yml` and serves it over HTTP. **Every service in dev has `spring.cloud.config.enabled: false`**, so this is provisioned but unused in local dev. It's there for future Phase 7/8 work. **Pattern**: [Centralised config](./00-glossary.md#spring-cloud-config-server). See [services/config-service.md](./services/config-service.md).

> **What to take away**: the three "smart" services are **order-service** (saga), **payment-service** (event sourcing), and **restaurant-service** (catalog + outbox). The gateway, search, and discovery are pure infrastructure. The two library modules — [`shared`](./services/shared-and-kafka.md#3-file-by-file-walkthrough--shared-module) (errors, correlation id, MDC keys) and [`shared-kafka`](./services/shared-and-kafka.md#4-file-by-file-walkthrough--shared-kafka-module) (Avro schemas, Kafka producer/consumer factories) — are not deployable services; they are JARs pulled in by every Spring service via Maven.

---

## 7. Cross-cutting concerns

These are the things that span every service. Click the links for the per-service depth; the inventory files in [`docs/inventory/`](./inventory/) are the source of truth for the snippets below.

### Authentication
Every request to a protected endpoint carries an `Authorization: Bearer <jwt>` header. The JWT is RS256-signed by [auth-service](./services/auth-service.md) using a 2048-bit RSA keypair generated at startup. The public half is exposed at `/.well-known/jwks.json`. Every other service configures a `NimbusJwtDecoder.withJwkSetUri(...)` bean that fetches and caches the JWKS — verification is local, no per-request call to `auth-service`. The gateway validates once; each service re-validates (defence in depth). See [docs/02-how-auth-works.md](./02-how-auth-works.md) (being written next) for the full JWT lifecycle.

**Roles** are extracted from the JWT's `roles` claim and prefixed with `ROLE_` by a `JwtAuthenticationConverter` in every `SecurityConfig`. The four roles today: `CUSTOMER`, `RESTAURANT_OWNER`, `DRIVER`, `ADMIN`. `@PreAuthorize("hasRole('CUSTOMER')")` is the standard guard. The `Razorpay webhook` endpoint is in a **separate filter chain** with HMAC verification instead of JWT.

### Observability
- **Tracing**: every service has `management.tracing.sampling.probability: 1.0` (100% in dev) and `management.zipkin.tracing.endpoint: http://localhost:9411/api/v2/spans`. Micrometer + Brave auto-instruments HTTP server / client, Feign, RestTemplate, Kafka producer/consumer, and scheduled tasks. View at `http://localhost:9411`.
- **Metrics**: every service exposes `/actuator/prometheus`. **Anomaly**: Prometheus currently scrapes **only 3 of 9 Spring services** (config, discovery, api-gateway via `host.docker.internal`) — see [ARCHITECTURE.md "Bring-up summary"](../ARCHITECTURE.md) and the inventory.
- **Logging**: every service uses the same log pattern `"%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"`. The `MdcKeys` class in [shared/observability](./services/shared-and-kafka.md#35-sharedsrcmainjavacomsamtosharedobservabilitymdckeyscjava) defines the four keys (`userId`, `correlationId`, `traceId`, `spanId`). A `try (var ctx = MdcContext.fromKafka(record))` in `@KafkaListener` methods scopes the correlation id to one record; the `FeignCorrelationIdInterceptor` propagates the id on outbound calls; the gateway's `CorrelationIdWebFilter` (reactive) and the servlet `CorrelationIdFilter` stamp it on inbound.

### Resilience (Resilience4j)
Resilience4j is on the classpath of `order-service`, `payment-service`, `user-service`, and `restaurant-service`. **Anomaly**: every service has YAML blocks under `samato.resilience4j.*` (or `resilience4j.circuitbreaker.instances.*`) but **no `@CircuitBreaker` / `@Retry` annotations on call sites**. The Fallback factories on the Feign interfaces ARE wired and are the actual fault-isolation mechanism (a failed call returns a stub that throws a domain exception). The Resilience4j policies would be activated in Phase 7 — for now they are configured but inert. See the [resilience4j block in `shared-and-kafka.json`](./inventory/shared-and-kafka.json) for the per-service config.

### Configuration
`config-service` is up and serves YAML from `config-repo/application.yml`. **Every service in dev opts out** via `spring.cloud.config.enabled: false` (Spring Boot 3.3 enforces the `spring.config.import` setting; the opt-out is in 5+ service YAML files — see [ARCHITECTURE.md "Bring-up summary"](../ARCHITECTURE.md)). Flip to `true` to enable centralised config. Note that `api-gateway` has `spring.cloud.config.enabled: false` in its YAML — see [api-gateway.md §3.9](./services/api-gateway.md#39-application-config--applicationyml).

### Service discovery (Eureka)
Every service has `spring.application.name` and `eureka.client.service-url.defaultZone` and registers on startup. The api-gateway resolves `lb://SERVICE-NAME` URIs through Eureka; the Feign clients in `order-service` and `user-service` do the same. The `eureka.instance.prefer-ip-address: true` setting is critical inside Docker (the hostname is not resolvable between containers). See [services/discovery-service.md](./services/discovery-service.md).

### CORS
Centralised in the [api-gateway](./services/api-gateway.md#33-configcorsconfigjava). `CorsConfig` registers a `CorsWebFilter` with `allowedOriginPatterns: "*"`, `allowedMethods: "*"`, `allowedHeaders: "*"`, `exposedHeaders: "X-Correlation-Id"`. There is also a `spring.cloud.gateway.globalcors` block in `application.yml` (overlapping). **Dev concession**: tighten `allowedOriginPatterns` to specific origins in prod. Every other service is reachable only through the gateway, so no per-service CORS config is needed.

### Rate limiting
`RateLimitConfig.userOrIpKeyResolver` is wired in [api-gateway.md §3.7](./services/api-gateway.md#37-securityratelimitconfigjava), but the `RequestRateLimiterGatewayFilterFactory` is **NOT attached to any route yet**. The `spring-boot-starter-data-redis-reactive` dependency is present (and the Redis health check is in the compose env), so activation is a small Phase 7 change. See [api-gateway.md "Phase 7 note"](./services/api-gateway.md#37-securityratelimitconfigjava).

---

## 8. The "why" — Architectural Decision Records

The repo's [ARCHITECTURE.md](../ARCHITECTURE.md) lists 13 ADRs that justify the major choices. The table below is a one-sentence summary of each; read [ARCHITECTURE.md §Key architectural decisions](../ARCHITECTURE.md#key-architectural-decisions-adrs) for the rationale.

| ADR | Decision | Plain-English |
|---|---|---|
| ADR-1 | Database-per-service | Each service owns its own database; no shared schemas or cross-service joins. |
| ADR-2 | Kafka as the backbone | Async events decouple services and give us replay + per-partition ordering. |
| ADR-3 | Saga (orchestration) for orders | A central orchestrator (`order-service`'s `SagaEngine`) is easier to reason about than choreographed events. |
| ADR-4 | CQRS for read-heavy services | Optimise reads with a denormalised view, leave writes on the canonical store. |
| ADR-5 | Event Sourcing for payment | Append-only events give audit-grade history and time-travel. |
| ADR-6 | Outbox pattern | Solve the dual-write problem without 2PC. |
| ADR-7 | API Gateway with JWT validation | Centralise cross-cutting concerns; hide internal topology. |
| ADR-8 | Eureka for discovery | Mature and well-known; the load-balancer story is built in. |
| ADR-9 | Spring Cloud Config (git-backed) | Decouple config from code; audit trail; env promotion. |
| ADR-10 | Resilience4j | Lightweight fault-tolerance; no Hystrix-style sidecar. |
| ADR-11 | Self-hosted Spring Authorization Server | Avoid an external IdP dependency; the JWT contract is the interface. |
| ADR-12 | JSONB inside, Avro on the wire | Debug-friendly event store; contract-enforced Kafka payloads. |
| ADR-13 | Razorpay is the source of truth for money | We mirror its state transitions through webhooks; we never lie about money. |

> **The reality check on ADR-12**: today only `restaurant-service` actually uses the Confluent Avro wire format end-to-end. `order-service` and `payment-service` use the `byte[]` (= `Avro.toString` JSON) path. See [§3 The data story](#3-the-data-story) and the [inventory anomaly](./inventory/shared-and-kafka.json).

---

## 9. What's deliberately NOT in scope

These are the things a reader might expect to find but won't:

- **Live Razorpay mode.** The `application.yml` defaults to `rzp_test_xxxxxxxxxxxxxx` placeholder credentials; the service boots, but no real money moves. `RazorpayClientImpl` ([payment-service.md](./services/payment-service.md)) is wired for production keys — set `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` in env.
- **Multi-currency.** Every payment is INR (the only currency Razorpay test mode accepts without extra config). `currency` is stored as a 3-letter ISO code in the DB but the wire to Razorpay is hard-coded.
- **WebSocket / push notifications.** `delivery-service` is planned for Phase 6 and will use WebSockets to push driver location to customers. The database is provisioned, no code yet.
- **Service mesh / mTLS.** No Istio / Linkerd / Consul Connect. Service-to-service traffic on the docker network is plain HTTP. In prod, you would terminate mTLS at the gateway and let the mesh handle east-west.
- **Kubernetes.** The bible runs on `docker compose`. `Dockerfile` per service is multi-stage distroless (see [ARCHITECTURE.md "Container" row](../ARCHITECTURE.md)); the deployment topology is "one container per service on one host" today. The K8s liveness/readiness probes are configured (`management.endpoint.health.probes.enabled: true`) but no manifests are in the repo.
- **Phases 6–8.** `delivery-service`, `notification-service`, `analytics-service` are listed in `ARCHITECTURE.md` "Planned" — their databases are provisioned, no code. WebSocket, fanout consumers, Kafka Streams aggregations, and the analytics projector all belong to those phases.

---

## 10. Where to read next

If you have just read this guide top-to-bottom, the natural next steps are:

- **[docs/02-how-auth-works.md](./02-how-auth-works.md)** — the JWT lifecycle in detail. How the keypair is generated, how the JWKS is served, how the gateway validates, how `NimbusJwtDecoder` is wired in every service. (Being written next.)
- **[docs/use-cases/01-place-an-order.md](./use-cases/01-place-an-order.md)** — a step-by-step walkthrough of the saga. Best first use case to read.
- **[docs/use-cases/02-auth-flow.md](./use-cases/02-auth-flow.md)** — register, login, get a token, hit a protected endpoint. Explains the JWT round-trip and role mapping.
- **[docs/use-cases/03-browse-and-search.md](./use-cases/03-browse-and-search.md)** — the read side. Browse restaurants, run a search query, see how the OpenSearch index is fed.
- **[docs/use-cases/04-refund-flow.md](./use-cases/04-refund-flow.md)** — the saga's compensation path. Place a charge, then issue a refund, trace the events.

And the repo-wide docs:

- **[ARCHITECTURE.md](../ARCHITECTURE.md)** — the C4 diagrams, the ADR list, the bring-up bug history, the failure semantics.
- **[RUN-THE-BIBLE.md](../RUN-THE-BIBLE.md)** — how to bring the 18-container stack up locally.
- **[PROJECT-STATUS.md](../PROJECT-STATUS.md)** — what's running today vs. what's planned.
- **[docs/INTERVIEW-CHEATSHEET.md](./INTERVIEW-CHEATSHEET.md)** — quick-reference answers to common microservice interview questions, cross-service.

**Per-service `INTERVIEW-NOTES.md`** — every service has a per-service designer note aimed at interview prep. They are referenced from each per-service doc in [docs/services/](./services/) and live alongside the service code (e.g. `services/order-service/docs/INTERVIEW-NOTES.md`).

- [00-glossary.md](./00-glossary.md) — every term you'll hit (Spring annotations, microservice patterns, Kafka concepts, auth terminology). Keep it open in a tab.
- [docs/inventory/](./inventory/) — the Phase 1 inventory files that ground this guide. `call-graph.json`, `shared-and-kafka.json`, `endpoints-and-use-cases.json`, and `infrastructure.json` are the source of truth for every "X lives in Y" claim in this doc.
