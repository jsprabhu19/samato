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

---

## Bring-up debugging (2026-07-08)

The first time the full 18-container stack came up, nine real bugs surfaced. These are the ones with the most transferable lessons — the kind of thing interviewers love because they're not trivia, they're **post-mortems**.

### Q: "My `@Lob` field is now a PostgreSQL OID column and the insert fails with `column "payload" is of type oid`"

A: This is a Hibernate 6 behavior change that bites everyone migrating to Spring Boot 3. In Hibernate 5, `@Lob` on a `String` mapped to `CLOB`/`TEXT`, and on a `byte[]` mapped to `BLOB`/`BYTEA`. Hibernate 6 changed the default: `@Lob` on `String` now maps to `SQL CLOB` which Postgres translates to **OID** (large-object storage, not the in-row `text` type), and `@Lob` on `byte[]` maps to `BLOB` → **OID** instead of `BYTEA`. OID storage needs a separate `pg_largeobject` write, a different JDBC type, and a different fetch path — many drivers (including older `pgjdbc` versions) choke on it.

**Fix:** stop using `@Lob` for what is actually inline data. Use `@Column(columnDefinition = "TEXT")` for strings and `@JdbcTypeCode(SqlTypes.VARBINARY)` (or `@Column(columnDefinition = "BYTEA")`) for byte arrays. Hibernate then emits a normal `text`/`bytea` column and your inserts work as they did pre-3.0.

**Lesson:** the JPA annotation you used for years may have changed semantics underneath you. Read the Hibernate 6 migration guide's "breaking changes" section before you bump.

### Q: "Spring Boot 3.3 fails to start: `Failed to load property source from location 'classpath:/application.yml'`"

A: Spring Boot 3.3 tightened the YAML loader. The new rule: **`spring.config.import` files and `application.yml` must exist and be parseable as soon as the application context starts looking for them** — even before the `@ConfigurationProperties` binding runs. In 3.2, a missing or malformed `spring.config.import` would log a warning and the property source would simply be empty. In 3.3, it's a hard failure.

In this repo the trigger was a service whose `spring.config.import=optional:configserver:http://config-server:8888` pointed at a config server that wasn't up yet. Boot 3.2 was happy to keep going; Boot 3.3 aborted startup.

**Fix:** make the import truly optional (`optional:` prefix) and add a default empty config — or stand up the config server first. Don't rely on "Boot will tolerate this."

**Lesson:** "fail fast" is the new default. When you upgrade Spring Boot, re-read the *removed* and *stricter* sections of the release notes, not just the new features.

### Q: "The api-gateway throws `Duplicate key 'cloud' in YAML` on startup"

A: SnakeYAML (and Spring Boot's `YamlPropertySourceLoader`) treat duplicate top-level keys as a hard error during strict loading. A copy-paste merge left two `cloud:` blocks in `application.yml`:

```yaml
cloud:
  gateway:
    discovery:
      locator:
        enabled: true
...
cloud:                   # <-- duplicate!
  loadbalancer:
    ribbon:
      enabled: false
```

The second `cloud:` silently overwrote the first in Spring Boot 3.2's lax parser; in 3.3 the stricter parser rejects the file entirely. That's actually the *good* outcome — silent overrides are debugging nightmares.

**Fix:** merge them into a single `cloud:` block. As a general practice, never paste a "here's another config block" snippet into a YAML file without re-reading the file top-to-bottom first.

**Lesson:** YAML is not a hash map with last-write-wins semantics, at least not in modern Spring Boot. Tools that *do* silently overwrite (Helm, Ansible) hide bugs. Tools that *fail* on duplicates are your friends.

### Q: "My `KafkaTemplate<String, byte[]>` is not autowired — `required a single bean, but found 0`"

A: Spring Boot's `KafkaAutoConfiguration` defines a `KafkaTemplate<?, ?>` **only for the default serializer pair** (`KafkaTemplate<Object, Object>`), wired from `spring.kafka.producer.key-serializer` / `value-serializer`. It does **not** autowire a `KafkaTemplate<String, byte[]>` for you — generics are erased, and Spring's `byType` lookup can't match `KafkaTemplate<String, byte[]>` against the bean of type `KafkaTemplate<Object, Object>`.

The fix is a one-line bean declaration that reuses the producer factory but pins the generic types:

```java
@Bean
public KafkaTemplate<String, byte[]> bytesKafkaTemplate(
        ProducerFactory<String, byte[]> pf) {
    return new KafkaTemplate<>(pf);
}
```

(With a producer factory either borrowed from the default or configured with `ByteArraySerializer` / `StringSerializer` explicitly.)

**Lesson:** Spring's bean resolution is type-based, but the `byType` lookup uses **raw types** for parameterized beans. Whenever a generic type matters at the injection site — `KafkaTemplate`, `JdbcTemplate`, `RestTemplate` — declare the bean yourself. Don't assume `byName` or qualifier magic will save you.

### Q: "schema-registry is up but logs say `Connection to node -1 (localhost/127.0.0.1:9092) could not be established`"

A: The Bitnami schema-registry image (7.6) silently ignores the env-var names you'd expect. Both `SCHEMA_REGISTRY_KAFKA_BOOTSTRAP_SERVERS` (the upstream Confluent name) and `SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS` (a name we made up hoping it'd work) get dropped on the floor. The Java process starts with the baked-in default `kafkastore.bootstrap.servers=[PLAINTEXT://localhost:9092]`, then tries to talk to itself.

**Fix:** stop using env vars for this property. Mount a `schema-registry.properties` file directly:

```properties
kafkastore.bootstrap.servers=PLAINTEXT://kafka:9094
kafkastore.topic=_schemas
listeners=http://0.0.0.0:8081
```

Note the port: schema-registry is on the same Docker network as Kafka, so it uses the **internal** `INTERNAL://` listener (`kafka:9094`), not the external `localhost:9092`. The internal listener exists exactly for this kind of inter-container traffic.

**Lesson:** "trust the docs but verify with the actual logs." Read the *actual* container logs on first boot, not just the Compose output. If a property didn't take effect, the env-var name is the first place to suspect — image authors frequently remap upstream names.

### Q: "Redis health check is DOWN in /actuator/health even though Redis is up and the app is using it fine"

A: Spring Boot's `RedisHealthContributorAutoConfiguration` builds the health indicator from the **`RedisConnectionFactory` bean** *of the primary* Redis configuration. In this stack, `spring-boot-starter-data-redis` was on the classpath twice (once via `spring-cloud-starter-gateway`'s transitive, once via the gateway's own cache config). Spring created two `LettuceConnectionFactory` beans — one pointing at `localhost:6379` (the default from a starter), one at the configured `redis:6379`. The `@Primary` annotation was on the wrong one, so the health probe hit a non-existent local Redis while real traffic flowed through the right one.

**Fix:** mark the correct `LettuceConnectionFactory` `@Primary` explicitly, or — better — define a single `RedisConnectionFactory` `@Bean` yourself so there's only ever one candidate. Then the health indicator and your actual traffic are forced to use the same connection.

**Lesson:** Spring Boot's auto-configuration is "convention over configuration" — but only until you have *two* configurations. As soon as you start adding starter jars with overlapping responsibilities, the auto-configured beans multiply and `@Primary` ambiguity creeps in. Declare the one bean you care about explicitly; don't rely on autoconfig to converge.

### Q: "kafka-ui won't start: `Bind for 0.0.0.0:8081 failed: port is already allocated`"

A: The `user-service` Spring Boot app binds to host port **8081** (its `server.port`). We had originally mapped kafka-ui to `8081:8080` in the compose file — a common habit because `kafka-ui` *itself* listens on 8080 inside the container, and 8081 is the most memorable mapping. But on the host, 8081 was already taken by `user-service`. The error came back as a Docker network-driver failure, not a Spring Boot error, which made it look like a network problem until you read the line carefully.

**Fix:** map kafka-ui to a different host port (we used `8091:8080`); the in-network port stays the same. As a rule, **never reuse a host port that a business service already binds** — keep UI/dev ports (`8091`, `9090`, `9411`, `3000`) on their own range so they don't collide with service ports (`8080`-`9000`).

**Lesson:** docker-compose doesn't fail at parse time on duplicate host-port mappings — it fails at `docker compose up`, with a confusing driver error. Build a one-line port audit into the bring-up: `netstat -ano | grep -E ':(808[0-9]|809[0-9])' | sort` before `docker compose up` will catch 80% of these.

### Q: "DevDataSeeder crashes on second run: `duplicate key value violates unique constraint "users_email_key"`"

A: A `@PostConstruct` seeder ran on every startup. The first run inserted 50 demo users; the second run tried to insert the same 50, and Postgres (correctly) rejected the duplicates. The seeder assumed "dev DB starts empty," which is true on day 1 and never again.

**Fix:** make the seeder **idempotent**. Three options in order of preference:
1. `INSERT ... ON CONFLICT (email) DO NOTHING` — Postgres-native, race-safe.
2. Check for existence first, then insert — fine for single-instance, racy under parallelism.
3. Wrap the whole seed in a transaction and use a sentinel row to detect "already seeded" — overkill for dev.

**Lesson:** any code that "only runs once" is a load-bearing assumption. A `try { ... } catch (DuplicateKeyException) { /* assume already seeded */ }` is fine for dev; `ON CONFLICT DO NOTHING` is fine for prod, too. Idempotency is a habit, not a switch.
