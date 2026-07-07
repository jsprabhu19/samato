# Testing the Samato Bible from Postman (Beginner Guide)

> A click-by-click guide for a complete beginner. No Spring Boot, no Kafka,
> no event sourcing. Just Postman buttons in the right order.

---

## Before you start

This section explains three terms in plain English. You don't need to know how
any of this works internally — you just need to know what role each piece
plays in the recipe you're about to follow.

### What "microservice" means here

Samato is split into **9 small web apps** instead of one big app. Each one owns
one job: one handles logins, one handles restaurants, one handles orders, one
handles payments, and so on. They talk to each other over HTTP. You, the user,
talk to the **API gateway** (a single front door), which forwards your request
to the right service.

> **Note for the bible:** in this build, the gateway's URL-rewriting layer
> has a known mismatch (it strips `/api` from the path, but the downstream
> services still expect it). The cleanest workaround is to call each service
> **directly on its own port** for this walkthrough. We use the gateway's
> port (`http://localhost:8080`) for the health check only. See
> [Step 0](#step-0-get-the-project-running) for the exact ports.

### What "JWT" means here

A **JWT** (JSON Web Token) is a long string of text that says
*"I am Alice, and the auth-service signed this."* Once you have one, you send
it as a header on every request:

```
Authorization: Bearer eyJhbGciOiJ...
```

The other services check the signature (using a public key from the
auth-service) and trust the claim. They never need to ask the auth-service
again. If the token is bad, missing, or expired, you get a `401`.

### What "the saga" means here

When you place an order, the system has to do several things in order:
**validate the restaurant → reserve the payment → confirm the order**. That
sequence is the **saga**. Each step is recorded in the database; if a step
fails, the system tries to undo the previous steps. For you, the saga just
means: "after I place an order, I should poll the order's status and watch
it move through `PLACED → VALIDATED → RESERVED → CONFIRMED`."

---

## Step 0: Get the project running

This step has **three parts** and they must be done in order:
(1) build, (2) start the infrastructure, (3) start the 7 Spring Boot apps.

> **Why three parts?** Only **2 of the 9 services** (`order-service` and
> `payment-service`) are wired into `docker-compose.yml`. The other **7
> Spring Boot apps** — config, discovery, api-gateway, auth, user,
> restaurant, search — run as plain JVM processes (either `mvn
> spring-boot:run` or `java -jar` on the built JAR). Docker is only used
> for the infrastructure (postgres, kafka, redis, opensearch, zipkin, etc.).

### Part A: Build the project (one time)

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato
E:\SW\apache-maven-3.9.16\bin\mvn -B -DskipTests package
```

This produces a fat JAR for every service at
`services\<name>\target\*.jar`. First time takes 2-5 minutes (Maven
downloads ~500 MB of dependencies); subsequent runs take 10-20 seconds.

### Part B: Start the infrastructure (Docker)

```powershell
cd E:\Learning\ollama-projects\springboot-app\samato\infra
docker compose up -d
```

This brings up **postgres, kafka, redis, opensearch, schema-registry,
zipkin, prometheus, grafana, kafka-ui, order-service, and
payment-service**. Wait **30-45 seconds** for postgres to be healthy and
Flyway to run.

```powershell
docker compose ps
# Every container should be "Up" or "Up (healthy)"
```

If a container keeps restarting, see [Appendix A](#appendix-a-where-the-docker-logs-live).

### Part C: Start the 7 non-dockerized Spring Boot apps

Each of these needs its own terminal window. Open **7 new PowerShell
windows** and run one command in each. They all take 20-40 seconds to
start (Spring Boot startup + Flyway migrations).

> **Why not just `mvn spring-boot:run` for everything?** It works, but
> starting each service in its own terminal keeps logs separated and
> makes it obvious which one crashed. The built JARs from Part A start
> faster than `mvn spring-boot:run` (no Maven overhead).

```powershell
# Terminal 1 — config-service (must start first; others read config from it)
cd E:\Learning\ollama-projects\springboot-app\samato
java -jar services\config-service\target\config-service-*.jar

# Terminal 2 — discovery-service (Eureka; must start before others register)
cd E:\Learning\ollama-projects\springboot-app\samato
java -jar services\discovery-service\target\discovery-service-*.jar

# Wait ~15s for Terminals 1 and 2 to be up, then:

# Terminal 3 — auth-service (port 9000)
java -jar services\auth-service\target\auth-service-*.jar

# Terminal 4 — user-service (port 8081)
java -jar services\user-service\target\user-service-*.jar

# Terminal 5 — restaurant-service (port 8082)
java -jar services\restaurant-service\target\restaurant-service-*.jar

# Terminal 6 — search-service (port 8087)
java -jar services\search-service\target\search-service-*.jar

# Terminal 7 — api-gateway (port 8080)
java -jar services\api-gateway\target\api-gateway-*.jar
```

> **Tip:** if you want to use `mvn spring-boot:run` instead, replace each
> `java -jar` line with `mvn -pl services\<name> -am spring-boot:run`.
> The `-am` flag also builds the upstream `shared` and `shared-kafka`
> modules. JARs are faster; `mvn` is more forgiving if you don't have
> the JAR built.

> **Order matters for startup** if Eureka strict-mode is enabled. The
> safe order is: **config → discovery → auth → everything else**. If a
> service fails to register on first start, wait 30 seconds and restart
> just that one — Eureka will pick it up.

### 90-second health check

Each service exposes its own health URL on its own port. Hit all of them
in a loop. **Only run this after Part C is done.**

```powershell
$ports = 8080, 8888, 8761, 9000, 8081, 8082, 8083, 8084, 8087
foreach ($p in $ports) {
  $r = try { (Invoke-WebRequest -Uri "http://localhost:$p/actuator/health" -UseBasicParsing -TimeoutSec 3).Content }
        catch { "DOWN" }
  Write-Host "Port $p -> $r"
}
```

> **Port 8085 is intentionally NOT in this list** — it belongs to
> `schema-registry` (a Kafka helper, not a Samato service). Port 8081 on
> the host is `schema-registry`'s **container** port, not a Samato
> service. Don't confuse the two.

### Expected output

| Port | Service | How it's running | What you should see |
|---:|---|---|---|
| 8888 | config-service | Terminal 1 (JVM) | `{"status":"UP"}` |
| 8761 | discovery-service (Eureka) | Terminal 2 (JVM) | `{"status":"UP"}` |
| 8080 | api-gateway | Terminal 7 (JVM) | `{"status":"UP"}` |
| 9000 | auth-service | Terminal 3 (JVM) | `{"status":"UP"}` |
| 8081 | user-service | Terminal 4 (JVM) | `{"status":"UP"}` |
| 8082 | restaurant-service | Terminal 5 (JVM) | `{"status":"UP"}` |
| 8083 | order-service | Docker container | `{"status":"UP"}` |
| 8084 | payment-service | Docker container | `{"status":"UP"}` |
| 8087 | search-service | Terminal 6 (JVM) | `{"status":"UP"}` |

If any line says `DOWN` or you see a connection error, wait 15 seconds
and retry. The slowest services (payment, order) take the longest.

If a port is missing entirely (you see a connection error rather than a
`{"status":"DOWN"}` body), the service hasn't started yet. For Docker
services, check `docker compose ps`; for the JVM ones, look at the
terminal where you ran the JAR.

---

## Step 1: Import the Postman environment

The environment file is **`samato.postman_environment.json`**, produced
separately (see Agent A's deliverable). It pre-fills the URLs and the empty
JWT slot for you.

1. Open Postman.
2. Click **File** → **Import**.
3. In the file picker, select `samato.postman_environment.json` (next to
   this walkthrough file in `docs/`).
4. Click **Import**.
5. In the left sidebar, click **Environments**. You should see
   `Samato` listed.
6. Click the `Samato` row to open it.

You should see **15 variables** in the environment. The two that matter for
this walkthrough are:

| Variable | What it is |
|---|---|
| `baseUrl` | The base URL we'll use for almost every call. Defaults to `http://localhost:8080`. (Because of the gateway-strip bug mentioned earlier, the actual `baseUrl` you use per request will vary — see the [Ports cheat sheet at the bottom](#appendix-b-cheat-sheet).) |
| `jwt` | Starts empty. You paste your JWT here after login. |

To use a variable in Postman, type it inside double curly braces:
`{{baseUrl}}/api/...` — Postman fills it in automatically.

---

## Step 2: The happy path (12 numbered steps)

Each step follows the same recipe: open a new tab, pick a method, paste a URL,
pick a body, click **Send**, and check the response.

> **Variable shortcuts used below:**
> `{{jwt}}` = the logged-in user's access token
> `{{jwtOwner}}` = the restaurant owner's access token
> `{{restaurantId}}` = the UUID of the restaurant we create
> `{{menuItemId}}` = the UUID of a menu item we add
> `{{orderId}}` = the UUID of the order we place

---

### Step 1: Register Alice (the customer)

**Goal:** create a new customer account in the auth-service.

- **Method dropdown:** `POST`
- **URL box:** `{{baseUrl_auth}}/api/auth/register`
  - In practice, set this to `http://localhost:9000/api/auth/register` (the
    auth-service directly — see the [Note about the gateway](#what-microservice-means-here)).
- **Headers tab:** add one row
  - Key: `Content-Type`, Value: `application/json`
- **Body tab:** select **raw** and **JSON**. Paste:
  ```json
  {
    "email": "alice@example.com",
    "password": "Password123!",
    "name": "Alice"
  }
  ```
- Click **Send**.

**Expected response:** status `201 Created`, body:
```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "email": "alice@example.com"
}
```

> **What to save:** nothing yet — registration does **not** return a JWT.
> You need to log in (Step 2) to get one.

> **Beginner sanity check:**
> - If you see `409 EMAIL_TAKEN`, the user already exists. Either use a
>   different email (e.g. `alice2@example.com`) or skip ahead to Step 2
>   and just log in.
> - If you see `400` with `email: must be a well-formed email address`,
>   your email is malformed.
> - If you see `400` with `password: size must be between 8 and 100`,
>   your password is too short.

> **Note about the `name` field:** the registration controller currently
> only saves `email` and `password` (it ignores `name`). This is a
> known gap in the bible. Don't worry if your `name` field seems to
> disappear.

---

### Step 2: Login as Alice (save the JWT)

**Goal:** exchange Alice's email and password for a JWT access token.

The login endpoint is the standard Spring Authorization Server
`/oauth2/token` endpoint using the **password grant**. It uses
`application/x-www-form-urlencoded` (not JSON), and requires HTTP Basic
auth for the OAuth client.

- **Method dropdown:** `POST`
- **URL box:** `http://localhost:9000/oauth2/token`
- **Headers tab:** add two rows
  - Key: `Content-Type`, Value: `application/x-www-form-urlencoded`
  - Key: `Authorization`, Value: `Basic YXBpLWdhdGV3YXk6Z2F0ZXdheS1zZWNyZXQtcGxlYXNlLXJvdGF0ZQ==`
    - This is the OAuth client id `api-gateway` and secret
      `gateway-secret-please-rotate`, base64-encoded. (These are the
      dev seed values; safe to use here.)
- **Body tab:** select **x-www-form-urlencoded**. Add four rows:
  - Key: `grant_type`, Value: `password`
  - Key: `username`, Value: `alice@example.com`
  - Key: `password`, Value: `Password123!`
  - Key: `scope`, Value: `openid profile`
- Click **Send**.

**Expected response:** status `200 OK`, body:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOi...",
  "refresh_token": "abc123...",
  "token_type": "Bearer",
  "expires_in": 899,
  "scope": "openid profile"
}
```

> **What to save:** the value of `access_token`. In Postman:
> 1. Click inside the response body, hover over the long string after
>    `"access_token":`.
> 2. Postman shows a `Set as variable` link. Click it.
> 3. Pick `jwt` from the dropdown.
> 4. Click **Set variable**.

> **Beginner sanity check:**
> - If you see `401 Bad credentials`, the email or password is wrong.
> - If you see `401 invalid_client`, the Basic-auth header is wrong. Try
>   generating it fresh: in PowerShell,
>   `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("api-gateway:gateway-secret-please-rotate"))`.
> - If you see `400 unsupported_grant_type`, the auth-service config has
>   the password grant disabled (it shouldn't be — it's enabled by
>   default in `application.yml`).

---

### Step 3: Register a restaurant owner (Bob)

**Goal:** create a user with the `RESTAURANT_OWNER` role so we can create
a restaurant in the next step.

**The catch:** the public `/api/auth/register` endpoint **only creates
`CUSTOMER` users** by design. You cannot register as a `RESTAURANT_OWNER`
through the public API. (This is intentional in the bible — non-customer
roles are assigned by an admin or seeded for dev.)

**The workaround for the bible:** use the **dev-seeded Bob account** that
the auth-service creates automatically on startup:

- Email: `bob@example.com`
- Password: `password123`
- Pre-assigned roles: `RESTAURANT_OWNER`

These values come from `DevDataSeeder.java` in the auth-service. If you
want to create your own owner, you have two options:

1. **Quick path (recommended):** just use the seeded Bob. Skip to Step 4.
2. **Create a new owner manually:** connect to the auth database and
   insert a new row directly. Open a PowerShell terminal and run:
   ```powershell
   docker exec -it samato-postgres psql -U fd -d auth
   ```
   Then, inside the `psql` prompt:
   ```sql
   -- Use an online BCrypt generator to make a hash of your password.
   -- e.g. for "Password123!" you get a hash that starts with $2a$12$...
   INSERT INTO user_accounts (id, email, password_hash, created_at, updated_at, version)
   VALUES (gen_random_uuid(), 'bob2@example.com',
           '$2a$12$<paste your BCrypt hash here>',
           now(), now(), 0);
   INSERT INTO user_account_roles (user_account_id, roles)
   VALUES ((SELECT id FROM user_accounts WHERE email = 'bob2@example.com'), 'RESTAURANT_OWNER');
   \q
   ```
   Replace the password hash with a real BCrypt hash for your password
   (use https://bcrypt-generator.com/ or any equivalent, cost 12).

> **Beginner sanity check:**
> - If you get a "permission denied" error in psql, your container name
>   might be different. Run `docker ps --format '{{.Names}}' | findstr postgres`
>   to find the actual name.
> - If you don't see the seeded Bob in the database, the auth-service
>   may not have started in the `default` profile. Check the **Terminal 3
>   window** (where you started `auth-service`) for the line
>   `Seeded user: bob@example.com`.

---

### Step 4: Login as Bob (save the owner JWT)

**Goal:** get a JWT for Bob with the `RESTAURANT_OWNER` role.

- **Method dropdown:** `POST`
- **URL box:** `http://localhost:9000/oauth2/token`
- **Headers tab:** same as Step 2 (Content-Type `application/x-www-form-urlencoded` and the same `Authorization: Basic ...` header).
- **Body tab (x-www-form-urlencoded):**
  - Key: `grant_type`, Value: `password`
  - Key: `username`, Value: `bob@example.com`
  - Key: `password`, Value: `password123`
  - Key: `scope`, Value: `openid profile`
- Click **Send**.

**Expected response:** status `200 OK`, body similar to Step 2 (a new `access_token`).

> **What to save:** the new `access_token`. In Postman, set it as the
> variable `jwtOwner` (not `jwt` — Alice's token stays in `jwt`).

> **Beginner sanity check:**
> - If you get `401 Bad credentials` and you used `bob@example.com` /
>   `password123`, the seeder didn't run. Check the **Terminal 3 window**
>   (where you started `auth-service`) for the line
>   `Seeded user: bob@example.com`.

---

### Step 5: Create a restaurant (as Bob)

**Goal:** create a new restaurant owned by Bob, so Alice can order from it.

For this and every step that follows, **use the `jwtOwner` token** (set
`Authorization: Bearer {{jwtOwner}}` in the headers).

- **Method dropdown:** `POST`
- **URL box:** `http://localhost:8082/api/restaurants`
- **Headers tab:** add one row
  - Key: `Authorization`, Value: `Bearer {{jwtOwner}}`
  - Key: `Content-Type`, Value: `application/json`
- **Body tab (raw / JSON):**
  ```json
  {
    "name": "Bob's Pizza",
    "description": "Wood-fired pizza in Bangalore",
    "cuisine": "Italian",
    "address": "100 MG Road",
    "city": "Bangalore",
    "latitude": 12.9756,
    "longitude": 77.6053
  }
  ```
- Click **Send**.

**Expected response:** status `201 Created`, body:
```json
{
  "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "name": "Bob's Pizza",
  "ownerId": "22222222-2222-2222-2222-222222222222",
  "active": true,
  ...
}
```

> **What to save:** the `id` field. In Postman, hover over the UUID value
> and click **Set as variable** → `restaurantId`.

> **Beginner sanity check:**
> - If you see `403 Forbidden`, the JWT you're using is Alice's, not Bob's.
>   Make sure the `Authorization` header says `{{jwtOwner}}`.
> - If you see `401 Unauthorized`, the token is missing or expired. Re-do
>   Step 4 to get a fresh one.
> - If you see `400` with a field-name validation error, double-check the
>   field names — they are camelCase (`restaurantName` is **wrong**; `name` is right).

---

### Step 6: Add 2-3 menu items to the restaurant

**Goal:** give the restaurant something to sell.

Repeat this step 2-3 times, changing the `name` and `price` each time,
so you have a few menu items to choose from. Use **the same restaurant
id** from Step 5, and Bob's JWT.

- **Method dropdown:** `POST`
- **URL box:** `http://localhost:8082/api/restaurants/{{restaurantId}}/menu`
- **Headers tab:**
  - Key: `Authorization`, Value: `Bearer {{jwtOwner}}`
  - Key: `Content-Type`, Value: `application/json`
- **Body tab (raw / JSON):**
  ```json
  {
    "name": "Margherita Pizza",
    "description": "Tomato, mozzarella, basil",
    "price": 12.50
  }
  ```
- Click **Send**.

**Expected response:** status `201 Created`, body:
```json
{
  "id": "ffffffff-1111-2222-3333-444444444444",
  "restaurantId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "name": "Margherita Pizza",
  "price": 12.50
}
```

> **What to save:** the `id` of the first menu item you add. In Postman,
> hover over the UUID and **Set as variable** → `menuItemId`.

> **Beginner sanity check:** the response's `restaurantId` should match
> the `{{restaurantId}}` you used in the URL. If it doesn't, you posted
> to the wrong restaurant.

---

### Step 7: Back to Alice — search for the restaurant

**Goal:** confirm Alice can find Bob's restaurant in the search service.

For this step, switch back to **Alice's JWT** (`{{jwt}}`). The search
service is at port `8087`.

- **Method dropdown:** `GET`
- **URL box:** `http://localhost:8087/api/search/restaurants?city=Bangalore&cuisine=Italian`
- **Headers tab:**
  - Key: `Authorization`, Value: `Bearer {{jwt}}`
- Click **Send**.

**Expected response:** status `200 OK`, body:
```json
{
  "total": 1,
  "hits": [
    {
      "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      "name": "Bob's Pizza",
      "cuisine": "Italian",
      "city": "Bangalore",
      "_score": 1.0
    }
  ]
}
```

> **Beginner sanity check:**
> - If you see `total: 0`, the search index may not have the new
>   restaurant yet. The search service projects from restaurant-service
>   to OpenSearch — give it 5-10 seconds and retry. If it's still empty
>   after 30 seconds, check `docker logs samato-search-service --tail 50`.
> - If you see `total: 1` but the `id` doesn't match your `restaurantId`,
>   you may have an old restaurant from a previous run. Add a unique
>   word to the restaurant name (e.g. "Bob's Pizza #2") and re-create.

> **Alternative:** you can also list restaurants by city directly from the
> restaurant-service. Use:
> `GET http://localhost:8082/api/restaurants?city=Bangalore` with Alice's
> JWT. The response is a JSON array of restaurant objects.

---

### Step 8: Place an order (drives the saga)

**Goal:** trigger the saga — the system will validate the restaurant,
create a payment, and move the order toward `CONFIRMED`.

Use **Alice's JWT** again.

- **Method dropdown:** `POST`
- **URL box:** `http://localhost:8083/api/orders`
- **Headers tab:**
  - Key: `Authorization`, Value: `Bearer {{jwt}}`
  - Key: `Content-Type`, Value: `application/json`
  - Key: `Idempotency-Key`, Value: `my-first-order-001`
    - This header prevents the same order from being created twice if
>      you click Send again. Any unique string works.
- **Body tab (raw / JSON):**
  ```json
  {
    "restaurantId": "{{restaurantId}}",
    "items": [
      { "menuItemId": "{{menuItemId}}", "quantity": 2 }
    ]
  }
  ```
- Click **Send**.

**Expected response:** status `201 Created`, body:
```json
{
  "id": "99999999-aaaa-bbbb-cccc-dddddddddddd",
  "customerId": "11111111-1111-1111-1111-111111111111",
  "restaurantId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "status": "PLACED",
  "totalAmount": 25.00,
  "currency": "INR",
  "items": [...],
  "sagaId": "...",
  "createdAt": "2026-07-07T..."
}
```

> **What to save:** the `id` field. In Postman, **Set as variable** → `orderId`.

> **Beginner sanity check:**
> - If you see `404 RESTAURANT_NOT_FOUND`, your `restaurantId` is wrong
>   or the restaurant-service isn't running. Re-check `{{restaurantId}}`.
> - If you see `400` with a `menuItemId` validation error, the menu
>   item UUID is malformed.
> - If you see `401`, your `{{jwt}}` expired. Re-do Step 2.

> **Note about Razorpay:** in this bible, Razorpay is in placeholder mode
> (the keys are `rzp_test_xxxxxxxxxxxxxx`). The payment-service will try
> to call Razorpay and the call will fail. That's OK — the saga is
> designed to handle this; the order will still transition through
> `VALIDATED → RESERVED`, and the payment will sit in `ORDER_CREATED`.
> See Step 9 to watch the saga tick.

---

### Step 9: Poll the order status

**Goal:** watch the saga work. The status moves asynchronously, so poll
a few times.

- **Method dropdown:** `GET`
- **URL box:** `http://localhost:8083/api/orders/{{orderId}}`
- **Headers tab:**
  - Key: `Authorization`, Value: `Bearer {{jwt}}`
- Click **Send**. Wait 2-3 seconds, click **Send** again. Repeat.

**Expected response progression:**

| When you poll | Expected `status` field |
|---|---|
| Right after Step 8 | `PLACED` |
| 1-2 seconds later | `VALIDATED` |
| 3-5 seconds later | `RESERVED` |
| 5-10 seconds later | `CONFIRMED` |

> **Beginner sanity check:**
> - If the status stays at `PLACED` for more than 30 seconds, the saga
>   isn't running. Check `docker logs samato-order-service --tail 100`.
>   Look for a line like `Starting saga ...` and any stack trace.
> - If it goes to `CANCELLED`, a step failed. Scroll down in the same
>   log for the word `compensation` or `error`.

---

### Step 10: Inspect the saga steps

**Goal:** see each step the saga took, with its status and any errors.

- **Method dropdown:** `GET`
- **URL box:** `http://localhost:8083/api/orders/{{orderId}}/saga`
- **Headers tab:**
  - Key: `Authorization`, Value: `Bearer {{jwt}}`
- Click **Send**.

**Expected response:** status `200 OK`, body:
```json
{
  "sagaId": "...",
  "status": "COMPLETED",
  "currentStepIndex": 4,
  "failureReason": "",
  "steps": [
    { "index": 0, "type": "VALIDATE_RESTAURANT", "status": "COMPLETED", "error": "" },
    { "index": 1, "type": "RESERVE_PAYMENT",     "status": "COMPLETED", "error": "" },
    { "index": 2, "type": "CONFIRM_ORDER",       "status": "COMPLETED", "error": "" }
  ]
}
```

> **Beginner sanity check:**
> - The outer `status` should be `COMPLETED` (or `COMPENSATING` / `FAILED`
>   if something went wrong).
> - Every step in the `steps` array should have `status: "COMPLETED"`.
>   A step with `status: "FAILED"` has the reason in its `error` field.

---

### Step 11: Inspect the payment view in postgres

**Goal:** confirm the payment was created in the database.

The payment data lives in the `payment_service` database, on the postgres
container (`samato-postgres`). We use `psql` to query it.

> **What to look for:** at least one row whose `razorpay_order_id` is
> populated. The status will be `ORDER_CREATED` (because the Razorpay API
> call couldn't complete — placeholder keys).

- **Method:** open a PowerShell terminal and run:
  ```powershell
  docker exec -it samato-postgres psql -U fd -d payment_service -c \
    "SELECT payment_id, razorpay_order_id, status, amount, currency, order_id FROM payment_view ORDER BY last_event_seq DESC LIMIT 5;"
  ```
- When prompted for a password, type `fd` and press Enter.

**Expected output (1-2 rows):**
```
             payment_id              | razorpay_order_id |    status     | amount | currency |             order_id
--------------------------------------+-------------------+---------------+--------+----------+------------------------------------
 8a1d2c5e-7b34-4e91-9a01-1a2b3c4d5e6f | order_Pplaceholder| ORDER_CREATED |  25.00 | INR      | 99999999-aaaa-bbbb-cccc-dddddddddddd
```

> **Beginner sanity check:**
> - The `order_id` column should equal your `{{orderId}}`.
> - If you see 0 rows, the payment-service never recorded the payment.
>   Check `docker logs samato-payment-service --tail 100` for the words
>   `projector` and `RazorpayOrderCreated`.

---

### Step 12: Inspect the event store in postgres

**Goal:** see the immutable log of events for the payment — this is the
**event-sourced** part of the bible. Every state change is a row in the
`events` table.

- **Method:** run this in PowerShell:
  ```powershell
  docker exec -it samato-postgres psql -U fd -d payment_service -c \
    "SELECT sequence_number, event_type, version, event_data->>'razorpayOrderId' AS rzp_order FROM events ORDER BY sequence_number DESC LIMIT 10;"
  ```

**Expected output (at least 1 row):**
```
 sequence_number |     event_type        | version |      rzp_order
-----------------+-----------------------+---------+---------------------
              12 | RazorpayOrderCreated  |       1 | order_Pplaceholder
              11 | PaymentCreated        |       0 |
              10 | PaymentInitialized    |       0 |
```

> **Beginner sanity check:**
> - The most recent `event_type` for a fresh order should be
>   `RazorpayOrderCreated`. If you also see `PaymentFailed` after that,
>   that's because the placeholder Razorpay keys caused the call to fail —
>   which is expected in this build.
> - If you see 0 rows, the payment-service never appended any events.
>   The order-service saga would also be stuck in `RESERVED`. Check
>   `docker logs samato-payment-service --tail 100`.

---

## Step 3: Common error responses and what they mean

When something goes wrong, Postman will show you an HTTP status code.
Here's what they mean in this app:

| Status | What it usually means in Samato | First thing to check |
|---:|---|---|
| `400` | Validation failed (bad email, short password, missing field, etc.) | The response body — it lists which field is wrong. |
| `401` | JWT missing, malformed, or expired | Re-run Step 2 to get a fresh `{{jwt}}`. Check the `Authorization` header. |
| `403` | Role mismatch (e.g. CUSTOMER trying to create a restaurant) | Make sure you're using the right token: `{{jwt}}` for Alice, `{{jwtOwner}}` for Bob. |
| `404` | Resource not found (UUID typo, parent resource doesn't exist) | Double-check the UUID in the URL matches the one you saved. |
| `409` | Conflict — email already taken, etc. | Use a different email, or log in with the existing one. |
| `422` | Validation failed (semantic, e.g. bad enum value) | Check that enum fields (currency, status) use the right value. |
| `500` | Server-side bug | Check the relevant log — see [Appendix A](#appendix-a-where-the-logs-live) for where each service's log lives (Docker container vs. terminal window). |
| `503` | Service is starting up, not ready yet | Wait 10 seconds and click Send again. |

---

## Step 4: Verifying it actually worked

A "you should see this" checklist. Go through each item:

- [ ] I have a JWT in the `{{jwt}}` variable (Step 2).
- [ ] I have a JWT in the `{{jwtOwner}}` variable (Step 4).
- [ ] I have a `restaurantId` from Step 5.
- [ ] I have at least one `menuItemId` from Step 6.
- [ ] I have an `orderId` from Step 8.
- [ ] The order status moved through `PLACED → VALIDATED → RESERVED → CONFIRMED` (Step 9).
- [ ] The saga endpoint (Step 10) returns `status: "COMPLETED"` and every step is `COMPLETED`.
- [ ] The `payment_view` table has at least one row for my `orderId` (Step 11).
- [ ] The `events` table has at least one `RazorpayOrderCreated` row (Step 12).

If you can tick every box, the bible is end-to-end working for you. Time to
celebrate — and then go read `INTERVIEW-CHEATSHEET.md` for the Q&A.

---

## Appendix A: Where the logs live

When something goes wrong, the first thing to do is read the relevant
service's log. **Most services run as JVM processes in their own terminal
windows** (not in Docker), so there are two commands depending on what
fails.

| Where the service runs | How to see its log |
|---|---|
| Docker container | `docker logs samato-<service> --tail 100` |
| JVM (one of your 7 terminals from Step 0 Part C) | Just look at the terminal window where you started the JAR. |

**Per-service log location:**

| Service | How it runs | Where to look |
|---|---|---|
| api-gateway | Terminal 7 (JVM) | Terminal 7 window |
| config-service | Terminal 1 (JVM) | Terminal 1 window |
| discovery-service | Terminal 2 (JVM) | Terminal 2 window |
| auth-service | Terminal 3 (JVM) | Terminal 3 window |
| user-service | Terminal 4 (JVM) | Terminal 4 window |
| restaurant-service | Terminal 5 (JVM) | Terminal 5 window |
| search-service | Terminal 6 (JVM) | Terminal 6 window |
| order-service | Docker | `docker logs samato-order-service --tail 100` |
| payment-service | Docker | `docker logs samato-payment-service --tail 100` |
| postgres | Docker | `docker logs samato-postgres --tail 100` |
| kafka | Docker | `docker logs samato-kafka --tail 100` |
| opensearch | Docker | `docker logs samato-opensearch --tail 100` |

**Symptom → service → what to look for:**

| Symptom | Service to check | What to look for |
|---|---|---|
| Can't log in, OAuth errors | auth-service (Terminal 3) | `Seeded user: alice@example.com` should appear once at startup. Then look for `Authentication failed` or stack traces. |
| 404 on `/api/...` through the gateway | api-gateway (Terminal 7) | "no matching route" or "404 Not Found" — confirms the gateway-strip bug; call the service directly. |
| Restaurant creation returns 403 | auth-service (Terminal 3) | Check that the JWT has the `roles` claim containing `RESTAURANT_OWNER`. |
| Order stays in `PLACED` for >30s | order-service (Docker) | `docker logs samato-order-service --tail 100`. Look for `Starting saga` and the `Current step` log lines. |
| Payment `ORDER_CREATED` but no `RazorpayOrderCreated` event | payment-service (Docker) | `docker logs samato-payment-service --tail 100`. Look for `projector` and `RazorpayOrderCreated` — a deserialisation mismatch will be named there. |
| No events in `events` table | payment-service (Docker) | `docker logs samato-payment-service --tail 100`. Look for `DataIntegrityViolationException` (version conflict) or `OptimisticLockException`. |
| Search returns 0 results | search-service (Terminal 6) | Terminal 6 window. Look for `indexing` and any OpenSearch errors. Also check `docker logs samato-opensearch --tail 50`. |
| Connection refused to a JVM service | (whichever service) | The terminal where it was started — if it crashed, the stack trace is right there. |
| Connection refused to a Docker service | `docker compose ps` | Check which container is in `Restarting` state and look at its log. |
| Postgres errors (FOREIGN KEY, etc.) | `samato-postgres` | Run `docker exec -it samato-postgres psql -U fd -d <dbname> -c "SELECT * FROM flyway_schema_history;"` to verify migrations ran. |

> **Useful one-liner for the saga:** to follow order + payment logs live
> as you click Send in Postman, open a second terminal and run:
> ```powershell
> docker logs -f samato-order-service
> # in another terminal:
> docker logs -f samato-payment-service
> ```
> Then click **Send** in Postman. You'll see the saga ticks in real time.

---

## Appendix B: Cheat sheet

Every endpoint hit in this walkthrough, in order, for interview revision.

| # | Method | URL (port) | Auth | Notes |
|---:|---|---|---|---|
| 1 | POST | `http://localhost:9000/api/auth/register` | none | Alice's account. JSON body. |
| 2 | POST | `http://localhost:9000/oauth2/token` | Basic (`api-gateway:gateway-secret-please-rotate`) | Password grant for Alice. Form body. Saves `{{jwt}}`. |
| 3 | — | (Use seeded Bob — no API call.) | — | `bob@example.com` / `password123`. |
| 4 | POST | `http://localhost:9000/oauth2/token` | Basic (same) | Password grant for Bob. Saves `{{jwtOwner}}`. |
| 5 | POST | `http://localhost:8082/api/restaurants` | `Bearer {{jwtOwner}}` | Creates the restaurant. Saves `{{restaurantId}}`. |
| 6 | POST | `http://localhost:8082/api/restaurants/{{restaurantId}}/menu` | `Bearer {{jwtOwner}}` | Adds a menu item. Repeat 2-3 times. Saves `{{menuItemId}}`. |
| 7 | GET | `http://localhost:8087/api/search/restaurants?city=Bangalore&cuisine=Italian` | `Bearer {{jwt}}` | Confirms the search index. |
| 8 | POST | `http://localhost:8083/api/orders` | `Bearer {{jwt}}` | Places the order. Triggers the saga. Saves `{{orderId}}`. |
| 9 | GET | `http://localhost:8083/api/orders/{{orderId}}` | `Bearer {{jwt}}` | Poll to watch status. |
| 10 | GET | `http://localhost:8083/api/orders/{{orderId}}/saga` | `Bearer {{jwt}}` | Inspect each saga step. |
| 11 | SQL | `payment_view` in `samato-postgres` | n/a | `docker exec -it samato-postgres psql -U fd -d payment_service -c "..."` |
| 12 | SQL | `events` in `samato-postgres` | n/a | Same as above. |

> **Postgres credentials (dev):** user `fd`, password `fd`. Database name
> matches the service: `auth`, `user_service`, `restaurant_service`,
> `order_service`, `payment_service`, `search_service`.

> **Port summary (verified from `services/*/src/main/resources/application.yml`):**

| Port | Service | How it runs | What it's for |
|---:|---|---|---|
| 8080 | api-gateway | JVM (Terminal 7) | Routes — but routing is currently broken in this build; call services directly. |
| 8888 | config-service | JVM (Terminal 1) | Centralized config; must be first. |
| 8761 | discovery-service (Eureka) | JVM (Terminal 2) | Service registry. |
| 9000 | auth-service | JVM (Terminal 3) | Registration, OAuth2 token, JWKS. |
| 8081 | user-service | JVM (Terminal 4) | Customer / owner / driver profiles. |
| 8082 | restaurant-service | JVM (Terminal 5) | Restaurant + menu CRUD. |
| 8083 | order-service | Docker container | Order placement + saga. |
| 8084 | payment-service | Docker container | Event-sourced payment ledger. |
| 8087 | search-service | JVM (Terminal 6) | OpenSearch query for restaurants. |
| 9200 | opensearch | Docker container | Search engine (used by search-service). |
| 8085 | schema-registry | Docker container | **NOT a Samato service** — it's a Kafka helper. Port 8085 maps to container 8081, so don't confuse it with anything. |
| 5432 | postgres | Docker container | All service databases. |
| 9092 | kafka | Docker container | Event bus. |
| 6379 | redis | Docker container | Cache / locks. |
| 9411 | zipkin | Docker container | Distributed tracing UI (optional). |
| 3000 | grafana | Docker container | Dashboards (admin / admin, optional). |
| 8081 (kafka-ui) | kafka-ui | Docker container | Kafka topic browser (optional). |
