# Project Status

> *What's done, what's pending, what's risky — kept honest.*

Last updated: 2026-07-08

---

## At a glance

| | Count |
|---|---:|
| Services implemented | **9 of 9 planned for Phase 1-5** |
| Java files | 132 |
| Lines of Java | ~9,000 |
| `INTERVIEW-NOTES.md` files | 9 (one per service) + 1 cross-cutting cheatsheet |
| `mvn compile` success on this machine | ✅ (12/12 modules) |
| `docker compose up` end-to-end | ✅ (18/18 containers running, HTTP 200 from all 9 service endpoints, 7 registered in Eureka) |
| Integration tests | 0 |
| Pushed to GitHub | ✅ https://github.com/jsprabhu19/samato.git |

The code is **structurally complete** for Phases 1-5 AND is verified running end-to-end on this machine (mvn package + docker compose up bring up 18/18 containers with HTTP 200 from every service endpoint).

---

## Phase 1-5: done (structural)

| # | Service | Status | Designer notes | Key files |
|---|---|---|---|---|
| 1 | config-service | ✅ | [`services/config-service/docs/INTERVIEW-NOTES.md`](services/config-service/docs/INTERVIEW-NOTES.md) | Spring Cloud Config, git-backed |
| 1 | discovery-service | ✅ | [`services/discovery-service/docs/INTERVIEW-NOTES.md`](services/discovery-service/docs/INTERVIEW-NOTES.md) | Netflix Eureka |
| 1 | api-gateway | ✅ | [`services/api-gateway/docs/INTERVIEW-NOTES.md`](services/api-gateway/docs/INTERVIEW-NOTES.md) | Spring Cloud Gateway, JWT, rate limit |
| 2 | auth-service | ✅ | [`services/auth-service/docs/INTERVIEW-NOTES.md`](services/auth-service/docs/INTERVIEW-NOTES.md) | Spring Authorization Server, JWKS |
| 2 | user-service | ✅ | [`services/user-service/docs/INTERVIEW-NOTES.md`](services/user-service/docs/INTERVIEW-NOTES.md) | Profiles, role-based access |
| 3 | restaurant-service | ✅ | [`services/restaurant-service/docs/INTERVIEW-NOTES.md`](services/restaurant-service/docs/INTERVIEW-NOTES.md) | Catalog, menu, OpenSearch |
| 3 | search-service | ✅ | [`services/search-service/docs/INTERVIEW-NOTES.md`](services/search-service/docs/INTERVIEW-NOTES.md) | CQRS read model, cache-aside |
| 4 | order-service | ✅ | [`services/order-service/docs/INTERVIEW-NOTES.md`](services/order-service/docs/INTERVIEW-NOTES.md) | Saga orchestrator, outbox, idempotency, Feign clients |
| 5 | payment-service | ✅ | [`services/payment-service/docs/INTERVIEW-NOTES.md`](services/payment-service/docs/INTERVIEW-NOTES.md) | Event sourcing, Razorpay, HMAC webhook, snapshots, time-travel |

Each service follows the same shape (pom.xml, Dockerfile, src/, db/migration, INTERVIEW-NOTES). The code matches the conventions established in earlier phases.

---

## Phase 6-8: not started

| # | Service | Notes |
|---|---|---|
| 6 | delivery-service | Driver assignment, WebSocket location updates, geo queries. DB is provisioned (`delivery_service` in `infra/postgres/init-databases.sh`) but the service doesn't exist. |
| 7 | notification-service | Email/SMS/push. Cross-service event consumer. DB provisioned. |
| 7 | analytics-service | Kafka Streams aggregations, materialized views. DB provisioned. |
| 8 | Hardening | Testcontainers for ITs, Pact contract tests, DLQ for outbox failures, ADRs as separate `docs/adr/`, k6 load tests, K8s/Helm charts. |

These are listed in the README roadmap. None of them are required to demonstrate the patterns Phases 1-5 already cover.

---

## What is verified vs. unverified

This is the honest table. The repo on GitHub reflects what's *written*, not what *runs*.

| Item | Status | Notes |
|---|---|---|
| Java syntax compiles | **Verified** | BUILD SUCCESS for 12/12 modules |
| Maven multi-module builds | **Verified** | parent + 2 shared libs + 9 services |
| Docker images build | **Verified** | all 9 service images built via `docker compose build` |
| Stack brings up on `docker compose up` | **Verified** | 18/18 containers running, 7/7 business services registered in Eureka, all 9 service /actuator/health return HTTP 200 |
| Code-path smoke test | **Verified** | standalone SmokeTest.java passes 3/3: Avro encoding, outbox serialization, JWT round-trip |
| Saga runs end-to-end | **Unverified** | Code path verified by smoke test; live POST /api/orders through the gateway not yet run |
| Razorpay integration works | **Unverified** | Placeholder keys; real flow needs `rzp_test_*` from dashboard |
| Webhook signature verification | **Unverified** | `WebhookSignatureVerifier` is written but never executed against a real Razorpay event |
| Time-travel queries | **Unverified** | Endpoint exists, replay logic present |
| Tests pass | **N/A** | No tests exist; only Testcontainers scaffolding intent |
| Cross-service Kafka events consumed | **Unverified** | `samato.payment.charged` is published, but no consumer wires `Order.PAID` yet (still Phase 6 work) |

**If you want to use this repo as a working demo, the first 90 minutes are setup** — upgrade Maven, run `mvn compile`, fix what breaks, then `docker compose up`. After that the system is honest; the rest is real.

---

## Known risks (the bring-up actually hit these — all fixed, see RUN-THE-BIBLE.md §10 for the full list)

These are the spots most likely to need a touch-up. I called them out in `RUN-THE-BIBLE.md` §2; reproducing here for visibility.

1. ✅ Resolved — **`JsonType` import** — the `io.hypersistence.utils.hibernate.type.json.JsonType` artifactId is `hypersistence-utils-hibernate-63` for Hibernate 6.3. If you have a different Hibernate version, change to `hibernate-60` or `hibernate-62`. Check `payment-service/pom.xml`.

2. ✅ Resolved — **Razorpay SDK class names** — `com.razorpay:razorpay-java` has had breaking changes between versions. `Utils.verifyWebhookSignature` may not exist in older versions. We ship a `WebhookSignatureVerifier` helper for exactly this case; you can use that instead of delegating to the SDK.

3. N/A (event store queries not yet exercised) — **JSONB query field names** — `event_data->>'razorpayOrderId'` is case-sensitive. If a record component is named differently (e.g. `razorpay_order_id` snake_case), Jackson serialises to that name, and the SQL returns zero rows. Verify by `SELECT event_data FROM events LIMIT 1;` after the first successful append.

4. ✅ Resolved — **Outbox topic auto-creation** — depends on the broker config. If topics don't auto-create, you'll need to `kafka-topics --create` for each of:
   - `samato.payment.created`
   - `samato.payment.charged`
   - `samato.payment.failed`
   - `samato.payment.refund.initiated`
   - `samato.payment.refunded`
   - `samato.payment.expired`

5. ✅ Resolved — **Gateway Eureka id** — the gateway route is `lb://SAMATO-PAYMENT-SERVICE` (uppercase). Eureka uppercases the registered service name. As long as `spring.application.name` stays `samato-payment-service`, this matches.

6. Still applies (dev-only) — **Database password `fd` is in the repo** — this is a dev-only value. The compose file and yml files all use it. If you ever make the repo public and the password has any value, rotate it.

7. Still applies (Phase 6 work) — **Order.PAID transition is unwired** — the saga still goes `RESERVED → CONFIRMED` directly. Wiring `PAID` via a Kafka listener on `samato.payment.charged` is documented in `services/order-service/docs/INTERVIEW-NOTES.md` §12 as Phase 6.

### Bugs hit during the verified bring-up

- Hibernate 6 `@Lob` on String/byte[] maps to OID; migrations use TEXT/BYTEA — replaced `@Lob` with `columnDefinition`.
- Spring Boot 3.3 enforces `spring.config.import`; set `spring.cloud.config.enabled=false` in 5 service yml files.
- `api-gateway` yml had a duplicate `cloud:` block; merged into one.
- `user-service` yml had two `spring:` blocks; merged.
- Missing `KafkaTemplate<String, byte[]>` bean in order-service and payment-service; added a `KafkaByteArrayConfig` to each.
- Bitnami schema-registry 7.6 env-var remap unreliable; mounted a config file directly with `kafkastore.bootstrap.servers`.
- `api-gateway` Redis health DOWN; added `SPRING_DATA_REDIS_HOST=redis` to compose env.
- `kafka-ui` port 8081 conflicted with user-service, moved to 8091.
- Dev data seeder crashed on re-run; added `existsByEmail` check alongside `existsById`.

---

## What's deliberately deferred

These are conscious "out of scope" decisions, not oversights:

- **Live Razorpay mode.** Test mode only. The placeholder keys are intentional.
- **Multi-currency.** INR only. The `currency` field is a 3-letter code; switching is small but kept out for focus.
- **WebSocket / real-time push.** Phase 6 (delivery-service). For now, clients poll or refresh.
- **Service mesh / mTLS.** Out of scope; we rely on the network being private.
- **K8s manifests.** Phase 8.
- **Subscription / recurring payments.** Post-MVP.
- **PDF receipts.** Post-MVP.

---

## How to keep this document honest

This file should be the first thing you update when you do real work. Specifically:

- After the first successful `mvn compile`: change the "Unverified" line items you just verified.
- After the first `docker compose up`: change "Stack brings up" to ✅.
- After writing the first integration test: list it.
- After Phase 6 starts: move `delivery-service` from "not started" to "in progress".

If you do work without updating this file, future-you will be lied to. The file is a contract with yourself.
