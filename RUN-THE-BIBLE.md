# How to Run the Samato Bible

> *A step-by-step guide to bring the entire microservice stack up locally and exercise the saga end-to-end.*

This document is the **operator's manual**. The INTERVIEW-NOTES files in each service are the *designer notes*; this is the *getting-it-running* doc.

**Status (2026-07-07):** the full reactor now compiles and packages — `mvn -B -DskipTests package` returns **BUILD SUCCESS** for all 12 modules (9 services + parent + 2 shared libs). The build is verified; the runtime walkthrough below is not yet run end-to-end on this machine (see section 9).

The bible has 9 Spring Boot services backed by PostgreSQL, Kafka, Redis, OpenSearch, Eureka, Config Server, and an API Gateway. This guide takes you from a fresh checkout to "I just placed an order and the saga completed" in roughly 15–30 minutes, depending on Docker image pulls.

---

## 0. Prerequisites

Install these once. All are required.

### 0.1 Java 21

```powershell
java -version
```

If you have anything below 21, install Temurin 21 from https://adoptium.net/temurin-21/.

### 0.2 Maven 3.9+

> **Important:** The Maven shipped with most systems (3.3.9, 3.6.x) is too old for Spring Boot 3.3.4. Bump it first or you'll see `requires Maven version 3.6.3` errors from `maven-compiler-plugin`.

1. Download: https://maven.apache.org/download.cgi → **3.9.x** (binary zip)
2. Unzip somewhere on your disk — e.g. `E:\SW\apache-maven-3.9.16`
3. **Either** add `<unzip-dir>\bin` to your PATH, **or** invoke it by full path. The full-path form is what this guide uses, so the commands below work even if your `mvn` is still the old one:
   ```powershell
   E:\SW\apache-maven-3.9.16\bin\mvn -version
   # Apache Maven 3.9.16
   # Java version: 21.x
   ```
4. From here on, every `mvn` command in this doc is written for the new Maven. If you put it on the PATH first, drop the `E:\SW\apache-maven-3.9.16\bin\` prefix.

> **Heads up — what the rest of the doc assumes:** the new Maven lives at `E:\SW\apache-maven-3.9.16\bin\mvn`. If yours is elsewhere, substitute that path. The commands below all use the absolute path so they work out-of-the-box.

### 0.3 Docker Desktop

https://www.docker.com/products/docker-desktop/

Required for the infrastructure containers (postgres, kafka, redis, opensearch, eureka, etc.).

```powershell
docker --version
docker compose version
```

On Windows, accept the WSL2 backend prompt the first time you start Docker Desktop.

### 0.4 (Optional) Razorpay test keys

Only needed for the real money flow in section 5. Get free test keys from https://dashboard.razorpay.com/app/keys.

For everything below section 5, the placeholders in `application.yml` work — Razorpay calls will fail, but the saga and event store will still tick.

---

## 1. First compile (the most important step)

Before touching Docker, prove the code at least compiles. **The full reactor now builds cleanly** — see the verified command in section 1.1. If you've never run the build before, expect the Maven plugin to download ~500 MB of dependencies on the first call (Avro, Confluent, OpenSearch, etc.) and then succeed in 10–20 s on subsequent calls.

### 1.1 Build the whole reactor (verified)

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato
E:\SW\apache-maven-3.9.16\bin\mvn -B -DskipTests package
```

Expected final lines (with no `BUILD FAILURE`):

```
[INFO] Samato — Microservice Reference Bible  SUCCESS
[INFO] Shared Library .......................... SUCCESS
[INFO] Shared Kafka Library .................... SUCCESS
[INFO] Config Service .......................... SUCCESS
[INFO] Discovery Service ....................... SUCCESS
[INFO] API Gateway ............................. SUCCESS
[INFO] Auth Service ............................ SUCCESS
[INFO] User Service ............................ SUCCESS
[INFO] Restaurant Service ...................... SUCCESS
[INFO] Search Service .......................... SUCCESS
[INFO] Order Service ........................... SUCCESS
[INFO] Samato — Payment Service ................ SUCCESS
[INFO] BUILD SUCCESS
```

That command produces a Spring Boot fat JAR for every service under `services/*/target/*.jar`.

### 1.2 Faster builds while iterating

Use `-am` ("also-make") when you only want to work on one service and its upstream deps:

```powershell
E:\SW\apache-maven-3.9.16\bin\mvn -B -pl services/payment-service -am -DskipTests package
E:\SW\apache-maven-3.9.16\bin\mvn -B -pl services/order-service   -am -DskipTests package
```

`shared` and `shared-kafka` build first as transitive deps.

To re-build one service without rebuilding the world, drop `-am`:

```powershell
E:\SW\apache-maven-3.9.16\bin\mvn -B -pl services/payment-service -DskipTests package
```

(useful when shared libs haven't changed but you want to confirm a payment-service fix still compiles).

### 1.3 Skip tests for now

```powershell
-DskipTests
```

Tests are mostly Testcontainers-based and need Docker running with the right images. Get the main code building first; run tests after section 3 brings up the infra.

### 1.4 If you see a new error (unlikely)

The build was a known-broken state before this guide was written. A sweep of fixes is now committed (see section 1.5). If a new error appears on your machine, it's almost certainly one of:

| Error pattern | Cause | Fix |
|---|---|---|
| `requires Maven version 3.6.3` from `maven-compiler-plugin` | Old Maven (3.3.x, 3.6.x) on PATH | Use Maven 3.9.x — see section 0.2 |
| `Non-resolvable parent POM for com.samato:shared` | The `shared/pom.xml` `relativePath` is wrong | Should be `<relativePath>../pom.xml</relativePath>` (one level up, not two) |
| `Could not resolve dependencies: io.confluent:kafka-avro-serializer` | Maven Central doesn't host the Confluent artifacts | Make sure the root `pom.xml` has the `https://packages.confluent.io/maven/` repository block (it does) |
| `cannot find symbol: class Razorpay` in `RazorpayClientImpl` | The SDK class is `RazorpayClient`, not `Razorpay` | Use `com.razorpay.RazorpayClient` (fully qualified if your class also imports a same-named type) |
| `cannot infer type arguments for SpecificDatumReader<>` in `AvroBytes` | The registry value `Class<? extends SpecificRecord>` is too narrow for Avro's `SpecificDatumReader` constructor | Cast to raw `Class` and add `@SuppressWarnings({"unchecked", "rawtypes"})` |
| `boolean cannot be dereferenced` on `client.indices().exists(...).isExists()` | OpenSearch 2.x returns `boolean` directly from `exists()` | Drop the `.isExists()` call |

### 1.5 What was fixed (changelog of the build session)

The build went from "30 errors expected" to "BUILD SUCCESS" in one session. The fixes are committed as `876b497 fix: make mvn package succeed across all 12 modules` on `main`. Highlights:

- **Maven 3.9.x required** — the system Maven 3.3.9 in the dev environment is too old; use 3.9.16 (or any 3.9.x).
- **Confluent repo added** to root `pom.xml` for `io.confluent:kafka-avro-serializer:7.6.1` (not on Maven Central).
- **Sealed `PaymentEvent` / `PaymentCommand`** had a `permits` clause listing nested records by simple name — that only works inside one compilation unit. Dropped the redundant clause; callers now use `PaymentEvent.RazorpayOrderCreated` etc.
- **`DomainEvent` demoted to a marker interface.** Avro's code generator can't make generated classes implement a custom Java interface, so the abstract `getEventId` / `getAggregateId` methods on `DomainEvent` were unreachable. Switched the `KafkaTemplate<String, DomainEvent>` to `KafkaTemplate<String, SpecificRecord>`; consumers read `eventId` through `GenericRecord.get("eventId")`.
- **`Razorpay` → `RazorpayClient`** in `payment-service`. The SDK class is `com.razorpay.RazorpayClient`, and the resource fields are lowercase: `razorpay.orders.create(...)` and `razorpay.payments.refund(...)` (not `.Orders` / `.Payments`).
- **OpenSearch 2.15 client API**. `client.indices().exists()` returns `boolean` directly (no more `.isExists()`), and `create()` takes `org.opensearch.client.indices.CreateIndexRequest` (not the old `action.admin.indices.create` package).
- **`Payment()` no-arg constructor** was package-private but the command handler in `service/` needed it. Made it public with a comment.
- **`DomainException`** now has a `(code, message, httpStatus, cause)` constructor for `RestaurantUnavailableException` to chain the original Razorpay throw.
- **Lambda capture** in `JwtAuthFilter`: marked `userId`, `roles`, and `rolesHeader` final.
- **Order-service `OutboxPublisher` import**: the `OrderPlacedEvent` etc. classes are generated in `com.samato.events.*` (matches the `.avsc` namespace), not `com.samato.sharedkafka.events.*`.

---

## 2. Verify the runtime-only spots (compile-time issues are fixed)

Section 1 now compiles clean. These are the spots most likely to bite you **at runtime** — the build can't catch them. They were the focus of the original section 2, but most have been confirmed working by the build sweep; the remaining items here are the ones you still want to sanity-check after the first start.

### 2.1 ~~Verify the `JsonType` import~~ — done

The `JsonType` import in `payment-service` is the right `io.hypersistence.utils.hibernate.type.json.JsonType` (artifactId `hypersistence-utils-hibernate-63:3.7.7`). The build now confirms it resolves. If you ever swap Hibernate versions, the mapping is:

| Hibernate version | artifactId |
|---|---|
| 6.0.x | `hypersistence-utils-hibernate-60` |
| 6.2.x | `hypersistence-utils-hibernate-62` |
| 6.3.x / 6.4.x | `hypersistence-utils-hibernate-63` (no 64 published) |

### 2.2 ~~Verify Razorpay SDK class names~~ — done

`RazorpayClientImpl` now uses the correct SDK class `com.razorpay.RazorpayClient` and the lowercase resource fields `razorpay.orders` / `razorpay.payments`. The webhook signature check uses the real `com.razorpay.Utils.verifyWebhookSignature(String payload, String signature, String secret)`. The build confirms all three call sites compile.

> **Historical workaround** (no longer needed): the `WebhookSignatureVerifier` class in the codebase is a hand-rolled HMAC fallback. It used to be a safety net if the SDK call site failed. Now that the SDK call works, the impl class uses the SDK and only falls back to the hand-rolled one if the SDK throws.

### 2.3 Verify the JSONB query field names

The native query in `EventStoreEntryRepository` references `event_data->>'razorpayOrderId'`. This is **case-sensitive**. Jackson serialises `record` components using their component name as the JSON key, so a record:

```java
record RazorpayOrderCreated(..., String razorpayOrderId, ...) { ... }
```

produces JSON key `razorpayOrderId`. Match.

**But** if a record uses `razorpay_order_id` (snake_case), the JSON key is `razorpay_order_id` and the query returns zero rows.

Verify by running this after first start:
```sql
SELECT event_data FROM events LIMIT 1;
```
Look at the actual key. If it doesn't match, either:
- Rename the record component, or
- Update the SQL to use the actual key

### 2.4 Verify Outbox topic auto-creation

Kafka topics in this setup are usually **auto-created** on first publish. The `OutboxPublisher` publishes to:
- `samato.payment.created`
- `samato.payment.charged`
- `samato.payment.failed`
- `samato.payment.refund.initiated`
- `samato.payment.refunded`
- `samato.payment.expired`
- `samato.order.placed`
- `samato.order.confirmed`
- `samato.order.cancelled`

If they don't auto-create (depends on broker config), pre-create them via `kafka-topics --create` or via the schema-registry admin.

### 2.5 ~~Verify the gateway's Eureka service id~~ — done

`lb://SAMATO-PAYMENT-SERVICE` in `GatewayRoutesConfig.java` is correct. Eureka uppercases the registered service name. The payment-service's `spring.application.name` is `samato-payment-service`, so the registered id is `SAMATO-PAYMENT-SERVICE`. Match.

### 2.6 The `Order.PAID` transition is optional

The saga currently goes `RESERVED → CONFIRMED` directly. That's fine for the bible. Wiring `PAID` via a Kafka listener on `samato.payment.charged` is documented as Phase 6 work in `services/order-service/docs/INTERVIEW-NOTES.md`.

---

## 3. Bring up the infrastructure

### 3.1 Start the foundation (postgres, kafka, redis, opensearch)

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato\infra
docker compose up -d postgres kafka redis opensearch
```

Wait ~30 seconds, then verify:

```powershell
docker compose ps
# postgres, kafka, redis, opensearch should be "healthy" or "running"
```

Smoke test the database:
```powershell
docker exec -it samato-postgres psql -U fd -d payment_service -c "SELECT 1;"
# Should print: 1
```

### 3.2 Start the platform services (eureka, config, gateway)

```powershell
docker compose up -d discovery-service config-service api-gateway
```

Wait ~30 seconds, then check:

```powershell
# Eureka dashboard
curl http://localhost:8761
# Should show HTML

# Config server health
curl http://localhost:8888/actuator/health
# {"status":"UP"}
```

If the config server is down, look at its logs:
```powershell
docker logs samato-config-service
# Usually: missing git repo, or a "Could not resolve placeholder" error
```

### 3.3 Start the business services

```powershell
docker compose up -d auth-service user-service restaurant-service search-service order-service payment-service
```

Wait ~60 seconds for the slower services (Spring context startup + Flyway migrations).

```powershell
docker compose ps
# All should be "Up" or "Up (healthy)"
```

Eureka should now show all 9 services:
```powershell
curl http://localhost:8761/eureka/apps
# Big XML dump; each <instance> is a registered service
```

If a service keeps restarting, see section 7.

---

## 4. Verify the wire is hot

### 4.1 Register a user

```powershell
curl -X POST http://localhost:8080/api/auth/register `
  -H "Content-Type: application/json" `
  -d '{
    "email": "alice@example.com",
    "password": "Password123!",
    "name": "Alice"
  }'
```

### 4.2 Get a JWT

```powershell
curl -X POST http://localhost:8080/api/auth/login `
  -H "Content-Type: application/json" `
  -d '{
    "email": "alice@example.com",
    "password": "Password123!"
  }'
```

The response is a JWT. Save it as a PowerShell variable:

```powershell
$JWT = "eyJhbGciOi..."   # paste the access_token from the login response
```

### 4.3 List restaurants and menu items

```powershell
curl http://localhost:8080/api/restaurants -H "Authorization: Bearer $JWT"
# Pick one; save its id

curl http://localhost:8080/api/restaurants/<restaurant-id>/menu -H "Authorization: Bearer $JWT"
# Pick a menu item id
```

### 4.4 Place an order (drives the saga)

```powershell
curl -X POST http://localhost:8080/api/orders `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $JWT" `
  -d '{
    "restaurantId": "<restaurant-uuid>",
    "items": [
      {"menuItemId": "<menu-item-uuid>", "quantity": 2}
    ]
  }'
```

Save the order id. The saga starts asynchronously; poll for status:

```powershell
curl http://localhost:8080/api/orders/<order-uuid> -H "Authorization: Bearer $JWT"
```

You should see the order move through:
```
PLACED → VALIDATED → RESERVED → CONFIRMED
```

(The `PAID` state is a Phase 6 todo — see `services/order-service/docs/INTERVIEW-NOTES.md` §12.)

### 4.5 Inspect the saga steps

```powershell
curl http://localhost:8080/api/orders/<order-uuid>/saga -H "Authorization: Bearer $JWT"
```

Each step should be `COMPLETED` (or `COMPENSATED` if something failed).

### 4.6 Inspect the payment view

```powershell
docker exec -it samato-postgres psql -U fd -d payment_service -c \
  "SELECT payment_id, razorpay_order_id, status, amount, currency FROM payment_view ORDER BY last_event_seq DESC LIMIT 5;"
```

You should see one row with status `ORDER_CREATED` (Razorpay's hosted checkout isn't driven end-to-end in a curl test — that's a browser step; see section 5).

### 4.7 Inspect the event store

```powershell
docker exec -it samato-postgres psql -U fd -d payment_service -c \
  "SELECT sequence_number, event_type, version, event_data->>'razorpayOrderId' AS rzp_order FROM events ORDER BY sequence_number DESC LIMIT 10;"
```

Should show at least one `RazorpayOrderCreated` event for the test order.

---

## 5. Observability

### 5.1 Kafka UI

Open http://localhost:8081 in a browser. You should see the `samato` cluster, all topics, and live messages flowing after each order.

Topics of interest:
- `samato.payment.created` — Razorpay order created
- `samato.payment.charged` — payment captured
- `samato.payment.failed` — payment failed
- `samato.payment.refunded` — refund completed
- `samato.order.confirmed` / `samato.order.cancelled` — order-service events

### 5.2 Prometheus

http://localhost:9090

Query examples:
```promql
# Request rate per service
rate(http_server_requests_seconds_count{application="samato-payment-service"}[1m])

# Saga step duration p99
histogram_quantile(0.99, sum(rate(saga_step_duration_seconds_bucket[5m])) by (le, step_type))
```

### 5.3 Grafana

http://localhost:3000 (admin / admin)

Import a Spring Boot dashboard (community dashboard ID 11378 is a good starting point). Point it at the Prometheus datasource (already provisioned).

### 5.4 Zipkin (distributed tracing)

http://localhost:9411

Each service uses Micrometer Tracing. Trace ids propagate through the saga, so you can see the full path: order-service → payment-service → Razorpay.

### 5.5 Direct service logs

```powershell
docker logs -f samato-order-service
docker logs -f samato-payment-service
docker logs -f samato-restaurant-service
```

The `-f` flag tails the logs live.

---

## 6. Drive Razorpay end-to-end (the money flow)

This part requires a real Razorpay test account. Skip it if you just want to see the saga tick.

### 6.1 Set the test keys

Edit `infra/docker-compose.yml` (or use a `.env` file):

```yaml
RAZORPAY_KEY_ID: rzp_test_xxxxxxxxxxxxxx
RAZORPAY_KEY_SECRET: xxxxxxxxxxxxxxxxxxxxxxxx
RAZORPAY_WEBHOOK_SECRET: xxxxxxxxxxxxxxxxxxxxxxxx
```

Restart:
```powershell
docker compose restart payment-service
```

### 6.2 Set up the Razorpay webhook

Razorpay's webhooks need a public URL. For local testing, use ngrok:

```powershell
ngrok http 8084
# Copy the https URL: e.g. https://a1b2c3d4.ngrok.io
```

In the Razorpay dashboard → Webhooks → Add new:
- URL: `https://<your-ngrok-url>/api/payments/webhooks/razorpay`
- Events: `payment.captured`, `payment.failed`, `refund.processed`
- Secret: same as `RAZORPAY_WEBHOOK_SECRET`

### 6.3 Drive a payment in the browser

Razorpay's test mode lets you use a hosted checkout page. To drive it, write a small HTML page that loads Razorpay's JS SDK with the `razorpayOrderId` from the saga response and the test key:

```html
<script src="https://checkout.razorpay.com/v1/checkout.js"></script>
<button id="pay">Pay</button>
<script>
  document.getElementById('pay').onclick = async () => {
    const orderId = '<razorpay_order_id from /api/payments/{id}>';
    const resp = await fetch(`/api/payments/${orderId}`).then(r => r.json());
    new Razorpay({
      key: '<RAZORPAY_KEY_ID>',
      order_id: resp.razorpayOrderId,
      amount: resp.amount * 100,
      currency: resp.currency,
      handler: (r) => console.log('paid', r),
    }).open();
  };
</script>
```

Test card numbers: https://razorpay.com/docs/payments/payments/test-card-details/

### 6.4 Verify the webhook flipped the state

After the test card "succeeds", Razorpay sends the webhook. Within seconds:

```powershell
docker exec -it samato-postgres psql -U fd -d payment_service -c \
  "SELECT payment_id, status FROM payment_view ORDER BY last_event_seq DESC LIMIT 5;"
```

Status should now be `CAPTURED`. The `events` table should have a new `PaymentCaptured` row.

---

## 7. Troubleshooting

### Symptom: a service keeps restarting

```powershell
docker logs samato-payment-service --tail 100
```

Common causes:

| Cause | Fix |
|---|---|
| Postgres connection refused | Check that `postgres` container is healthy: `docker compose ps` |
| Kafka connection refused | Check that `kafka` container is healthy and the topic auto-create is on |
| Eureka registration timeout | Check that `discovery-service` is up first (start order: discovery → config → others) |
| Flyway migration failed | `docker exec -it samato-postgres psql -U fd -d payment_service -c "SELECT * FROM flyway_schema_history;"` |
| OOMKilled | Increase Docker memory: Docker Desktop → Settings → Resources → Memory → 8GB+ |

### Symptom: 401 on every request

JWT key-set URI is wrong. Check:

```powershell
docker logs samato-auth-service --tail 50
# Look for "started on port(s): 9000" or similar

curl http://localhost:9000/.well-known/jwks.json
# Should return JWKS JSON
```

If empty, the auth service hasn't started its JWKS endpoint yet. Wait 10 seconds and retry.

### Symptom: saga stuck in RESERVED

The payment-service didn't reply. Check:

```powershell
docker logs samato-order-service --tail 200 | Select-String -Pattern "payment"
# Look for the CHARGE_PAYMENT step output

docker logs samato-payment-service --tail 200
```

Common cause: Razorpay API call failed because keys are placeholders. The fallback throws, the saga catches, retries. Set real keys to break the loop.

### Symptom: events not appearing in payment_view

The projector failed. The command handler log will show what:

```powershell
docker logs samato-payment-service --tail 200 | Select-String -Pattern "projector"
```

Most likely: a JSON deserialisation mismatch (e.g. `RazorpayOrderCreated` field name drift). The error will name the event type and the field.

### Symptom: nothing in events table

The append failed. Look for `DataIntegrityViolationException` — that means a version conflict (concurrent writer). For a single curl, you shouldn't see this; if you do, the saga is somehow retrying concurrently.

### Symptom: "Address already in use" on startup

A service from a previous run didn't shut down cleanly:

```powershell
docker compose down
# Then bring it back up
```

Or, for a port conflict on the host (e.g. 8080 in use by something else):
```powershell
netstat -ano | findstr :8080
taskkill /F /PID <pid-from-above>
```

### Symptom: WebSocket connection refused (Kafka UI, Grafana live)

The browser is trying `ws://` and failing. This is harmless — refresh, or open the UI in an incognito window.

### Nuclear option: reset everything

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato\infra
docker compose down -v        # -v removes volumes (postgres data, kafka data, etc.)
docker compose up -d
```

This is a fresh start. All data is lost.

---

## 8. The minimum viable run (TL;DR)

If you just want to see *something* run:

1. Install Java 21, Maven 3.9.x (must be 3.9+; the system one is too old), Docker Desktop
2. `cd samato` and `E:\SW\apache-maven-3.9.16\bin\mvn -B -DskipTests package` — the build is now verified
3. `cd infra && docker compose up -d`
4. Wait 90 seconds
5. `curl http://localhost:8761` — Eureka is up
6. Register, login, place an order, watch the saga in `docker logs samato-order-service`

That's the path. The Razorpay end-to-end money flow is a separate session of work (section 6) and requires real test keys.

---

## 9. What's actually verified vs. unverified

| Step | Verified? |
|---|---|
| Java + Maven + Docker installed | User must verify |
| `mvn -B -DskipTests package` succeeds for all 12 modules | **Verified** — see the BUILD SUCCESS transcript in section 1.1 |
| `docker compose up -d` brings up the stack | **Unverified** — depends on images and Docker memory |
| Saga runs end-to-end | **Unverified** — code matches the order-service pattern but never executed on this machine |
| Razorpay test mode | **Unverified** — keys are placeholders; real flow needs real keys |
| Kafka topics auto-create | Depends on broker config |
| Webhook signature verification | Code verified to compile against the real `com.razorpay.Utils.verifyWebhookSignature`; runtime path is unverified |
| Time-travel queries | **Unverified** — endpoint exists, replay logic present |
| Event-sourced replay produces identical state | **Unverified** — code reviewed but no test run yet |

**Treat this guide as a starting point, not a guarantee.** The build now succeeds (section 1.1 is verified). What's unverified is the runtime: bringing Docker up, exercising the saga, and watching the Razorpay webhook flip the payment state. When a service won't start, share `docker logs` and we'll trace it.

---

## 10. Related docs

- `README.md` — project overview
- `ARCHITECTURE.md` — service topology and data flow
- `PROJECT-STATUS.md` — what's done and what's pending
- `services/*/docs/INTERVIEW-NOTES.md` — designer notes per service (the *why*)
- `services/payment-service/docs/INTERVIEW-NOTES.md` — the payment service deep-dive

---

*Built for the Samato bible. Tests pending. Money in test mode. Tread with care; document everything.*
