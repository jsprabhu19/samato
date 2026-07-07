# Interview Cheat-Sheet

Quick-look Q&A for the most-asked microservice interview topics. Each entry points at the code in this repo that demonstrates the answer.

---

## 1. What is a microservice?

A small, autonomous service that owns **one bounded context**, has its **own datastore**, and communicates with others over a **network** (HTTP, gRPC, messaging). Trade-offs: independent deploy + scale vs. operational overhead + distributed-system complexity.

**In this repo:** 12 services, each with its own Postgres. See [ARCHITECTURE.md](../ARCHITECTURE.md).

## 2. How do you decompose a monolith?

By **bounded contexts** (DDD), not by technical layers. Group things that **change together** for the same business reason. Start coarse; split when the data model or team boundaries demand it.

**In this repo:** `restaurant` is split from `order` because restaurants are a *catalog* (read-heavy) while orders are a *workflow* (write-heavy, transactional).

## 3. Sync vs. async communication — when do you use which?

- **Sync (REST/gRPC):** when the caller **needs the answer now** and the callee is the source of truth (e.g., `GET /restaurant/{id}`).
- **Async (Kafka/RabbitMQ):** when the work is **decoupled**, **eventual** is acceptable, you want **replay**, or you're crossing a **workflow boundary** (e.g., `OrderPlaced` → payment).

**Rule of thumb:** if you can answer "what happens if this fails?" without mentioning the caller, use async.

## 4. What's the Saga pattern?

A way to maintain **consistency across services** without a distributed transaction. Each step has a **compensating action** that undoes it. Two flavors:
- **Choreography** — services emit events, others react. Simple, but hard to see the whole flow.
- **Orchestration** — a central coordinator drives the steps. Easier to reason about, easier to add steps.

**In this repo:** `order-service` is the Saga orchestrator for the order → payment → restaurant → delivery flow.

## 5. Why is dual-write (DB + message broker) dangerous?

You can succeed at one and fail at the other, leaving the system inconsistent. The fix is the **Outbox pattern**: write the event to an `outbox` table in the **same DB transaction** as the business state; a separate publisher reads the outbox and ships to Kafka. At-least-once delivery + consumer-side idempotency = effectively-once.

**In this repo:** planned for `order-service` in Phase 4.

## 6. CQRS — what and why?

**Command-Query Responsibility Segregation.** Writes go to a normalized model; reads come from a **denormalized projection** built by consuming events. Why: optimize reads without distorting writes, scale them independently, support multiple read models per write model.

**In this repo:** `restaurant-service` will keep a write model in Postgres and a read model in OpenSearch + Redis.

## 7. Event Sourcing — when?

Store **state as a sequence of events** instead of the current row. You rebuild state by replaying events. Use when you need a **full audit trail**, **time-travel**, or **temporal queries** (e.g., "what did the account look like on March 1?").

**In this repo:** `payment-service` — money movement needs an audit-grade ledger.

## 8. Circuit breaker — why?

If a downstream is slow or down, you don't want to pile up threads waiting on it. The breaker **opens** after N failures, **fails fast** for a cool-down, then **half-opens** to test recovery. Resilience4j wraps your call in a try-with-fallback.

**In this repo:** gateway → downstream calls will use Resilience4j in Phase 7.

## 9. Service discovery — why and how?

Instances come and go; you can't hard-code IPs. A **registry** (Eureka, Consul) lets services register and find each other. Client-side discovery (Eureka + Ribbon-style load balancer) is simple; service-mesh sidecars (Istio) push this into the infra layer.

**In this repo:** Eureka is up at `:8761`. The gateway uses it to resolve routes dynamically.

## 10. API Gateway — what problems does it solve?

- Single client entry point
- Centralized **auth** (validate JWT once)
- **Rate limiting** and **quotas**
- **Aggregation** (compose responses from multiple services)
- **Protocol translation** (REST → gRPC internal)
- **Routing** and versioning
- Hides internal topology from clients

**In this repo:** `api-gateway` at `:8080`.

## 11. How do you handle distributed tracing?

Propagate a `traceId` and `spanId` across the call chain via headers (`traceparent` / W3C Trace Context, or `X-B3-*` from Zipkin). Each service creates **child spans** for outgoing calls. A tracing backend (Zipkin, Jaeger, Tempo) stitches the spans.

**In this repo:** Micrometer Tracing + Zipkin, configured in every service.

## 12. Idempotency — why does it matter?

In distributed systems you'll retry. If a charge endpoint is called twice, you charge twice. Clients pass an `Idempotency-Key` header; the server stores the result against the key and returns the **same response** for repeats.

**In this repo:** order placement and payment endpoints will require `Idempotency-Key`.

## 13. How do you deploy these?

- **Docker images** per service (multi-stage, distroless)
- **docker-compose** for local dev
- **Kubernetes** for prod (rolling updates, HPA, PDBs, network policies)
- **Service mesh** (Istio/Linkerd) for mTLS, retries, traffic shifting

**In this repo:** Compose for local; K8s/Helm roadmap (Phase 8).

## 14. Failure modes I should know

- **Cascading failure** — one slow service slows everyone. Fix: bulkheads, circuit breakers, timeouts.
- **Thundering herd** — retry storm after an outage. Fix: jittered exponential backoff.
- **Split brain** — two instances both think they're primary. Fix: leader election (Quorum/RAFT).
- **Data drift** — services disagree on shared facts. Fix: events + eventual consistency + reconcilers.
- **Lost messages** — broker or producer crashes between commit and send. Fix: outbox + idempotent consumers.

## 15. The classic "design a system" answer skeleton

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
