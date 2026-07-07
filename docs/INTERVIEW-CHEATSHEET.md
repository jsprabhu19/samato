# Interview Cheat-Sheet

Quick-look Q&A for the most-asked microservice interview topics. Each entry points at the code in this repo that demonstrates the answer.

> **Reality check:** the code is structurally complete for Phases 1-5 (9 services) but has never been compiled or run end-to-end on this machine. Use the cheat-sheet to point at *what* demonstrates the pattern; see [`PROJECT-STATUS.md`](../PROJECT-STATUS.md) for what's actually been verified.

---

## 1. What is a microservice?

A small, autonomous service that owns **one bounded context**, has its **own datastore**, and communicates with others over a **network** (HTTP, gRPC, messaging). Trade-offs: independent deploy + scale vs. operational overhead + distributed-system complexity.

**In this repo:** 9 services (Phases 1-5), each with its own Postgres. See [ARCHITECTURE.md](../ARCHITECTURE.md).

## 2. How do you decompose a monolith?

By **bounded contexts** (DDD), not by technical layers. Group things that **change together** for the same business reason. Start coarse; split when the data model or team boundaries demand it.

**In this repo:** `restaurant-service` is split from `order-service` because restaurants are a *catalog* (read-heavy) while orders are a *workflow* (write-heavy, transactional). `search-service` is a separate read model.

## 3. Sync vs. async communication — when do you use which?

- **Sync (REST/gRPC):** when the caller **needs the answer now** and the callee is the source of truth (e.g., `GET /restaurant/{id}`).
- **Async (Kafka/RabbitMQ):** when the work is **decoupled**, **eventual** is acceptable, you want **replay**, or you're crossing a **workflow boundary** (e.g., `OrderPlaced` → `PaymentAuthorized`).

**Rule of thumb:** if you can answer "what happens if this fails?" without mentioning the caller, use async.

**In this repo:** `order-service` calls `restaurant-service` and `payment-service` synchronously (Feign); publishes `samato.order.*` to Kafka; receives `samato.payment.charged` from `payment-service` via webhook + Kafka outbox.

## 4. What's the Saga pattern?

A way to maintain **consistency across services** without a distributed transaction. Each step has a **compensating action** that undoes it. Two flavors:
- **Choreography** — services emit events, others react. Simple, but hard to see the whole flow.
- **Orchestration** — a central coordinator drives the steps. Easier to reason about, easier to add steps.

**In this repo:** `order-service` is the saga orchestrator. The 5-step workflow (validate restaurant → validate items → reserve inventory → charge payment → confirm order) is in [`services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java`](../services/order-service/src/main/java/com/samato/orderservice/saga/SagaEngine.java) (the `WORKFLOW` list). Each step runs in `REQUIRES_NEW` so a failure doesn't roll back the prior step.

## 5. Why is dual-write (DB + message broker) dangerous?

You can succeed at one and fail at the other, leaving the system inconsistent. The fix is the **Outbox pattern**: write the event to an `outbox` table in the **same DB transaction** as the business state; a separate publisher reads the outbox and ships to Kafka. At-least-once delivery + consumer-side idempotency = effectively-once.

**In this repo:** [`services/order-service/src/main/java/com/samato/orderservice/outbox/OutboxPublisher.java`](../services/order-service/src/main/java/com/samato/orderservice/outbox/OutboxPublisher.java) and the same shape in `payment-service/src/main/java/com/samato/paymentservice/outbox/OutboxPublisher.java`. The `outbox_events` table is read by a `@Scheduled` poller; on Kafka success, the row's `sent_at` is stamped.

## 6. CQRS — what and why?

**Command-Query Responsibility Segregation.** Writes go to a normalized model; reads come from a **denormalized projection** built by consuming events. Why: optimize reads without distorting writes, scale them independently, support multiple read models per write model.

**In this repo:** `search-service` keeps a read model in OpenSearch + Redis cache, fed by Kafka events from `restaurant-service`. `payment-service` is the most rigorous example: the write side is an event-sourced aggregate; the read side is `payment_view` table updated by an in-process projector in the same transaction.

## 7. Event Sourcing — when?

Store **state as a sequence of events** instead of the current row. You rebuild state by replaying events. Use when you need a **full audit trail**, **time-travel**, or **temporal queries** (e.g., "what did the account look like on March 1?").

**In this repo:** `payment-service` ([`services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java`](../services/payment-service/src/main/java/com/samato/paymentservice/domain/Payment.java)) is the canonical example. The `events` table holds the JSONB event stream; `payment_snapshots` holds every-50-events snapshots. The `Payment` aggregate has **no setters** — every state change goes through `apply(PaymentEvent)`. The time-travel endpoint is `GET /api/payments/{id}/balance-at/{version}`.

## 8. Circuit breaker — why?

If a downstream is slow or down, you don't want to pile up threads waiting on it. The breaker **opens** after N failures, **fails fast** for a cool-down, then **half-opens** to test recovery. Resilience4j wraps your call in a try-with-fallback.

**In this repo:** `order-service` has `@CircuitBreaker` config in `application.yml` for `restaurant-client`. `payment-service` has the same for `razorpay-client`. The Feign `FallbackFactory` returns a `*UnavailableException` so the saga can distinguish "down" from "validation error".

## 9. Service discovery — why and how?

Instances come and go; you can't hard-code IPs. A **registry** (Eureka, Consul) lets services register and find each other. Client-side discovery (Eureka + Spring Cloud LoadBalancer) is simple; service-mesh sidecars (Istio) push this into the infra layer.

**In this repo:** Eureka at `:8761`. The gateway resolves `lb://SAMATO-PAYMENT-SERVICE` etc. through Eureka. Each service sets `eureka.client.service-url.defaultZone` in `application.yml`.

## 10. API Gateway — what problems does it solve?

- Single client entry point
- Centralized **auth** (validate JWT once)
- **Rate limiting** and **quotas**
- **Aggregation** (compose responses from multiple services)
- **Protocol translation** (REST → gRPC internal)
- **Routing** and versioning
- Hides internal topology from clients

**In this repo:** [`services/api-gateway/src/main/java/com/samato/apigateway/config/GatewayRoutesConfig.java`](../services/api-gateway/src/main/java/com/samato/apigateway/config/GatewayRoutesConfig.java) — one route block per service. Webhooks are special-cased: `payment-service` has its own `SecurityFilterChain` that allows `permitAll()` on `/api/payments/webhooks/**` because Razorpay uses HMAC, not JWT.

## 11. How do you handle distributed tracing?

Propagate a `traceId` and `spanId` across the call chain via headers (`traceparent` / W3C Trace Context, or `X-B3-*` from Zipkin). Each service creates **child spans** for outgoing calls. A tracing backend (Zipkin, Jaeger, Tempo) stitches the spans.

**In this repo:** Micrometer Tracing + Zipkin, configured in every service. The `shared` module's `CorrelationIdFilter` puts the trace id in MDC so it shows up in every log line.

## 12. Idempotency — why does it matter?

In distributed systems you'll retry. If a charge endpoint is called twice, you charge twice. Clients pass an `Idempotency-Key` header; the server stores the result against the key and returns the **same response** for repeats.

**In this repo:** two layers.
1. **Order-service** keeps an `idempotency_records` table; same `(key, body-hash)` returns the prior response.
2. **Payment-service** keeps a `processed_commands` table; same `(command_type, key)` returns the prior Razorpay result.

Both sagas pass the step id as the `Idempotency-Key` HTTP header. See [`services/payment-service/src/main/java/com/samato/paymentservice/idempotency/IdempotencyGuard.java`](../services/payment-service/src/main/java/com/samato/paymentservice/idempotency/IdempotencyGuard.java).

## 13. How do you handle webhooks safely?

External systems (Stripe, Razorpay, GitHub) push events to you. Two things must hold:
1. **Authenticate the sender.** JWT doesn't work — they don't have your key. HMAC SHA-256 of the body using a shared secret is the standard.
2. **Make handlers idempotent.** They'll retry. Dedup on the event id (each webhook has a unique id).

**In this repo:** `payment-service` does both.
- [`services/payment-service/src/main/java/com/samato/paymentservice/api/WebhookSignatureVerifier.java`](../services/payment-service/src/main/java/com/samato/paymentservice/api/WebhookSignatureVerifier.java) — constant-time HMAC compare.
- The `IdempotencyGuard` is called with the Razorpay event id as the key.

## 14. How do you deploy these?

- **Docker images** per service (multi-stage, distroless base, non-root user)
- **docker-compose** for local dev (this repo)
- **Kubernetes** for prod (rolling updates, HPA, PDBs, network policies)
- **Service mesh** (Istio/Linkerd) for mTLS, retries, traffic shifting

**In this repo:** Compose for local; K8s/Helm is on the Phase 8 roadmap.

## 15. Failure modes I should know

- **Cascading failure** — one slow service slows everyone. Fix: bulkheads, circuit breakers, timeouts.
- **Thundering herd** — retry storm after an outage. Fix: jittered exponential backoff.
- **Split brain** — two instances both think they're primary. Fix: leader election (Quorum/RAFT).
- **Data drift** — services disagree on shared facts. Fix: events + eventual consistency + reconcilers.
- **Lost messages** — broker or producer crashes between commit and send. Fix: outbox + idempotent consumers.
- **Webhook replay** — sender retries a successful event. Fix: idempotency keyed on event id.
- **Optimistic concurrency races** — two writers append to the same aggregate. Fix: `UNIQUE(aggregate_id, version)` constraint; let the loser retry.

## 16. The classic "design a system" answer skeleton

1. **Clarify requirements** (QPS, latency, consistency).
2. **Identify bounded contexts** → services.
3. **Pick storage per service** (SQL vs NoSQL, why).
4. **Sketch communication** (sync vs async, when).
5. **Address consistency** (saga? outbox? eventual?).
6. **Cross-cutting** (auth, rate limit, tracing, config, discovery).
7. **Failure modes** and how you handle them.
8. **Trade-offs** — explicitly call out what you sacrificed.

Use this repo as the worked example.

---

> For deeper, code-grounded answers, see the `docs/INTERVIEW-NOTES.md` inside each service.
> For what's actually been verified end-to-end, see [PROJECT-STATUS.md](../PROJECT-STATUS.md).
