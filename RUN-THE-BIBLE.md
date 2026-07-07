# How to Run the Samato Bible

> *A step-by-step guide to bring the entire microservice stack up locally and exercise the saga end-to-end.*

This document is the **operator's manual**. The INTERVIEW-NOTES files in each service are the *designer notes*; this is the *getting-it-running* doc.

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

> **Important:** The Maven shipped with most systems (3.3.9, 3.6.x) is too old for Spring Boot 3.3.4. Bump it first or you'll see `Maven 3.x is not supported by Spring Boot 3.x` errors.

1. Download: https://maven.apache.org/download.cgi → **3.9.9** (binary zip)
2. Unzip to `C:\tools\maven`
3. Add `C:\tools\maven\bin` to your PATH (System Properties → Environment Variables)
4. Verify:
   ```powershell
   mvn -version
   # Apache Maven 3.9.9
   # Java version: 21.x
   ```

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

Before touching Docker, prove the code at least compiles. **Expect 5–30 small errors** (missing imports, wrong SDK method names, signature mismatches). Fix them as you go; the patterns should match the existing order-service.

### 1.1 Compile from the project root

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato
mvn -B -DskipTests compile
```

Read the errors top to bottom. Do not try to fix all 30 at once — fix the topmost, recompile, repeat.

### 1.2 Compile individual services if the root fails

```powershell
mvn -B -pl services/payment-service -am -DskipTests compile
mvn -B -pl services/order-service   -am -DskipTests compile
```

The `-am` ("also-make") flag builds upstream modules (`shared`, `shared-kafka`) first.

### 1.3 Skip tests for now

```powershell
-DskipTests
```

Tests will fail because of missing test infrastructure (Testcontainers, etc.). Get the main code compiling first.

### 1.4 Common errors and fixes

| Error pattern | Fix |
|---|---|
| `cannot find symbol: class Utils` in `RazorpayClientImpl` | The SDK version in `payment-service/pom.xml` is wrong. Try `1.4.5` or the latest. |
| `package io.hypersistence.utils.hibernate.type.json does not exist` | Add the dep to `payment-service/pom.xml` (artifactId `hypersistence-utils-hibernate-63`, version `3.7.7`). If your Hibernate version differs, change to `hibernate-60` / `hibernate-62`. |
| `Optional<Payment> cannot be converted to Payment` in some call site | Use `.orElseThrow(...)` at the call site. |
| `@Lazy` self-injection failure in `SagaEngine` | The pattern is fine — just make sure `SagaEngine` is the only `@Service` of that type. |
| Native query returns no rows | See section 2.3 below. |
| Spring Cloud version mismatch | Use `2023.0.3` (matches the plan). |

---

## 2. Verify the likely-broken spots

These are the spots most likely to need a touch-up. I'm calling them out so you don't have to discover them by reading stack traces.

### 2.1 Verify the `JsonType` import

```java
import io.hypersistence.utils.hibernate.type.json.JsonType;
```

If you see `cannot find symbol`, the artifactId is wrong. The mapping is:

| Hibernate version | artifactId |
|---|---|
| 6.0.x | `hypersistence-utils-hibernate-60` |
| 6.2.x | `hypersistence-utils-hibernate-62` |
| 6.3.x | `hypersistence-utils-hibernate-63` |
| 6.4.x | `hypersistence-utils-hibernate-63` (same package, no 64) |

### 2.2 Verify Razorpay SDK class names

The `com.razorpay:razorpay-java` SDK has had breaking changes between versions. The most likely breakage is `Utils.verifyWebhookSignature` — in some versions it's renamed or has a different signature.

**Workaround:** If the SDK call fails to compile, replace it with our own verifier:

```java
@Override
public boolean verifyWebhookSignature(String payload, String signature) {
    return com.samato.paymentservice.api.WebhookSignatureVerifier
            .verify(webhookSecret, payload, signature);
}
```

We ship `WebhookSignatureVerifier` in the codebase for exactly this reason.

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

If they don't auto-create (depends on broker config), pre-create them via `kafka-topics --create` or via the schema-registry admin.

### 2.5 Verify the gateway's Eureka service id

`lb://SAMATO-PAYMENT-SERVICE` in `GatewayRoutesConfig.java` — Eureka uppercases the registered service name. The payment-service's `spring.application.name` is `samato-payment-service`, so the registered id is `SAMATO-PAYMENT-SERVICE`. Match.

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

1. Install Java 21, Maven 3.9+, Docker Desktop
2. `cd samato` and `mvn -B -DskipTests compile` — fix any errors
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
| `mvn -B -DskipTests compile` succeeds | **Unverified** — expect 5-30 errors on first try |
| `docker compose up -d` brings up the stack | **Unverified** — depends on images and Docker memory |
| Saga runs end-to-end | **Unverified** — code matches the order-service pattern but never executed |
| Razorpay test mode | **Unverified** — keys are placeholders; real flow needs real keys |
| Kafka topics auto-create | Depends on broker config |
| Webhook signature verification | **Unverified** — code present but not exercised |
| Time-travel queries | **Unverified** — endpoint exists, replay logic present |

**Treat this guide as a starting point, not a guarantee.** When you hit the first compile error, paste it and we'll diagnose. When a service won't start, share `docker logs` and we'll trace it.

---

## 10. Related docs

- `README.md` — project overview
- `ARCHITECTURE.md` — service topology and data flow
- `PROJECT-STATUS.md` — what's done and what's pending
- `services/*/docs/INTERVIEW-NOTES.md` — designer notes per service (the *why*)
- `services/payment-service/docs/INTERVIEW-NOTES.md` — the payment service deep-dive

---

*Built for the Samato bible. Tests pending. Money in test mode. Tread with care; document everything.*
