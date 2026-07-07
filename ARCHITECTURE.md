# Architecture

This document captures the high-level architecture of the **Samato** project, the rationale behind it, and the trade-offs we accept. Use it as the entry point when explaining the system in interviews.

## C4 — Level 1: System Context

```
┌────────────┐         ┌─────────────────────────────┐
│  Customer  │────────▶│                             │
└────────────┘         │                             │
                       │   Samato Platform           │      ┌──────────┐
┌────────────┐         │                             │      │ Identity │
│ Restaurant │────────▶│   • API Gateway             │◀────▶│ Provider │
└────────────┘         │   • 12 domain services      │      │ (Keycloak│
                       │   • Async event bus (Kafka) │      └──────────┘
┌────────────┐         │   • Postgres, Redis, Search │      ┌──────────┐
│  Driver    │────────▶│                             │      │ Observ-  │
└────────────┘         └─────────────────────────────┘      │ ability  │
                                                             └──────────┘
```

## C4 — Level 2: Containers

| Container | Tech | Port | Purpose |
|-----------|------|------|---------|
| samato-api-gateway | Spring Cloud Gateway | 8080 | Routing, auth, rate limit |
| samato-config-service | Spring Cloud Config | 8888 | Centralized config |
| samato-discovery-service | Netflix Eureka | 8761 | Service registry |
| samato-auth-service | Spring Authorization Server | 9000 | OAuth2 / OIDC issuer |
| samato-user-service | Spring Boot + Postgres | 8081 | Profiles, RBAC |
| samato-restaurant-service | Spring Boot + Postgres + Redis | 8082 | Menus, search |
| samato-order-service | Spring Boot + Postgres | 8083 | Order lifecycle + Saga |
| samato-payment-service | Spring Boot + Postgres | 8084 | Event-sourced ledger |
| samato-delivery-service | Spring Boot + Postgres | 8085 | Driver assignment, tracking |
| samato-notification-service | Spring Boot | 8086 | Email/SMS/Push |
| samato-search-service | Spring Boot + OpenSearch | 8087 | Read model |
| samato-analytics-service | Spring Boot + Kafka Streams | 8088 | Materialized views |
| Kafka | Apache Kafka + Schema Registry | 9092 | Async messaging |
| Postgres (per service) | PostgreSQL 16 | 5432 | Per-service database |
| Redis | Redis 7 | 6379 | Cache, locks, rate limit |
| Zipkin | Zipkin | 9411 | Distributed tracing |
| Prometheus | Prometheus | 9090 | Metrics |
| Grafana | Grafana | 3000 | Dashboards |
| Keycloak | Keycloak | 9080 | Identity provider (dev only) |

## Key architectural decisions

| # | Decision | Rationale |
|---|----------|-----------|
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

## Communication patterns

| Pattern | When | Used for |
|---------|------|----------|
| **Sync REST (OpenFeign)** | Need immediate response, single source of truth | `GET /restaurant/{id}` from order-service |
| **Async events (Kafka)** | Cross-aggregate workflows, eventual consistency | `OrderPlaced`, `PaymentAuthorized`, `DeliveryAssigned` |
| **Request/Response on Kafka** | Saga steps that need a reply | `ReserveInventory` command + reply |
| **WebSocket** | Real-time push to client | Driver location updates |
| **gRPC** *(future)* | High-perf internal RPC | Not in MVP, called out as a trade-off |

## Cross-cutting concerns

| Concern | Implementation |
|---------|----------------|
| Tracing | Micrometer Tracing → Zipkin (OTel-compatible) |
| Metrics | Micrometer → Prometheus → Grafana |
| Logging | Logback JSON, MDC for `traceId`/`spanId` |
| Resilience | Resilience4j (CB, Retry, Bulkhead, TimeLimiter) |
| Rate limit | Redis token bucket at gateway |
| AuthN/Z | OAuth2 resource server (JWT, RS256) at gateway |
| Config | Spring Cloud Config Server (git) |
| Discovery | Eureka |
| Idempotency | `Idempotency-Key` header on POST/PUT |
| Outbox | Transactional outbox table + Kafka publisher |
| Caching | Redis, cache-aside pattern |
| Schema evolution | Avro + Confluent Schema Registry |
| Container | Multi-stage Dockerfile, distroless, non-root |

## Failure semantics

| Scenario | Strategy |
|----------|----------|
| Service down | Circuit breaker + fallback; load balancer removes instance |
| Slow dependency | TimeLimiter + Bulkhead |
| Lost message | Outbox + at-least-once delivery + consumer-side idempotency |
| Partial saga failure | Compensating transactions; saga state machine in DB |
| Cascade failure | Bulkheads prevent thread-pool exhaustion |
| Data drift | Eventual consistency, periodic reconcilers |

## Why food delivery as a domain?

It naturally exercises the trickiest patterns in one flow:
- **Long-running transactions** (order → cook → deliver)
- **External dependencies** (payment gateway, maps, push notifications)
- **Read-heavy** (browse restaurants) **and** **write-heavy** (orders) workloads
- **Multiple user types** (customer, restaurant, driver, admin)
- **Geo-spatial** queries (find nearby drivers)
- **Time-sensitive** flows (delivery time estimates, driver assignment)

This means almost every interview topic is grounded in real code you can point at.
