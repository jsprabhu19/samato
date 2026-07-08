# Architecture

> *The system at a glance — for the whiteboard, the design doc, and the interview.*

This document captures the high-level architecture of the **Samato** project, the rationale behind it, and the trade-offs we accept. It is the entry point when explaining the system.

---

## C4 — Level 1: System Context

```
┌────────────┐         ┌─────────────────────────────┐
│  Customer  │────────▶│                             │
└────────────┘         │                             │
                       │   Samato Platform           │
┌────────────┐         │                             │
│ Restaurant │────────▶│   • API Gateway             │
└────────────┘         │   • 9 domain services       │
                       │   • Async event bus (Kafka) │
┌────────────┐         │   • Postgres, Redis, Search │
│  Driver    │────────▶│                             │      (Phase 6+)
└────────────┘         └─────────────────────────────┘
```

The platform is **self-contained** for identity: `auth-service` (Spring Authorization Server) issues JWTs and exposes the JWKS endpoint. There is no external IdP. In production, this would be replaced by Okta / Auth0 / Azure AD; the resource-server config would change, the gateway logic would not.

---

## C4 — Level 2: Containers

These are the containers that exist **today** in the repo.

| Container | Tech | Port | Purpose |
|---|---|---:|---|
| samato-api-gateway | Spring Cloud Gateway | 8080 | Routing, JWT validation, rate limit, CORS |
| samato-config-service | Spring Cloud Config | 8888 | Centralized config (git-backed) |
| samato-discovery-service | Netflix Eureka | 8761 | Service registry, health |
| samato-auth-service | Spring Authorization Server | 9000 | OAuth2 / OIDC issuer, JWKS endpoint |
| samato-user-service | Spring Boot + Postgres | 8081 | Profiles, RBAC, addresses |
| samato-restaurant-service | Spring Boot + Postgres + OpenSearch | 8082 | Restaurant + menu catalog |
| samato-search-service | Spring Boot + OpenSearch + Redis | 8087 | Read model, search index, cache |
| samato-order-service | Spring Boot + Postgres | 8083 | Order lifecycle + saga orchestrator |
| samato-payment-service | Spring Boot + Postgres | 8084 | Event-sourced ledger, Razorpay integration |
| samato-schema-registry | Confluent Schema Registry (bitnamilegacy 7.6) | 8085 | Avro schema storage for Kafka payloads |
| samato-search-service | Spring Boot + OpenSearch + Redis | 8087 | Read model, search index, cache |
| Kafka | Apache Kafka (KRaft mode) | 9092 (external) / 9094 (internal) | Async messaging, outbox fanout |
| samato-kafka-ui | provectuslabs/kafka-ui v0.7.2 | 8091 | Dev UI for inspecting topics/messages (note: 8091, not 8081 — 8081 is user-service) |
| Postgres (per service) | PostgreSQL 16 | 5432 | One DB per service (see `infra/postgres/init-databases.sh`) |
| Redis | Redis 7 | 6379 | Cache, rate-limit tokens, distributed locks |
| OpenSearch | OpenSearch | 9200 | Search index for restaurant/search-service |
| Zipkin | Zipkin | 9411 | Distributed tracing UI |
| Prometheus | Prometheus | 9090 | Metrics scrape |
| Grafana | Grafana | 3000 | Dashboards (admin / admin) |

**Planned (Phase 6+) but not implemented:**

| Container | Port | Purpose |
|---|---:|---|
| samato-delivery-service | 8085 | Driver assignment, WebSocket, geo queries |
| samato-notification-service | 8086 | Email/SMS/push, Kafka consumers |
| samato-analytics-service | 8088 | Kafka Streams aggregations |

The databases for these are already provisioned (`delivery_service`, `notification_service`, `analytics_service` in `init-databases.sh`) so the Postgres container is ready when the services are built.

---

## Service dependency map

```
                       ┌─────────────────┐
                       │   API Gateway   │  :8080
                       │   (Spring CG)   │
                       └────────┬────────┘
                                │
       ┌────────────┬───────────┼───────────┬─────────────┬──────────────┐
       ▼            ▼           ▼           ▼             ▼              ▼
   auth-svc    user-svc   restaurant   search-svc    order-svc    payment-svc
    :9000       :8081      :8082         :8087         :8083         :8084
                                                                  ▲
                                                              Feign
                                                                  │
                                                          order-svc
                                  ┌──────────────────┴────────┬─────┴──────┬─────────────┐
                                  │                           │            │             │
                                  └────────────┴──────────────┴────────────┴──── Kafka ───┘
                                                       (samato.* topics)
```

**Sync** (REST via Feign):
- `order-svc` → `restaurant-svc` (validate restaurant + menu)
- `order-svc` → `payment-svc` (CHARGE_PAYMENT, REFUND)

**Async** (Kafka topics):
- `order-svc` publishes `samato.order.confirmed`, `samato.order.cancelled`
- `payment-svc` publishes `samato.payment.charged`, `samato.payment.refunded`, `samato.payment.failed`, `samato.payment.expired`, `samato.payment.created`, `samato.payment.refund.initiated`

**Discovery**: every service registers with `discovery-svc`; the gateway resolves `lb://SAMATO-ORDER-SERVICE` etc. through Eureka.

---

## Key architectural decisions (ADRs)

| # | Decision | Rationale |
|---|---|---|
| ADR-1 | **Database-per-service** | No cross-service joins, independent scaling, blast-radius isolation |
| ADR-2 | **Kafka as the backbone** | Decoupling, replay, ordering per partition, schema evolution |
| ADR-3 | **Saga (orchestration) for orders** | Explicit flow, easier to reason about in interviews vs. choreography |
| ADR-4 | **CQRS for read-heavy services** (restaurant, search) | Optimize reads, denormalize for the query, no impact on writes |
| ADR-5 | **Event Sourcing for payment** | Audit-grade, time-travel, projection-friendly |
| ADR-6 | **Outbox pattern** for reliable event publishing | Atomic DB + event write, no dual-write problem |
| ADR-7 | **API Gateway with JWT validation** | Centralize cross-cutting concerns, hide internal topology |
| ADR-8 | **Eureka for discovery** | Mature, well-known in interviews; Consul is the alternative |
| ADR-9 | **Spring Cloud Config (git-backed)** | Decouple config from code, audit trail, env promotion |
| ADR-10 | **Resilience4j** | Lightweight, no AOP magic, perfect for the interview whiteboard |
| ADR-11 | **Spring Authorization Server (self-hosted)** | No external IdP dependency for the bible; the JWT contract is what matters |
| ADR-12 | **JSONB in the event store, Avro on the Kafka wire** | Debug-friendly inside, contract-enforced outside |
| ADR-13 | **Razorpay is the source of truth for money; we mirror it** | Real-world pattern; we cannot lie about money; reconciliation is auditable |

---

## Communication patterns

| Pattern | When | Used for |
|---|---|---|
| **Sync REST (OpenFeign)** | Need immediate response, single source of truth | `GET /restaurant/{id}` from order-service, `POST /api/payments/orders` from saga |
| **Async events (Kafka)** | Cross-aggregate workflows, eventual consistency, fanout | `OrderPlaced`, `PaymentAuthorized`, `DeliveryAssigned` |
| **Transactional outbox → Kafka** | Need atomic DB + event without 2PC | Every state change in order-service, payment-service |
| **WebSocket** | Real-time push to client | Driver location updates (Phase 6) |
| **HMAC-signed webhook** | Receive callbacks from external systems | Razorpay → payment-service |

> **Why no gRPC?** It's on the roadmap. For the bible, REST + JSON keeps the focus on microservice patterns, not wire formats. Adding gRPC later is a drop-in for high-perf internal RPC.

---

## Cross-cutting concerns

| Concern | Implementation |
|---|---|
| Tracing | Micrometer Tracing → Zipkin (OTel-compatible) |
| Metrics | Micrometer → Prometheus → Grafana |
| Logging | Logback JSON, MDC for `traceId` / `spanId` |
| Resilience | Resilience4j (CircuitBreaker, Retry, Bulkhead, TimeLimiter) |
| Rate limit | Redis token bucket at gateway |
| AuthN/Z | OAuth2 resource server (JWT, RS256) at gateway; identity issued by auth-service |
| Config | Spring Cloud Config Server (git-backed) |
| Discovery | Eureka |
| Idempotency | `Idempotency-Key` HTTP header on POST/PUT; `processed_commands` table |
| Outbox | Transactional outbox table + scheduled poller → Kafka |
| Caching | Redis, cache-aside pattern (search-service) |
| Schema evolution | Avro + Confluent Schema Registry (Kafka wire) |
| Container | Multi-stage Dockerfile, distroless base, non-root user |

---

## Bring-up summary (2026-07-08)

Bugs found and fixed while bringing the 18-container stack online (one per bullet):

- Hibernate 6 `@Lob` on `String`/`byte[]` maps to OID, but migrations declare `TEXT`/`BYTEA` — replaced `@Lob` with explicit `columnDefinition`.
- Spring Boot 3.3 enforces `spring.config.import` — set `spring.cloud.config.enabled=false` in 5 service yml files.
- `api-gateway` yml had a duplicate `cloud:` block — merged into one.
- `user-service` yml had two `spring:` blocks — merged into one.
- Missing `KafkaTemplate<String, byte[]>` bean in order/payment-service — added `KafkaByteArrayConfig`.
- Bitnami `schema-registry` env-var remap unreliable on 7.6 — mount the config file directly.
- `api-gateway` Redis health DOWN — added `SPRING_DATA_REDIS_HOST=redis` to compose env.
- `kafka-ui` port 8081 conflicted with user-service — moved to 8091.
- `DevDataSeeder` crashed on re-run (duplicate key) — added `existsByEmail` check.

---

## Failure semantics

| Scenario | Strategy |
|---|---|
| Service down | Circuit breaker + fallback; Eureka removes instance from rotation |
| Slow dependency | TimeLimiter + Bulkhead |
| Lost message | Outbox + at-least-once delivery + consumer-side idempotency |
| Partial saga failure | Compensating transactions; saga state machine in DB |
| Cascade failure | Bulkheads prevent thread-pool exhaustion |
| Data drift | Eventual consistency, periodic reconcilers (planned) |
| Razorpay down | Fallback throws; saga retry picks up on next tick; order stays `RESERVED` |
| Bad webhook signature | 401; Razorpay retries; legitimate webhook eventually arrives |
| Concurrent event writers | `UNIQUE(aggregate_id, version)` constraint; `DataIntegrityViolationException` → `OptimisticLockException` |

---

## Why food delivery as a domain?

It naturally exercises the trickiest patterns in one flow:
- **Long-running transactions** (order → cook → deliver)
- **External dependencies** (payment gateway, maps, push notifications)
- **Read-heavy** (browse restaurants) **and** **write-heavy** (orders) workloads
- **Multiple user types** (customer, restaurant, driver, admin)
- **Geo-spatial** queries (find nearby drivers, Phase 6)
- **Time-sensitive** flows (delivery time estimates, driver assignment, payment expiry)

This means almost every interview topic is grounded in real code you can point at.

---

## What to read next

- [`README.md`](README.md) — top-level orientation
- [`RUN-THE-BIBLE.md`](RUN-THE-BIBLE.md) — operator's guide (how to actually run this thing)
- [`PROJECT-STATUS.md`](PROJECT-STATUS.md) — what's verified vs. unverified
- [`docs/INTERVIEW-CHEATSHEET.md`](docs/INTERVIEW-CHEATSHEET.md) — Q&A by topic
- `services/*/docs/INTERVIEW-NOTES.md` — designer notes per service (the *why*)
