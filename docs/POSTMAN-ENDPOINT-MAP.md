# Postman Endpoint Map — Samato Microservice Bible

> **Audience:** junior engineer importing this into Postman as a checklist.
> Every example is **copy-pasteable as-is**. All field names are case-sensitive (camelCase).
> Import the matching `samato.postman_environment.json` first; every URL below uses Postman variables.

---

## 0. How the network is laid out (read this first)

There are two ways to hit the platform from Postman:

1. **Through the API gateway** at `{{baseUrl}}` = `http://localhost:8080`
   - Gateway does path rewriting: it strips the first `/api/<svc>` segment and resolves the rest via Eureka.
   - The **only** auth-service endpoint reachable via gateway is `/api/auth/register` and `/api/auth/me` — because gateway strips `/api` to `/auth` and then forwards `/auth/register` etc.
   - For login you **must** go direct to auth-service: `{{directAuthUrl}}/oauth2/token` (the standard Spring Authorization Server endpoint, not a custom `/api/auth/login`).

2. **Direct to a service** at `{{directAuthUrl}}`, `{{directUserUrl}}`, `{{directRestaurantUrl}}`, `{{directOrderUrl}}`, `{{directPaymentUrl}}`, `{{directSearchUrl}}` (one per service).
   - Useful when you want to skip the gateway (e.g. debugging) and when calling the OAuth2 token endpoint.

### Gateway path-rewriting gotcha (read carefully)

The gateway uses `stripPrefix(1)` on every `/api/<svc>/**` route, so:
- `/api/auth/register` → arrives at auth-service as `/auth/register` → **404**
- `/api/users/me` → arrives at user-service as `/users/me` → **404**
- `/api/orders` → arrives at order-service as `/orders` → **404**

What works through the gateway today:
- `/api/auth/register` → strips to `/auth/register` → ❌ auth-service controller is at `/api/auth/register`
- `/api/users/me` → strips to `/users/me` → ❌ user-service controller is at `/api/users/me`
- Same for orders, restaurants, payments.

In practice, for Postman you should call the services **directly** (use the `direct*Url` variables). The gateway is useful for `/actuator/health` probes and as a sanity check that the rewrite config matches the controller mappings.

> **TL;DR — for this walkthrough, hit every service on its direct port, not through the gateway.** All examples below use the `direct*Url` variables.

### Auth basics

- All `Authorization: Bearer <jwt>` headers are JWT access tokens (RS256, 15-minute lifetime) issued by auth-service.
- Roles in the JWT come from the `roles` claim as `["CUSTOMER"]`, `["RESTAURANT_OWNER"]`, `["DRIVER"]`, `["ADMIN"]` — Spring maps them to `ROLE_CUSTOMER`, `ROLE_RESTAURANT_OWNER`, etc.
- Token format: opaque to Postman. Just paste the value of `access_token` from the login response into the `jwt` Postman variable.

---

## 1. Happy-path checklist (10 steps)

| # | What you're doing | Service | Where to look in this doc |
|---|---|---|---|
| 1 | Register a CUSTOMER | auth-service | §3.1 |
| 2 | Log in as that customer → get JWT | auth-service | §3.2 |
| 3 | Create a restaurant (RESTAURANT_OWNER) | auth-service + restaurant-service | §3.4 (promote owner), §5.1 (create restaurant) |
| 4 | Add menu items to that restaurant | restaurant-service | §5.4 |
| 5 | Log in as the customer again (or reuse token) | auth-service | §3.2 |
| 6 | List restaurants / search | restaurant-service / search-service | §5.3, §6.1 |
| 7 | Place an order | order-service | §7.1 |
| 8 | Poll the order (watch status) | order-service | §7.2 |
| 9 | Inspect the payment view | payment-service | §8.2 |
| 10 | Inspect saga steps | order-service | §7.4 |

---

## 2. Roles cheat sheet

Defined in `services/auth-service/src/main/java/com/samato/authservice/domain/Role.java`:

| Role constant (exact) | Where to get a user with this role |
|---|---|
| `CUSTOMER` | `POST /api/auth/register` (public endpoint — every new signup is CUSTOMER) |
| `RESTAURANT_OWNER` | `POST /api/auth/register` first, then `UPDATE` the role in the auth DB — see §3.4 |
| `DRIVER` | `POST /api/auth/register` first, then `UPDATE` the role in the auth DB — see §3.4 |
| `ADMIN` | Seeder creates `dave@example.com` automatically on every auth-service start (see §3.3) |

> The public `/api/auth/register` endpoint always assigns `CUSTOMER`. There is **no admin endpoint to promote users** in this codebase. The dev seeder creates one user per role; everything else is a direct DB update.

---

## 3. auth-service (port 9000)

Base URL: `{{directAuthUrl}}` = `http://localhost:9000`
OAuth2 client credentials (used for login): `client_id=api-gateway`, `client_secret=gateway-secret-please-rotate`

### 3.1 `POST /api/auth/register` — register a new CUSTOMER

- **Auth:** none
- **Happy-path step:** 1
- **Request body (JSON):**
  ```json
  {
    "email": "alice2@example.com",
    "password": "Password123!"
  }
  ```
- **Request schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `email` | string | yes | Must be a valid email, unique in `auth` DB |
  | `password` | string | yes | 8–100 chars |
- **Response 201 (Created):**
  ```json
  { "id": "<uuid>", "email": "alice2@example.com" }
  ```
  - The user is assigned the `CUSTOMER` role automatically.
- **Most likely errors:**
  - `409 EMAIL_TAKEN` if the email already exists.

### 3.2 `POST /oauth2/token` — log in and get a JWT

> This is the **only** login endpoint. It is the standard Spring Authorization Server token endpoint. The body is form-urlencoded, not JSON.
> **Do not** call `/api/auth/login` — that path does not exist in the code (the older docs are wrong).

- **Auth:** HTTP Basic with the OAuth2 client (`api-gateway` / `gateway-secret-please-rotate`)
- **Happy-path step:** 2
- **Headers:**
  - `Content-Type: application/x-www-form-urlencoded`
  - `Authorization: Basic YXBpLWdhdGV3YXk6Z2F0ZXdheS1zZWNyZXQtcGxlYXNlLXJvdGF0ZQ==` (base64 of `api-gateway:gateway-secret-please-rotate`)
- **Request body (form fields, not JSON):**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `grant_type` | string | yes | `password` (or `refresh_token` to renew) |
  | `username` | string | yes | The email from registration |
  | `password` | string | yes | The password from registration |
  | `scope` | string | optional | e.g. `openid profile` |
- **Raw body example (paste into Postman's "x-www-form-urlencoded" tab):**
  ```
  grant_type=password
  &username=alice2@example.com
  &password=Password123!
  &scope=openid profile
  ```
- **Response 200 (OK):**
  ```json
  {
    "access_token": "<jwt>",
    "refresh_token": "<jwt>",
    "token_type": "Bearer",
    "expires_in": 900,
    "scope": "openid profile"
  }
  ```
- **Postman tip:** the response field you'll need to copy into the next call's `Authorization: Bearer` header is `access_token`. Save it to the `jwt` Postman variable.
- **Most likely errors:**
  - `401` if the email/password pair is wrong, or if the client credentials are wrong.

### 3.3 Seeded users (dev profile)

`DevDataSeeder` runs on every auth-service start (when `SPRING_PROFILES_ACTIVE` is `dev` or unset). All use password `password123`:

| Email | Role | UUID |
|---|---|---|
| `alice@example.com` | CUSTOMER | `11111111-1111-1111-1111-111111111111` |
| `bob@example.com` | RESTAURANT_OWNER | `22222222-2222-2222-2222-222222222222` |
| `carol@example.com` | DRIVER | `33333333-3333-3333-3333-333333333333` |
| `dave@example.com` | ADMIN | `44444444-4444-4444-4444-444444444444` |

If the seeder has already run and you want a fresh CUSTOMER, use `POST /api/auth/register` with a new email — you'll get a CUSTOMER back.

### 3.4 Promoting a user to RESTAURANT_OWNER (or DRIVER) — [VERIFY]

The codebase has no admin endpoint that promotes a user. The DevDataSeeder only seeds **one** user per role. For additional RESTAURANT_OWNER / DRIVER users, the practical paths are:

#### 3.4.1 Use one of the seeded accounts (recommended for the walkthrough)

Just log in as `bob@example.com` (RESTAURANT_OWNER) or `carol@example.com` (DRIVER) — both have password `password123`. Skip to §5.

#### 3.4.2 Direct DB update (if you really need a third owner)

Run this against the `auth` database:
```sql
INSERT INTO user_roles (user_id, role)
VALUES ('<uuid-from-register>', 'RESTAURANT_OWNER');
```
Then re-login so the new `roles` claim ends up in the JWT.

#### 3.4.3 ADMIN role

`dave@example.com` is seeded as `ADMIN` (see §3.3). Log in as him to test admin-only paths (e.g. `GET /api/users/{id}`, `POST /api/payments/{id}/refunds`).

### 3.5 `GET /api/auth/me` — who am I?

- **Auth:** JWT bearer
- **Happy-path step:** optional sanity check
- **Headers:** `Authorization: Bearer {{jwt}}`
- **Response 200 (OK):**
  ```json
  {
    "id": "<uuid>",
    "email": "alice2@example.com",
    "roles": ["CUSTOMER"]
  }
  ```
- **Postman tip:** this re-reads from the `auth` DB, so it shows the **current** roles — useful after a DB promotion (§3.4.2) to confirm the new role is there.
- **Most likely errors:**
  - `404` if the JWT subject no longer exists in the DB.

### 3.6 `GET /.well-known/jwks.json` — public signing keys (no auth)

- **Auth:** none
- **Response:** standard JWKS JSON. Postman won't use this; the gateway fetches it.

### 3.7 `GET /.well-known/openid-configuration` — OIDC discovery (no auth)

- **Auth:** none
- **Response:** standard OIDC metadata. Postman won't use this either.

### 3.8 `GET /api/auth/debug/token` — inspect a JWT (dev only)

- **Auth:** JWT bearer
- **Response 200:** JSON dump of the parsed token's `sub`, `iss`, `aud`, `exp`, `user_id`, `email`, `roles`. Handy when you're unsure what your token actually says.

---

## 4. user-service (port 8081)

Base URL: `{{directUserUrl}}` = `http://localhost:8081`
All endpoints require `Authorization: Bearer {{jwt}}`. Gateway injects `X-User-Id` and `X-User-Roles` headers but Postman hitting the service directly can skip them — the service re-validates the JWT.

### 4.1 Customer profile

#### 4.1.1 `GET /api/users/me` — read my customer profile

- **Auth:** JWT bearer
- **Roles:** any authenticated user
- **Side effect on first call:** creates a skeleton profile with `displayName` defaulted to the email's local part.
- **Response 200:**
  ```json
  {
    "userId": "<uuid>",
    "displayName": "alice2",
    "phone": null,
    "photoUrl": null
  }
  ```
  > Exact field names depend on the `CustomerProfile` entity. [VERIFY] — the entity lives at `services/user-service/src/main/java/com/samato/userservice/domain/CustomerProfile.java`.

#### 4.1.2 `PUT /api/users/me` — update my customer profile

- **Auth:** JWT bearer
- **Roles:** any authenticated user
- **Request body (JSON):**
  ```json
  {
    "displayName": "Alice",
    "phone": "+1-555-0100",
    "photoUrl": "https://example.com/avatar.png"
  }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `displayName` | string | no | 1–100 chars |
  | `phone` | string | no | up to 30 chars |
  | `photoUrl` | string | no | up to 500 chars |
- **Response 200:** the updated `CustomerProfile` JSON.

#### 4.1.3 `GET /api/users/{id}` — read any user's profile

- **Auth:** JWT bearer
- **Roles:** `ADMIN`, or the user themselves (`authentication.name == #id.toString()`)
- **Response 200:** the `CustomerProfile`.
- **Most likely error:** `403` if you ask for another user's profile without being ADMIN.

### 4.2 Driver profile

#### 4.2.1 `GET /api/users/drivers/me` — read my driver profile

- **Auth:** JWT bearer
- **Roles:** `DRIVER`
- **Response 200:** `DriverProfile` JSON. Creates a skeleton on first call (`vehicleType` defaults to `BIKE`).

#### 4.2.2 `PUT /api/users/drivers/me` — update my driver profile

- **Auth:** JWT bearer
- **Roles:** `DRIVER`
- **Request body (JSON):**
  ```json
  {
    "fullName": "Carol Driver",
    "vehicleType": "BIKE",
    "licensePlate": "ABC-123",
    "onDuty": true
  }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `fullName` | string | no | |
  | `vehicleType` | string | no | e.g. `BIKE`, `SCOOTER`, `CAR` |
  | `licensePlate` | string | no | |
  | `onDuty` | boolean | no | |
- **Response 200:** updated `DriverProfile`.

#### 4.2.3 `PUT /api/users/drivers/me/location` — push my current location

- **Auth:** JWT bearer
- **Roles:** `DRIVER`
- **Request body (JSON):**
  ```json
  {
    "latitude": 12.97,
    "longitude": 77.59
  }
  ```
- **Response 200:** updated `DriverProfile`.

#### 4.2.4 `GET /api/users/drivers/on-duty` — list on-duty drivers

- **Auth:** JWT bearer
- **Roles:** `DRIVER` or `ADMIN`
- **Response 200:** array of `DriverProfile`.

### 4.3 Restaurant-owner profile

#### 4.3.1 `GET /api/users/restaurant-owners/me` — read my owner profile

- **Auth:** JWT bearer
- **Roles:** `RESTAURANT_OWNER`
- **Response 200:** `RestaurantOwnerProfile` JSON.

#### 4.3.2 `PUT /api/users/restaurant-owners/me` — update my owner profile

- **Auth:** JWT bearer
- **Roles:** `RESTAURANT_OWNER`
- **Request body (JSON):**
  ```json
  {
    "businessName": "Bob's Brick Oven",
    "contactEmail": "bob@example.com",
    "contactPhone": "+1-555-0200",
    "taxId": "TAX-12345"
  }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `businessName` | string | yes | `@NotBlank` |
  | `contactEmail` | string | no | must be valid email |
  | `contactPhone` | string | no | |
  | `taxId` | string | no | |
- **Response 200:** updated `RestaurantOwnerProfile`.

---

## 5. restaurant-service (port 8082)

Base URL: `{{directRestaurantUrl}}` = `http://localhost:8082`

### 5.1 `POST /api/restaurants` — create a restaurant

- **Auth:** JWT bearer
- **Roles:** `RESTAURANT_OWNER`
- **Happy-path step:** 3
- **Request body (JSON):**
  ```json
  {
    "name": "Bob's Brick Oven",
    "description": "Wood-fired pizza and pasta",
    "cuisine": "Italian",
    "address": "123 Main St",
    "city": "Bangalore",
    "latitude": 12.97,
    "longitude": 77.59
  }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `name` | string | yes | 1–200 chars |
  | `description` | string | no | up to 2000 chars |
  | `cuisine` | string | yes | 1–50 chars |
  | `address` | string | yes | 1–500 chars |
  | `city` | string | yes | 1–100 chars |
  | `latitude` | double | no | |
  | `longitude` | double | no | |
- **Response 201 (Created):** the `Restaurant` JSON.
  - **Postman tip:** copy `id` (the restaurant UUID) into the Postman variable `restaurantId` — you'll need it for §5.3, §5.4, and §7.1.
  - The `ownerId` is taken from the JWT subject automatically; you don't send it.
- **Most likely error:** `403` if your token is not `RESTAURANT_OWNER`.

### 5.2 `GET /api/restaurants/{id}` — get a single restaurant

- **Auth:** JWT bearer
- **Roles:** any authenticated user
- **Response 200:** the `Restaurant` JSON.

### 5.3 `GET /api/restaurants?city=Bangalore` — list restaurants in a city

- **Auth:** JWT bearer
- **Roles:** any authenticated user
- **Response 200:** array of `Restaurant`.
- **Postman tip:** this is a thin pass-through; the richer search lives in `search-service` (§6).

### 5.4 `POST /api/restaurants/{id}/menu` — add a menu item

- **Auth:** JWT bearer
- **Roles:** owner of the restaurant or `ADMIN`
- **Happy-path step:** 4
- **Request body (JSON):**
  ```json
  {
    "name": "Margherita Pizza",
    "description": "Tomato, mozzarella, basil",
    "price": 12.50
  }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `name` | string | yes | 1–200 chars |
  | `description` | string | no | up to 1000 chars |
  | `price` | number (BigDecimal) | yes | must be `>= 0.00`; send as a JSON number, e.g. `12.50` |
- **Response 201 (Created):** the `MenuItem` JSON.
  - **Postman tip:** copy `id` into the Postman variable `menuItemId` — you'll need it for §7.1.

### 5.5 `GET /api/restaurants/{id}/menu` — get the menu for a restaurant

- **Auth:** JWT bearer
- **Roles:** any authenticated user
- **Response 200:** array of `MenuItem`.

### 5.6 `PUT /api/restaurants/{id}` — update a restaurant

- **Auth:** JWT bearer
- **Roles:** owner or `ADMIN`
- **Request body (JSON):** all fields optional (PATCH-style; only non-null fields are written):
  ```json
  {
    "name": "Bob's Brick Oven v2",
    "description": "Now with dessert",
    "cuisine": "Italian",
    "address": "123 Main St",
    "city": "Bangalore",
    "latitude": 12.97,
    "longitude": 77.59
  }
  ```

### 5.7 `DELETE /api/restaurants/{id}` — deactivate a restaurant

- **Auth:** JWT bearer
- **Roles:** owner or `ADMIN`
- **Response 204 (No Content).**

---

## 6. search-service (port 8087)

Base URL: `{{directSearchUrl}}` = `http://localhost:8087`

> **Note:** the gateway has a path-rewriting mismatch on the search route (it strips `/api` even though search-service's controller is at `/api/search`). Use the **direct** URL when going through Postman.

### 6.1 `GET /api/search/restaurants` — full-text / facet / geo search

- **Auth:** JWT bearer
- **Roles:** any authenticated user
- **Happy-path step:** 6
- **Query parameters (all optional, all combinable):**
  | Param | Type | Example | What it does |
  |---|---|---|---|
  | `q` | string | `noodles` | Full-text match against `name` + `description` |
  | `cuisine` | string | `Italian` | Exact-match filter |
  | `city` | string | `Bangalore` | Exact-match filter |
  | `lat` | double | `12.97` | Geo: latitude (must pair with `lon` + `radiusKm`) |
  | `lon` | double | `77.59` | Geo: longitude |
  | `radiusKm` | double | `5` | Geo: radius in km |
  | `size` | int | `20` (default) | Max results |
- **Example URL:**
  ```
  {{directSearchUrl}}/api/search/restaurants?q=pizza&cuisine=Italian&city=Bangalore&size=20
  ```
- **Response 200 (OK):**
  ```json
  {
    "total": 1,
    "hits": [
      {
        "id": "<uuid>",
        "name": "Bob's Brick Oven",
        "description": "...",
        "cuisine": "Italian",
        "city": "Bangalore",
        "address": "...",
        "latitude": 12.97,
        "longitude": 77.59,
        "_score": 1.234
      }
    ]
  }
  ```
  - Field names inside `hits[]` are the projection of the OpenSearch document — [VERIFY] exact field names by reading `services/search-service/src/main/java/com/samato/searchservice/projection/RestaurantProjector.java`.

---

## 7. order-service (port 8083)

Base URL: `{{directOrderUrl}}` = `http://localhost:8083`

### 7.1 `POST /api/orders` — place an order

- **Auth:** JWT bearer
- **Roles:** `CUSTOMER`
- **Happy-path step:** 7
- **Headers:**
  - `Content-Type: application/json`
  - `Authorization: Bearer {{jwt}}`
  - `Idempotency-Key: <any-unique-string>` (optional but **strongly recommended** — the saga uses it to dedupe retries)
- **Request body (JSON):**
  ```json
  {
    "restaurantId": "{{restaurantId}}",
    "items": [
      { "menuItemId": "{{menuItemId}}", "quantity": 2 }
    ],
    "notes": "Extra napkins please"
  }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `restaurantId` | UUID (string) | yes | From §5.1 |
  | `items` | array | yes | At least one entry |
  | `items[].menuItemId` | UUID (string) | yes | From §5.4 |
  | `items[].quantity` | int | yes | min `1` |
  | `notes` | string | no | up to 500 chars |
- **Response 201 (Created):** an `OrderResponse` JSON — see §7.2 for shape.
  - **Postman tip:** copy `id` (the order UUID) into the Postman variable `orderId` — you'll need it for §7.2, §7.3, §7.4, §8.2.
  - **Postman tip:** copy `sagaId` into the Postman variable `sagaId` (optional — same as `id` is fine for most use cases).
- **Most likely errors:**
  - `403` if your token is not `CUSTOMER`.
  - `400` if the menu item doesn't belong to the restaurant, or quantity < 1.

### 7.2 `GET /api/orders/{id}` — get an order

- **Auth:** JWT bearer
- **Roles:** `CUSTOMER` (own order) or `ADMIN` (any)
- **Happy-path step:** 8
- **Response 200 (OK):** the `OrderResponse`:
  ```json
  {
    "id": "<uuid>",
    "customerId": "<uuid>",
    "restaurantId": "<uuid>",
    "status": "PLACED",
    "totalAmount": 25.00,
    "currency": "INR",
    "cancellationReason": null,
    "items": [
      {
        "id": "<uuid>",
        "menuItemId": "<uuid>",
        "name": "Margherita Pizza",
        "quantity": 2,
        "unitPrice": 12.50
      }
    ],
    "sagaId": "<uuid>",
    "createdAt": "2026-07-07T10:00:00Z",
    "updatedAt": "2026-07-07T10:00:01Z"
  }
  ```
  - **Status lifecycle:** `PLACED → VALIDATED → RESERVED → CONFIRMED`. (`PAID` is a Phase 6 todo — see `RUN-THE-BIBLE.md` §2.6.)
  - **Postman tip:** poll every 1–2 seconds with this endpoint to watch the saga tick.
- **Most likely error:** `403` if you ask for another customer's order without being ADMIN.

### 7.3 `GET /api/orders` — list my orders

- **Auth:** JWT bearer
- **Roles:** `CUSTOMER`
- **Response 200:** array of `OrderResponse` for the JWT subject only.

### 7.4 `GET /api/orders/{id}/saga` — inspect the saga for an order

- **Auth:** JWT bearer
- **Roles:** `CUSTOMER` (own order) or `ADMIN` (any)
- **Happy-path step:** 10
- **Response 200 (OK):**
  ```json
  {
    "sagaId": "<uuid>",
    "status": "COMPLETED",
    "currentStepIndex": 3,
    "failureReason": "",
    "steps": [
      { "index": 0, "type": "VALIDATE_RESTAURANT", "status": "COMPLETED", "error": "" },
      { "index": 1, "type": "RESERVE_ORDER",       "status": "COMPLETED", "error": "" },
      { "index": 2, "type": "CHARGE_PAYMENT",      "status": "COMPLETED", "error": "" },
      { "index": 3, "type": "CONFIRM_ORDER",       "status": "COMPLETED", "error": "" }
    ]
  }
  ```
  - **Postman tip:** `steps[].type` values: `VALIDATE_RESTAURANT`, `RESERVE_ORDER`, `CHARGE_PAYMENT`, `CONFIRM_ORDER` (final). The exact list and order are defined in `services/order-service/src/main/java/com/samato/orderservice/saga/` — [VERIFY] there if you see new types.

### 7.5 `POST /api/orders/{id}/cancel` — cancel a non-terminal order [NOT IMPLEMENTED]

> The OrderController's Javadoc mentions this endpoint, but **the controller does not actually expose it**. There is no `@PostMapping("/{id}/cancel")` in `OrderController.java`. Treat it as future work.

---

## 8. payment-service (port 8084)

Base URL: `{{directPaymentUrl}}` = `http://localhost:8084`

> Most payment endpoints are intended to be called by the **saga** in `order-service` (Feign client). The Postman walkthrough is for inspection and for forcing a charge manually.

### 8.1 `POST /api/payments/orders` — create a Razorpay order

- **Auth:** JWT bearer (saga passes its service token)
- **Roles:** internal — the order-service saga is the normal caller
- **Headers:**
  - `Content-Type: application/json`
  - `Authorization: Bearer {{jwt}}`
  - `Idempotency-Key: <unique-string>` (REQUIRED — returns 400 if missing or blank)
- **Request body (JSON):**
  ```json
  {
    "orderId": "{{orderId}}",
    "customerId": "<uuid-from-jwt-subject>",
    "amount": 25.00,
    "currency": "INR"
  }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `orderId` | UUID | yes | The order's UUID (from §7.1) |
  | `customerId` | UUID | yes | The customer's UUID (from JWT `sub`) |
  | `amount` | BigDecimal (JSON number) | yes | `>= 0.01` |
  | `currency` | string | yes | 3 uppercase letters, e.g. `INR`, `USD` |
- **Response 201 (Created):** a `PaymentResponse`:
  ```json
  {
    "paymentId": "<uuid>",
    "razorpayOrderId": "order_XXXXXXXXX",
    "razorpayPaymentId": null,
    "orderId": "<uuid>",
    "customerId": "<uuid>",
    "amount": 25.00,
    "currency": "INR",
    "status": "ORDER_CREATED",
    "lastEventSeq": 1,
    "updatedAt": "2026-07-07T10:00:00Z"
  }
  ```
  - **Postman tip:** copy `paymentId` into the Postman variable `paymentId` for §8.3, §8.4, §8.5, §8.6.
  - The `razorpayOrderId` is the receipt Razorpay will echo back in the webhook.
  - **Status enum values:** `ORDER_CREATED`, `CAPTURED`, `REFUNDED`, `FAILED`, `EXPIRED`. The exact set lives in `services/payment-service/src/main/java/com/samato/paymentservice/domain/PaymentStatus.java` — [VERIFY] if you need the canonical list.

### 8.2 `GET /api/payments/by-order/{orderId}` — get the payment for an order

- **Auth:** JWT bearer
- **Roles:** any authenticated user
- **Happy-path step:** 9
- **Response 200:** a `PaymentResponse` (same shape as §8.1).
- **Most likely error:** `404` if the order has no payment yet (e.g. saga hasn't reached the CHARGE step).

### 8.3 `GET /api/payments/{id}` — get a payment by paymentId

- **Auth:** JWT bearer
- **Response 200:** a `PaymentResponse`.

### 8.4 `GET /api/payments/{id}/events` — list the event stream for a payment

- **Auth:** JWT bearer
- **Response 200:**
  ```json
  [
    { "sequenceNumber": 0, "version": 1, "eventType": "RazorpayOrderCreated", "occurredAt": "2026-07-07T10:00:00Z" },
    { "sequenceNumber": 0, "version": 2, "eventType": "PaymentCaptured",      "occurredAt": "2026-07-07T10:00:05Z" }
  ]
  ```
  - The `sequenceNumber` field is currently `0` for every event in the codebase (see `PaymentController.events()` — it hardcodes `0`). [VERIFY] if you rely on it for ordering; use `version` and `occurredAt` instead.
  - For a full event-payload dump (instead of the summary above), query the DB directly:
    ```sql
    SELECT sequence_number, version, event_type, event_data, occurred_at
    FROM events
    WHERE aggregate_id = '<paymentId>'
    ORDER BY sequence_number;
    ```

### 8.5 `GET /api/payments/{id}/balance-at/{version}` — time-travel query

- **Auth:** JWT bearer
- **Response 200:** a `BalanceAtResponse`:
  ```json
  {
    "paymentId": "<uuid>",
    "version": 2,
    "amount": 25.00,
    "currency": "INR"
  }
  ```
  - Useful for showing the state of the payment at any historical version of the event stream.

### 8.6 `POST /api/payments/{id}/refunds` — refund a captured payment

- **Auth:** JWT bearer
- **Roles:** `ADMIN` (intended)
- **Headers:**
  - `Content-Type: application/json`
  - `Authorization: Bearer {{jwtAdmin}}`
  - `Idempotency-Key: <unique-string>`
- **Request body (JSON):**
  ```json
  { "amount": 25.00 }
  ```
- **Schema:**
  | Field | Type | Required | Notes |
  |---|---|---|---|
  | `amount` | BigDecimal (JSON number) | yes | `>= 0.01`; must be `<=` captured amount |
- **Response 200:** the updated `PaymentResponse` (status should now be `REFUNDED`).

### 8.7 `POST /api/payments/webhooks/razorpay` — Razorpay webhook receiver

- **Auth:** HMAC SHA-256 signature, not JWT
- **Headers:**
  - `Content-Type: application/json`
  - `X-Razorpay-Signature: <hex-hmac>`
- **Request body:** raw Razorpay event JSON (untouched — signature is over the exact bytes).
- **Response:**
  - `200 OK` if signature verified and event processed.
  - `401` if signature missing or wrong (Razorpay will retry).
  - `500` if processing threw (Razorpay will retry).
- **Postman tip:** to exercise this in Postman you need to compute `HMAC-SHA256(body, RAZORPAY_WEBHOOK_SECRET)` and put it in `X-Razorpay-Signature`. The Pre-request Script can do this with `CryptoJS.HmacSHA256`. The webhook secret in `application.yml` is a placeholder until you set the real one — see `RUN-THE-BIBLE.md` §6.

---

## 9. Anything I couldn't determine (verified by reading the code)

| Item | Why |
|---|---|
| `/api/orders/{id}/cancel` | Documented in the Javadoc but **not actually implemented** in `OrderController.java`. Treat as future work. |
| `PaymentResponse.status` exact enum values | Code only sets `ORDER_CREATED`, `CAPTURED`, `REFUNDED`, `FAILED`, `EXPIRED`. [VERIFY] the full list in `services/payment-service/src/main/java/com/samato/paymentservice/domain/PaymentStatus.java` if the list grows. |
| `GET /api/payments/{id}/events` `sequenceNumber` field | Currently hardcoded to `0` in `PaymentController.events()`. Rely on `version` + `occurredAt` for ordering. |
| `CustomerProfile` exact response field names | Inferred from controller logic; [VERIFY] in `services/user-service/src/main/java/com/samato/userservice/domain/CustomerProfile.java` if you need the exact JSON shape. |
| `DriverProfile` exact response field names | Same as above; [VERIFY] in `services/user-service/src/main/java/com/samato/userservice/domain/DriverProfile.java`. |
| `RestaurantOwnerProfile` exact response field names | Same as above; [VERIFY] in `services/user-service/src/main/java/com/samato/userservice/domain/RestaurantOwnerProfile.java`. |
| `search-service` hit field names | Inferred from projection. [VERIFY] in `services/search-service/src/main/java/com/samato/searchservice/projection/RestaurantProjector.java`. |
| Order of saga `steps[].type` values | Inferred from the design. [VERIFY] in `services/order-service/src/main/java/com/samato/orderservice/saga/`. |
| Gateway path-rewriting works for most routes | The gateway's `stripPrefix(1)` doesn't match the controllers' `/api/<svc>/...` mappings for **any** `/api/<svc>/**` route except actuator. Confirmed by reading `GatewayRoutesConfig.java` vs each controller's `@RequestMapping`. **Workaround used in this doc: hit services directly on their ports, not via the gateway.** |
| `/api/auth/login` endpoint | Mentioned in `RUN-THE-BIBLE.md` and `api-gateway/docs/INTERVIEW-NOTES.md` but **does not exist in code**. The real login endpoint is the standard `/oauth2/token`. |

---

## 10. Quick reference — the 10-step happy path in Postman order

| # | Tab | Method | URL (use Postman variables) | Save into variable |
|---|---|---|---|---|
| 1 | auth | POST | `{{directAuthUrl}}/api/auth/register` | — |
| 2 | auth | POST | `{{directAuthUrl}}/oauth2/token` | `jwt` (from `access_token`) |
| 3a | auth | POST | `{{directAuthUrl}}/oauth2/token` (as `bob@example.com` / `password123`) | `jwtOwner` |
| 3b | restaurant | POST | `{{directRestaurantUrl}}/api/restaurants` | `restaurantId` |
| 4 | restaurant | POST | `{{directRestaurantUrl}}/api/restaurants/{{restaurantId}}/menu` | `menuItemId` |
| 5 | auth | POST | `{{directAuthUrl}}/oauth2/token` (re-login as customer if needed) | `jwt` |
| 6a | restaurant | GET | `{{directRestaurantUrl}}/api/restaurants?city=Bangalore` | — |
| 6b | search | GET | `{{directSearchUrl}}/api/search/restaurants?q=pizza` | — |
| 7 | order | POST | `{{directOrderUrl}}/api/orders` | `orderId`, `sagaId` |
| 8 | order | GET | `{{directOrderUrl}}/api/orders/{{orderId}}` (poll) | — |
| 9 | payment | GET | `{{directPaymentUrl}}/api/payments/by-order/{{orderId}}` | `paymentId` |
| 10 | order | GET | `{{directOrderUrl}}/api/orders/{{orderId}}/saga` | — |
