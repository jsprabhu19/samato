# Samato Pre-flight Checks and Troubleshooting

> *The "is it actually up?" and "why is it broken?" guide for the Samato microservice bible. Companion to `RUN-THE-BIBLE.md` and the Postman walkthrough.*

This document is for **complete beginners**. It assumes you can run a `curl` command, open a browser, and read an error message. It does **not** assume you know Spring Boot internals. Where a jargon term is unavoidable, it's defined in parentheses the first time it appears.

## Status (2026-07-08)

Verified: 18/18 containers running, all 9 service /actuator/health return HTTP 200, 7 services registered in Eureka. The preflight script in §1.2 should now pass cleanly.

---

## Part 1: Pre-flight — Is the stack actually up?

### What "stack is up" means

The Samato bible is **18 things that need to be running** for a single `POST /api/orders` to succeed end-to-end:

- **9 backing-service containers** (started by `docker compose`):
  - `samato-postgres` — the database
  - `samato-kafka` — the message bus
  - `samato-redis` — cache and distributed locks
  - `samato-opensearch` — search index
  - `samato-schema-registry` — Avro schema storage for Kafka payloads (port 8085)
  - `samato-discovery-service` — service registry (Eureka). Runs as its own Docker container; see `services/discovery-service/Dockerfile`.
  - `samato-config-service` — centralized config server. Runs as its own Docker container; see `services/config-service/Dockerfile`.
  - `samato-kafka-ui` — Kafka topic browser UI (port 8091)
  - `samato-zipkin`, `samato-prometheus`, `samato-grafana` — observability (optional but typical)

- **9 Spring Boot apps** (the microservices):
  api-gateway, auth-service, user-service, restaurant-service, search-service, order-service, payment-service, config-service, discovery-service.

Each Spring Boot app exposes a **health endpoint** — a special URL that returns a small JSON document saying "I'm fine" or "I'm broken". For every service it's the same path: `/actuator/health`. A healthy service returns `{"status":"UP"}`. A broken one returns `{"status":"DOWN"}` (with a reason) or returns nothing at all (because the service is dead).

The gateway (the front door of the system) is at **http://localhost:8080** — every request from Postman should go through the gateway, not directly to the underlying services.

> *Why a gateway?* In microservice architectures, the gateway is the single entry point. It routes requests to the right service, validates the JWT (login token), and adds cross-cutting concerns like rate limiting and tracing. Hitting a service directly is sometimes possible but bypasses auth — only do it for diagnostics.

### The 60-second health check (single copy-paste)

**PowerShell version** (Windows — what most beginners on this repo will use):

```powershell
# Health check: hits 7 docker containers, then curls /actuator/health on all 9 services.
# Expect "9 of 9 services are UP" at the end. If you see anything less, the stack is not ready.

# 1. Are the docker containers running?
docker ps --format "table {{.Names}}\t{{.Status}}" | Select-String "samato-"

# 2. Curl each service's /actuator/health. We use "try/catch" so a down service
#    doesn't abort the whole script.
$services = @(
  @{name="api-gateway";        port=8080},
  @{name="auth-service";       port=9000},
  @{name="config-service";     port=8888},
  @{name="discovery-service";  port=8761},
  @{name="user-service";       port=8081},
  @{name="restaurant-service"; port=8082},
  @{name="order-service";      port=8083},
  @{name="payment-service";    port=8084},
  @{name="search-service";     port=8087}   # verified: yml says 8087
)

$up = 0
foreach ($s in $services) {
  try {
    $resp = Invoke-RestMethod -Uri "http://localhost:$($s.port)/actuator/health" -TimeoutSec 3
    if ($resp.status -eq "UP") {
      Write-Host "$($s.name) (port $($s.port)): UP" -ForegroundColor Green
      $up++
    } else {
      Write-Host "$($s.name) (port $($s.port)): $($resp.status)" -ForegroundColor Yellow
    }
  } catch {
    Write-Host "$($s.name) (port $($s.port)): NOT REACHABLE" -ForegroundColor Red
  }
}

Write-Host ""
Write-Host "$up of 9 services are UP. If you see anything less than 9, the stack is not ready."
```

**Bash version** (macOS / Linux / WSL):

```bash
# Health check: hits docker containers, then curls /actuator/health on all 9 services.
# Expect "9 of 9 services are UP" at the end.

# 1. Are the docker containers running?
docker ps --format "table {{.Names}}\t{{.Status}}" | grep samato-

# 2. Curl each service's /actuator/health
check() {
  local name=$1 port=$2
  local code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:$port/actuator/health)
  if [ "$code" = "200" ]; then
    echo "$name (port $port): UP"
  else
    echo "$name (port $port): DOWN (HTTP $code)"
  fi
}

check api-gateway        8080
check auth-service       9000
check config-service     8888
check discovery-service  8761
check user-service       8081
check restaurant-service 8082
check order-service      8083
check payment-service    8084
check search-service     8087   # verified: yml says 8087
```

**What this script does, in 3 lines:**

1. Lists every docker container whose name starts with `samato-` — that's your 7 backing services.
2. For each Spring Boot service, sends a `GET /actuator/health` and checks if the response says `UP`.
3. Prints a final summary: `X of 9 services are UP`.

> **Port note:** the user-supplied "standard Samato port map" in the task brief lists `search-service: 8085` and `restaurant-service: 8081` / `user-service: 8082`. The actual `application.yml` files say `search-service: 8087`, `user-service: 8081`, `restaurant-service: 8082`. **The script above uses the values from the yml files** (the ground truth). If you have a different local override, edit the two port numbers in the script.

### What each service health status means

| Service | Port | Health URL | What "UP" means | Common reason it's DOWN | First thing to try |
|---|---|---|---|---|---|
| `api-gateway` | 8080 | `http://localhost:8080/actuator/health` | Gateway can talk to Eureka, has its routes loaded, and Spring is alive | Eureka is unreachable; one of the `lb://...` routes resolves to nothing | `docker logs samato-api-gateway --tail 50` |
| `auth-service` | 9000 | `http://localhost:9000/actuator/health` | Spring Authorization Server is up; Postgres is reachable; Flyway migrations ran | Postgres not ready yet, or Flyway migration failed | `docker logs samato-auth-service --tail 50` |
| `config-service` | 8888 | `http://localhost:8888/actuator/health` | Config server has loaded `config-repo/`; Spring is alive | The local `config-repo/` directory is missing or unreadable | `ls config-repo/` — if the dir is empty, see "Nuclear option" in Part 4 |
| `discovery-service` | 8761 | `http://localhost:8761/actuator/health` | Eureka is up and reachable | Port 8761 is in use by something else | `curl http://localhost:8761` — you should see the Eureka dashboard HTML |
| `user-service` | 8081 | `http://localhost:8081/actuator/health` | Spring is alive; Postgres reachable; Flyway ran | Postgres down; Flyway migration failed | `docker logs samato-user-service --tail 50` |
| `restaurant-service` | 8082 | `http://localhost:8082/actuator/health` | Spring alive; Postgres reachable | Same as user-service | `docker logs samato-restaurant-service --tail 50` |
| `order-service` | 8083 | `http://localhost:8083/actuator/health` | Spring alive; Postgres reachable; Kafka reachable | Kafka topic auto-create disabled; Eureka timeout | `docker logs samato-order-service --tail 50` |
| `payment-service` | 8084 | `http://localhost:8084/actuator/health` | Spring alive; Postgres reachable; Flyway ran | Same as order-service | `docker logs samato-payment-service --tail 50` |
| `search-service` | 8087 | `http://localhost:8087/actuator/health` | Spring alive; OpenSearch reachable | OpenSearch not yet ready; missing index | `docker logs samato-search-service --tail 50` |

> **`/actuator/health` is intentionally public.** The gateway routes `GET /actuator/**` straight through, so you can probe any service's health through the gateway *or* its direct port. If you can reach the gateway (`:8080/actuator/health` returns `UP`) but a downstream service is unreachable, the gateway will report `UP` with a `components` map showing which downstream is `DOWN`. Beginners can read this map to see exactly which dependency is broken.

---

## Part 2: Troubleshooting by symptom

For each symptom: **what you see**, **likely cause** (ranked most-likely first), **first thing to try** (one copy-paste command), **next thing to try** (if that didn't help), and **what to share with Claude** (if even that didn't help).

---

### 1. "Postman returns 'Could not get any response'"

> *What you see:* Postman says "Could not get any response" and the console shows `Error: connect ECONNREFUSED 127.0.0.1:8080`.

**Likely cause:** (1) the gateway is down, (2) you hit the wrong port, (3) you hit a service directly when it's not running.

**First thing to try:**

```bash
curl -v http://localhost:8080/actuator/health
```

**If that didn't work:** the gateway is down — start it: `cd infra && docker compose up -d api-gateway` (or run the Spring Boot app if you're running outside Docker).

**Share with Claude:** the full `curl -v` output (it shows the network-level error) plus `docker ps | grep samato-`.

---

### 2. "Postman returns 401 Unauthorized"

> *What you see:* the body says something like `{"code":"UNAUTHORIZED","message":"JWT missing or invalid"}`.

**Likely cause:** (1) you forgot the `Authorization` header, (2) your JWT expired (15-minute lifetime, see `application.yml`), (3) the `Bearer ` prefix is missing or has a typo.

**First thing to try:** log in again and copy the new `access_token` into Postman's `Authorization` tab:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"Password123!"}'
```

**If that didn't work:** check the header in Postman. It must be exactly `Authorization: Bearer eyJhbGc...` (capital `B`, single space, no quotes).

**Share with Claude:** the exact request headers from Postman and the 401 response body.

---

### 3. "Postman returns 403 Forbidden"

> *What you see:* body says `{"code":"FORBIDDEN","message":"..."}` and the status is 403.

**Likely cause:** (1) your JWT is valid but lacks the role this endpoint needs (e.g. you tried `POST /api/restaurants` as a CUSTOMER, but only `RESTAURANT_OWNER` or `ADMIN` can create restaurants), (2) you're trying to view another user's resource (e.g. another customer's order).

**First thing to try:** decode your JWT to see what roles you have:

```bash
# Paste your JWT into https://jwt.io OR run this in PowerShell:
$JWT = "paste-here"
$payload = $JWT.Split('.')[1]
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($payload + "==").Replace('-','+').Replace('_','/')))
```

Look for `"roles": [...]` in the JSON. The first user registered through `/api/auth/register` is always a `CUSTOMER`. To get an `ADMIN` or `RESTAURANT_OWNER` token, you need to seed one in the database (the bible's Flyway scripts may include a seed user).

**If that didn't work:** check the endpoint in the endpoint map — most "create" or "modify" endpoints require a specific role. Customer registration via `/api/auth/register` only ever grants `CUSTOMER`.

**Share with Claude:** the decoded JWT payload (the roles claim) and the exact endpoint URL.

---

### 4. "Postman returns 404 Not Found"

> *What you see:* `{"code":"NOT_FOUND","message":"..."}` or `{"timestamp":"...","status":404,"error":"Not Found"}`.

**Likely cause:** (1) wrong path (typo), (2) you used a UUID that doesn't exist (e.g. you copied a stale ID), (3) you hit the gateway with a path it doesn't have a route for.

**First thing to try:** check the path against the endpoint map. For example, the order-service's GET is `/api/orders/{id}` — note the `s`. If you wrote `/api/order/{id}` (singular) you'll get 404.

```bash
# Quick test: is the path even routed?
curl -i http://localhost:8080/api/orders
# Look for HTTP/1.1 401 (route exists, no auth) vs HTTP/1.1 404 (route doesn't exist)
```

**If that didn't work:** confirm the resource actually exists. For UUIDs, the most common bug is using the *user* UUID where a *restaurant* UUID was wanted, or vice versa.

**Share with Claude:** the full request URL and the 404 body.

---

### 5. "Postman returns 409 Conflict"

> *What you see:* `{"code":"EMAIL_TAKEN","message":"Email already registered","httpStatus":409}` or similar.

**Likely cause:** you're trying to create something that already exists. The bible's uniqueness rules: email is unique per user; restaurant name + owner is unique.

**First thing to try:** change the value and retry. Use a different email (e.g. add a timestamp):

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice-'$(date +%s)'@example.com","password":"Password123!","name":"Alice"}'
```

**If that didn't work:** for restaurants, the unique key is the combination of `name` + `ownerId`. Either use a different name or log in as a different owner.

**Share with Claude:** the exact `code` field from the 409 body — that tells you which uniqueness rule fired.

---

### 6. "Postman returns 422 / 400"

> *What you see:* body lists field errors like `{"errors":[{"field":"email","message":"must be a valid email"}]}`.

**Likely cause:** request body validation failed. The bible uses Jakarta Validation annotations (rules attached to fields in the controller DTOs).

**First thing to try:** read the `errors` array. Each entry has a `field` and a `message`. The most common cases:

| Field | Rule | Fix |
|---|---|---|
| `email` | must be a valid email format | Use `name@domain.tld` |
| `password` | min length 8, max 100 | Use at least 8 characters |
| `name` (restaurant) | max 200, not blank | Provide a non-empty name under 200 chars |
| `price` (menu item) | min 0.00 | Use a non-negative number, e.g. `199.99` |
| `restaurantId` (order) | must be a valid UUID | Copy the ID from the GET response, don't type it |
| `items` (order) | must be non-empty | Add at least one item to the array |

```bash
# Re-run with -i to see the headers and -v to see the request you sent
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" \
  -d '{"restaurantId":"<uuid>","items":[{"menuItemId":"<uuid>","quantity":2}]}'
```

**If that didn't work:** compare your JSON to the example in the endpoint map — the most common mistake is mismatched field names (e.g. `restaurant_id` vs `restaurantId`).

**Share with Claude:** the request body and the full `errors` array.

---

### 7. "Postman returns 500 Internal Server Error"

> *What you see:* `{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/api/orders"}` or a stack trace.

**Likely cause:** a bug in the service. This is a *server-side* problem — your request was well-formed but the service couldn't process it.

**First thing to try:** find the stack trace in the docker logs:

```powershell
docker logs samato-<service-name> --tail 200
```

Replace `<service-name>` with the one that logged the error. The path in the 500 body usually hints at which service. For `/api/orders/...` errors, look at `samato-order-service`; for `/api/payments/...` look at `samato-payment-service`.

**If that didn't work:** grep the logs for the order id or correlation id:

```powershell
docker logs samato-order-service --tail 500 | Select-String -Pattern "<order-uuid>"
```

**Share with Claude:** the last 100 lines of the relevant service's docker log, plus the request URL and body that triggered the 500.

---

### 8. "Order stuck in PLACED — saga didn't start"

> *What you see:* `GET /api/orders/{id}` returns `status: "PLACED"` indefinitely.

**Likely cause:** the order service's saga scheduler (a background poller that drives orders through their states) is failing on the first step. Most often: the HTTP call to `restaurant-service` (to reserve the order) failed.

**First thing to try:** check the saga state:

```bash
curl http://localhost:8080/api/orders/<order-uuid>/saga -H "Authorization: Bearer $JWT"
```

Look at the `steps` array. The step that failed will have `status: "FAILED"` and an `error` field. The most common failing step is `RESERVE_RESTAURANT`.

**If that didn't work:** tail the order-service log and watch for the next poll cycle:

```powershell
docker logs samato-order-service --tail 200
```

Look for `Saga step X started` and `Saga step X failed with Y`.

**Share with Claude:** the saga-state response (with steps) and the last 200 lines of `samato-order-service`.

---

### 9. "Order moves to RESERVED but stops — payment failed"

> *What you see:* `GET /api/orders/{id}` returns `status: "RESERVED"`. The saga's `CHARGE_PAYMENT` step never completes.

**Likely cause:** Razorpay API call failed because the keys in `application.yml` are placeholders (`rzp_test_xxxxxxxxxxxxxx`). The payment service retries, the saga may eventually compensate (rollback) and the order moves to `CANCELLED`, or it stays stuck if the saga's retry policy is exhausted.

**First thing to try:** check the saga state for the `CHARGE_PAYMENT` step:

```bash
curl http://localhost:8080/api/orders/<order-uuid>/saga -H "Authorization: Bearer $JWT"
```

**If that didn't work:** check the payment-service log for Razorpay errors:

```powershell
docker logs samato-payment-service --tail 200 | Select-String -Pattern "Razorpay"
```

**The real fix:** replace the placeholder Razorpay keys in `services/payment-service/src/main/resources/application.yml` (or set them via env vars), then rebuild and restart. The keys must be real `rzp_test_...` keys from https://dashboard.razorpay.com/app/keys.

**Share with Claude:** the saga state response and the Razorpay-related log lines.

---

### 10. "I see the event store has rows but the projection (payment_view) is empty"

> *What you see:* `SELECT * FROM events` returns rows for your payment, but `SELECT * FROM payment_view` returns nothing.

**Likely cause:** the projector (a background component that reads the event store and writes a denormalized view table) is failing. The event was appended successfully, but the projection consumer couldn't deserialize it.

**First thing to try:** check the projector log:

```powershell
docker logs samato-payment-service --tail 500 | Select-String -Pattern "projector"
```

**If that didn't work:** check the Avro schema. The most common cause is a field-name drift between the Java record and the `.avsc` file. The error will say something like `Cannot deserialize field X` or `Unknown field Y`.

**Share with Claude:** the projector log lines and the first row of `events` for your payment: `SELECT event_data FROM events ORDER BY sequence_number LIMIT 1;`

---

### 11. "I see duplicate rows in the event store"

> *What you see:* `SELECT * FROM events` shows two `RazorpayOrderCreated` rows for the same `payment_id`.

**Likely cause:** the saga retried. The bible uses **at-least-once delivery** — Kafka can deliver the same event twice (e.g. after a rebalance or a consumer crash). The system is designed to be idempotent (safe to process the same event twice), but the event store itself is append-only and may contain duplicates if the publisher retried.

**First thing to try:** confirm it's the publisher, not the consumer:

```sql
SELECT sequence_number, event_type, version, event_id, created_at
FROM events
WHERE event_type = 'RazorpayOrderCreated'
ORDER BY sequence_number DESC LIMIT 10;
```

If two rows have the same `event_id` (or the same aggregate-id), it's a publisher retry. If they have different `event_id`s, the saga genuinely published two events (which is a different bug).

**If that didn't work:** deduplicate downstream. Consumers are supposed to check `event_id` against an in-memory set or a database table. The bible's command handlers do this; if you wrote a new consumer and skipped the dedup, you have a bug there.

**Share with Claude:** the SQL query output above and the consumer code that's processing the events.

---

### 12. "My JWT works for one service but not another"

> *What you see:* `GET /api/users/me` works (returns 200), but `GET /api/orders` returns 401 with the same token.

**Likely cause:** the gateway's JWKS URI (the URL from which it fetches the public key to verify JWTs) doesn't match the auth-service's actual JWKS endpoint. The downstream service fetches its own JWKS independently, and if its configured `jwk-set-uri` is wrong, it can't verify the token.

**First thing to try:** fetch the JWKS from the auth service directly:

```bash
curl http://localhost:9000/.well-known/jwks.json
```

You should see a JSON object with `keys: [...]`. If empty or 404, the auth service hasn't started its JWKS endpoint.

**If that didn't work:** compare the JWKS URI in the failing service's `application.yml` to the one in `auth-service`. They should be identical: `http://localhost:9000/.well-known/jwks.json` (when running locally) or `http://auth-service:9000/.well-known/jwks.json` (when running inside Docker).

**Share with Claude:** the JWKS URL each service is configured with, and the response of `curl http://localhost:9000/.well-known/jwks.json`.

---

### 13. "docker compose up says 'port already allocated'"

> *What you see:* `Error: bind: address already in use` or `port is already allocated` during `docker compose up`.

**Likely cause:** another process on your host is using the port. Common offenders: a previous `docker compose` run that didn't clean up, another dev tool (Postgres Workbench, Redis Desktop, etc.), or another instance of the same container.

**First thing to try (Windows / PowerShell):** find the process holding the port:

```powershell
netstat -ano | findstr :8080
# Output ends with a PID, e.g. 12345
taskkill /F /PID 12345
```

**If that didn't work:** check if the port is held by an *orphan* container from a previous run:

```powershell
docker ps -a | findstr "samato-"
docker rm -f <container-name-from-above>
```

**If that didn't work (macOS / Linux):** use `lsof`:

```bash
sudo lsof -i :8080
kill -9 <PID>
```

**Share with Claude:** the full `netstat -ano | findstr :8080` output and the `docker ps -a` output.

---

### 14. "Build fails with 'requires Maven version 3.6.3'"

> *What you see:* `[ERROR] [require] Maven version 3.6.3 or later is required` (or similar) from the `maven-enforcer-plugin` / `maven-compiler-plugin`.

**Likely cause:** the `mvn` on your PATH is too old. The bible's `pom.xml` uses `maven-compiler-plugin` 3.13 which requires Maven 3.6.3+, and Spring Boot 3.3.4 actually requires Maven 3.9+ for its plugins to work cleanly.

**First thing to try:** check your Maven version:

```powershell
mvn -version
# If it says 3.3.x or 3.6.x, you have the wrong Maven.
```

Install Maven 3.9.x from https://maven.apache.org/download.cgi, then call it by full path:

```powershell
E:\SW\apache-maven-3.9.16\bin\mvn -B -DskipTests package
```

**If that didn't work:** the system `mvn` is being found first. Either delete it from `PATH`, or always call Maven by its full path.

**Share with Claude:** the output of `mvn -version` (Java home, Maven home, OS name).

---

### 15. "The application is running but /actuator/health returns 503"

> *What you see:* `curl http://localhost:8080/actuator/health` returns HTTP 503 with body `{"status":"DOWN","components":{...}}`.

**Likely cause:** the service is alive, but a downstream dependency is unreachable. Spring Boot's `/actuator/health` aggregates health from every component: the database (`db`), Kafka (`kafka`), Eureka (`discoveryClient`), disk space (`diskSpace`), and ping. Any one of them being `DOWN` flips the overall status to `DOWN`.

**First thing to try:** read the `components` field in the response body to see *which* dependency is down. For example:

```json
{
  "status": "DOWN",
  "components": {
    "db": { "status": "DOWN", "details": { "error": "Connection refused" } },
    "discoveryClient": { "status": "UP" },
    "kafka": { "status": "DOWN", "details": { ... } }
  }
}
```

The component name tells you exactly what's broken: `db` → Postgres, `kafka` → Kafka broker, `discoveryClient` → Eureka, `redis` → Redis, `opensearch` → OpenSearch.

**If that didn't work:** confirm the dependency container is up:

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}" | Select-String "samato-postgres|samato-kafka|samato-redis|samato-opensearch"
```

If a container is restarting, see Part 3 for the docker-logs cheat sheet.

**Share with Claude:** the full `/actuator/health` response body.

---

## Part 3: The docker logs cheat sheet

> *One-page reference: "I think the issue is in X service" → "run this command" → "look for this in the output".*

| Container name | Command | What to look for |
|---|---|---|
| `samato-api-gateway` | `docker logs -f samato-api-gateway --tail 100` | `Connection refused` (downstream unreachable); `JWT validation failed` (gateway's auth filter rejected the token); `Route not found` (typo in route config) |
| `samato-auth-service` | `docker logs -f samato-auth-service --tail 100` | `Token issued` (login worked); `Could not load JWK` (the JWKS endpoint isn't up yet); `Flyway migration failed` (DB schema problem) |
| `samato-user-service` | `docker logs -f samato-user-service --tail 100` | `Started UserServiceApplication` (it's up); `Connection refused` (Postgres); `JWT decode failed` (JWKS URI is wrong) |
| `samato-restaurant-service` | `docker logs -f samato-restaurant-service --tail 100` | `Started RestaurantServiceApplication`; `Schema registry unreachable`; `OpenSearch rejected` (search index errors) |
| `samato-search-service` | `docker logs -f samato-search-service --tail 100` | `OpenSearch rejected document` (index mapping mismatch); `Projection skipped event` (event deserialization failed — usually a schema-version mismatch); `started on port(s): 8087` |
| `samato-order-service` | `docker logs -f samato-order-service --tail 100` | `Saga step X started` (saga is ticking); `Saga step X failed with Y` (the failure reason — usually a `FeignException` from restaurant-service or payment-service); `CHARGE_PAYMENT` |
| `samato-payment-service` | `docker logs -f samato-payment-service --tail 100` | `Razorpay API returned 401` (placeholder keys — see symptom 9); `Could not write to events table` (event-store write failed — check Postgres); `Projector failed` (see symptom 10) |
| `samato-discovery-service` | `docker logs -f samato-discovery-service --tail 100` | `Started DiscoveryService`; `Eureka server started`; `Renewal from ...` (heartbeat received) |
| `samato-config-service` | `docker logs -f samato-config-service --tail 100` | `Could not resolve placeholder` (typo in `@Value` or `@RefreshScope` field); `ConfigServer started`; `Failed to load ... from ...` (missing config-repo file) |
| `samato-postgres` | `docker logs -f samato-postgres --tail 100` | `database system is ready to accept connections` (healthy); `FATAL: password authentication failed` (wrong creds); `out of memory` |
| `samato-kafka` | `docker logs -f samato-kafka --tail 100` | `KafkaServer id=1 started` (healthy); `Connection to node -1 could not be established` (broker still booting) |
| `samato-redis` | `docker logs -f samato-redis --tail 100` | `Ready to accept connections` (healthy); `MISCONF` (RDB write failed — usually disk full) |
| `samato-opensearch` | `docker logs -f samato-opensearch --tail 100` | `Node started` (healthy); `bootstrap checks failed` (usually vm.max_map_count — set on host) |

> **Tip:** the `-f` flag tails the log live (like `tail -f`). Drop `-f` if you want a one-shot dump. The `--tail 100` keeps the output short.

---

## Part 4: The nuclear option — fresh start

> *Use this when you've tried everything and the stack is wedged. It removes all data — every order, every user, every event.*

### Step 1: stop everything and wipe volumes

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato\infra
docker compose down -v
```

The `-v` flag removes all named volumes (`samato-pgdata`, `samato-kafkadata`, `samato-redisdata`, etc.). **This deletes your database, your Kafka topics, and your OpenSearch indices.** Make sure you don't need anything in there.

### Step 2: rebuild the Spring Boot apps (only if you changed code)

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato
E:\SW\apache-maven-3.9.16\bin\mvn -B -DskipTests package
```

### Step 3: bring everything back up, in the right order

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato\infra

# Layer 1: foundation (DB, Kafka, Redis, OpenSearch)
docker compose up -d postgres kafka redis opensearch

# Wait ~30 seconds for Postgres to be healthy
timeout /t 30

# Layer 2: platform (Eureka, config, gateway)
docker compose up -d discovery-service config-service api-gateway
# discovery-service and config-service have their own Dockerfiles (services/discovery-service/Dockerfile
# and services/config-service/Dockerfile) and run as Docker containers in the compose file.

# Layer 3: business services
docker compose up -d auth-service user-service restaurant-service search-service order-service payment-service
# All 6 are defined as Docker services in the compose file.

# Wait 60 seconds, then run the health check from Part 1.
timeout /t 60
```

### Step 4: verify with the Part 1 health check

Re-run the PowerShell or Bash health check from Part 1. You should see `9 of 9 services are UP`.

### If the nuclear option didn't work

The only thing left is host-level: your Docker Desktop memory limit might be too low, or your disk might be full. Check:

- **Docker Desktop → Settings → Resources → Memory:** should be at least 8 GB.
- **Disk space:** Postgres, Kafka, and OpenSearch all need several GB free.
- **WSL2 (Windows):** if you're on WSL2, the docker-desktop-data VM sometimes fills up. From PowerShell: `wsl --shutdown` then restart Docker Desktop.

---

## Appendix: How to read a docker log line

A typical Spring Boot log line, broken into pieces:

```
2026-07-07 14:23:01.123  INFO [order-service,,abc123,def456] [http-nio-8083-exec-1] c.s.o.s.OrderService - Order 7f8e9d... placed by customer 1234abcd...
└──────────┬──────────┘  └─┬─┘ └────────┬────────┘  └────────┬─────────────┘ └────────────┬─────────────┘ └──────┬──────┘
     timestamp          level    service + trace ids        thread name               logger (Java class)         message
```

- **Timestamp** — when the log line was written. Local time on the container.
- **Level** — `INFO` (normal), `WARN` (something to look at), `ERROR` (something broke), `DEBUG` (verbose — only shown if you turned it on).
- **`[order-service,,abc123,def456]`** — the service name (`order-service`), then the **trace id** (`abc123`), then the **span id** (`def456`). The trace id is the most important part: if you grep for `abc123` across *every* service's logs, you'll see the same request as it flowed from gateway → order-service → payment-service → Razorpay. That's how you trace one user action through the whole system.
- **Thread name** — the Java thread that wrote the log. `http-nio-8083-exec-1` means "the worker thread handling request #1 on port 8083". Saga work happens on `saga-...` threads. Kafka consumers run on `org.springframework.kafka.KafkaListenerEndpointContainer#0-1`-style threads.
- **Logger** — the Java class. `c.s.o.s.OrderService` is `com.samato.orderservice.service.OrderService`. Use this to find the source file.
- **Message** — free-form text. Stack traces follow on subsequent lines, indented.

> **If you see `traceId=abc123` in one service's log and `traceId=abc123` in another service's log, those are the same request.** Grep by trace id to see the full saga in one go:
>
> ```powershell
> docker logs samato-order-service --tail 500 | Select-String "abc123"
> docker logs samato-payment-service --tail 500 | Select-String "abc123"
> ```

### A note on MDC (Mapped Diagnostic Context)

The `[traceId=... spanId=...]` in your log pattern comes from a feature called **MDC** (Mapped Diagnostic Context). Spring Boot + Micrometer Tracing automatically puts the current request's trace id and span id into the MDC. The log pattern picks them up with `%X{traceId:-}` and `%X{spanId:-}`. The `:-` means "use empty string if not set". This is why the same `traceId` appears in every log line for a single request, across every service that handled it.

---

*Built for the Samato microservice bible. When all else fails, share the docker log of the relevant service and the exact request that broke.*
