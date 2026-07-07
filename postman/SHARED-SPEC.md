# Postman Collection — Shared Spec (READ FIRST)

This is the **single source of truth** for the Postman collection. All three agents
(D, E, F) MUST read this file before writing their part. If a request shape here
disagrees with what you read in the controller, **this spec wins** — it was
authored after reading the actual controllers.

## Variable names (must match the env file exactly)

| Variable | Source | Read by |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | gateway health check only |
| `directAuthUrl` | `http://localhost:9000` | login, jwks, register (register also works through gateway) |
| `directUserUrl` | `http://localhost:8081` | user-service profile endpoints |
| `directRestaurantUrl` | `http://localhost:8082` | restaurant + menu |
| `directOrderUrl` | `http://localhost:8083` | orders + saga |
| `directPaymentUrl` | `http://localhost:8084` | payments |
| `directSearchUrl` | `http://localhost:8087` | search |
| `oauthClientId` | `api-gateway` | OAuth2 Basic auth |
| `oauthClientSecret` | `gateway-secret-please-rotate` | OAuth2 Basic auth |
| `customerEmail` | `alice@example.com` | register/login |
| `customerPassword` | `Password123!` | register/login |
| `ownerEmail` | `bob@example.com` | register/login |
| `ownerPassword` | `Password123!` | register/login |
| `jwt` | (empty, set by login) | bearer for CUSTOMER calls |
| `jwtOwner` | (empty, set by login) | bearer for RESTAURANT_OWNER calls |
| `customerId` | (empty, set by register/login) | PlaceOrderRequest.customerId |
| `ownerId` | (empty, set by register/login) | used in restaurant create |
| `restaurantId` | (empty, set by create restaurant) | menu add, order place, get |
| `menuItemId` | (empty, set by menu add) | order place |
| `orderId` | (empty, set by order place) | get, saga, payment-by-order |
| `idempotencyKey` | (empty, set per-request) | Idempotency-Key header on POSTs |

**If you need a new variable, ADD IT TO THIS TABLE** and use the new name in your
requests. Do not invent names that aren't here.

## Collection-level scripts (everyone appends to the same draft)

### Collection-level pre-request script (paste into the collection's `event[0].script.exec`)

```javascript
// Runs before EVERY request in the collection.
// Two jobs: (1) make every bearer header fresh, (2) generate a fresh
// idempotency key for any POST that needs it.

const bearer = pm.variables.get('jwt');
const bearerOwner = pm.variables.get('jwtOwner');

if (bearer) {
    pm.request.headers.upsert({ key: 'Authorization', value: 'Bearer ' + bearer });
}
if (pm.request.url && pm.request.url.path && pm.request.url.path.join('/').includes('restaurants') && bearerOwner) {
    pm.request.headers.upsert({ key: 'Authorization', value: 'Bearer ' + bearerOwner });
}

// Per-run idempotency key
if (pm.request.method === 'POST') {
    pm.request.headers.upsert({ key: 'Idempotency-Key', value: pm.variables.replaceIn('{{$randomUUID}}') });
}
```

### Collection-level test script (paste into the collection's `event[1].script.exec`)

```javascript
// Empty for now. Per-request tests do the real work.
```

## Tests-script patterns

**Pattern A — capture a JWT after a login response:**

```javascript
const json = pm.response.json();
if (json.access_token) {
    pm.collectionVariables.set('jwt', json.access_token);
    console.log('Captured CUSTOMER JWT');
}
```

**Pattern B — capture a UUID from a response:**

```javascript
const json = pm.response.json();
if (json.id) {
    pm.collectionVariables.set('orderId', json.id);
    console.log('Captured orderId = ' + json.id);
}
```

**Pattern C — assert expected status:**

```javascript
pm.test('status is 2xx', () => {
    pm.expect(pm.response.code).to.be.oneOf([200, 201, 204]);
});
```

**Pattern D — capture from a token response with token_type:**

```javascript
const json = pm.response.json();
if (json.access_token) {
    // We have TWO logins: customer and owner. Detect which by request URL.
    const url = pm.request.url.toString();
    if (url.includes('{{customerEmail}}') || url.includes('alice')) {
        pm.collectionVariables.set('jwt', json.access_token);
    } else {
        pm.collectionVariables.set('jwtOwner', json.access_token);
    }
}
```

## Request bodies (use these EXACTLY — sourced from the controller source)

### 1. Register customer
- Method: POST
- URL: `{{directAuthUrl}}/api/auth/register`
- Body: `{"email": "{{customerEmail}}", "password": "{{customerPassword}}"}`
- Response: `{"id": "<uuid>", "email": "..."}`
- Test: capture `id` into `customerId`

### 2. Register owner
- Same shape. Response.id → `ownerId`
- NOTE: public registration always sets the CUSTOMER role. To make Bob an
  owner, the seeder has to assign RESTAURANT_OWNER. Document this in the
  collection's README; the postman collection itself includes a "seed
  owner role" request that runs an admin SQL or hits a dev-only endpoint.
  Since neither is in code, the cleanest approach is: a "Manual step"
  pre-request script that prints a copy-pasteable psql command for the
  user to run, and the test asserts the user is then able to hit a
  RESTAURANT_OWNER endpoint. If the user is still CUSTOMER, the test
  fails with a helpful message.

### 3. Login (OAuth2 password grant)
- Method: POST
- URL: `{{directAuthUrl}}/oauth2/token`
- Auth: Basic — username = `{{oauthClientId}}`, password = `{{oauthClientSecret}}`
  (Postman: Authorization tab → Type: Basic Auth → fill in)
- Headers: `Content-Type: application/x-www-form-urlencoded`
- Body (x-www-form-urlencoded):
  - `username` = `{{customerEmail}}`
  - `password` = `{{customerPassword}}`
  - `grant_type` = `password`
  - `scope` = (leave blank; or `openid profile` if you want a full OIDC response)
- Response: `{"access_token": "...", "token_type": "Bearer", "expires_in": 900, "scope": "..."}`
- Test: capture `access_token` into `jwt` (customer) or `jwtOwner` (owner)

### 4. Create restaurant
- Method: POST
- URL: `{{directRestaurantUrl}}/api/restaurants`
- Auth: Bearer `{{jwtOwner}}`
- Body:
  ```json
  {
    "name": "Biryani Blues",
    "description": "Authentic Hyderabadi biryani",
    "cuisine": "Indian",
    "address": "12 MG Road",
    "city": "Bangalore",
    "latitude": 12.9716,
    "longitude": 77.5946
  }
  ```
- Response: full restaurant object including `id`
- Test: capture `id` into `restaurantId`

### 5. Add menu item
- Method: POST
- URL: `{{directRestaurantUrl}}/api/restaurants/{{restaurantId}}/menu`
- Auth: Bearer `{{jwtOwner}}`
- Body:
  ```json
  {
    "name": "Chicken Biryani",
    "description": "Slow-cooked with saffron",
    "price": "350.00"
  }
  ```
  (price is a string because BigDecimal serializes that way; no quotes in the JSON
  of the body string is the JSON, the value MUST be quoted)
- Response: menu item with `id`
- Test: capture `id` into `menuItemId`

### 6. Search restaurants
- Method: GET
- URL: `{{directSearchUrl}}/api/search/restaurants?city=Bangalore`
- Auth: Bearer `{{jwt}}` (CUSTOMER)
- Response: `{"total": N, "hits": [{...}, ...]}`
- No env capture needed; informational.

### 7. Place order
- Method: POST
- URL: `{{directOrderUrl}}/api/orders`
- Auth: Bearer `{{jwt}}` (CUSTOMER)
- Headers: `Idempotency-Key: <fresh uuid>` (set by pre-request script)
- Body:
  ```json
  {
    "restaurantId": "{{restaurantId}}",
    "items": [
      { "menuItemId": "{{menuItemId}}", "quantity": 2 }
    ],
    "notes": "Extra spicy"
  }
  ```
- Response: full order object including `id`, `sagaId`
- Test: capture `id` into `orderId`

### 8. Get order
- Method: GET
- URL: `{{directOrderUrl}}/api/orders/{{orderId}}`
- Auth: Bearer `{{jwt}}`
- Response: order with `status` (PLACED, VALIDATED, RESERVED, CONFIRMED, etc.)
- Test: assert status code 200, log status to console

### 9. Get saga
- Method: GET
- URL: `{{directOrderUrl}}/api/orders/{{orderId}}/saga`
- Auth: Bearer `{{jwt}}`
- Response: `{"sagaId": "...", "status": "...", "currentStepIndex": N, "steps": [...]}`
- Test: log current step

### 10. Get payment by order
- Method: GET
- URL: `{{directPaymentUrl}}/api/payments/by-order/{{orderId}}`
- Auth: Bearer `{{jwt}}`
- Response: `{"paymentId": "...", "razorpayOrderId": "...", "amount": "...", "status": "...", ...}`

### 11. Pre-flight: gateway health
- Method: GET
- URL: `{{baseUrl}}/actuator/health`
- Auth: none
- Response: `{"status":"UP"}`

### 12. Pre-flight: auth JWKS
- Method: GET
- URL: `{{directAuthUrl}}/oauth2/jwks`
- Auth: none
- Response: standard JWKS

## Agent split

- **Agent D** writes the **header part** (collection info, auth pre-request, folders 00–01):
  - `00 — Pre-flight` folder: requests 11, 12, plus a 3rd health check on
    `{{directRestaurantUrl}}/actuator/health` (any service works)
  - `01 — Register & Login` folder: requests 1, 2, 3 (login × 2 — once for
    customer, once for owner). The owner-seeding limitation: include a
    4th "Manual: seed owner role via SQL" request that's actually a
    No-Op request with a Tests script that prints the SQL to run
    (Postman pm.environment.set + console.log the full psql command).
- **Agent E** writes **folders 02–03**:
  - `02 — Restaurant Owner` folder: requests 4, 5 (×2 — add 2 menu items)
  - `03 — Customer` folder: requests 6, 8 (read order — get, listing by id is
    also useful here, plus a `GET /api/users/me` to verify JWT works)
- **Agent F** writes **folders 04–06**:
  - `04 — Order` folder: request 7 (place order)
  - `05 — Saga` folder: request 8 (get order, polls status), request 9 (get saga)
  - `06 — Payment` folder: request 10 (get payment by order), plus a
    `GET /api/payments/{{paymentId}}` if `paymentId` is captured, and a
    `GET /api/payments/{{paymentId}}/events` for the event stream

## Output format

Each agent writes a partial JSON file in `postman/` with shape:
- `agent_d.part.json` — the items array for folders 00–01
- `agent_e.part.json` — the items array for folders 02–03
- `agent_f.part.json` — the items array for folders 04–06

The parent (Claude in the main session) will assemble these into
`postman/samato.postman_collection.json`.

A "part" file is a JSON array. The array elements are the FOLDERS
themselves (not leaf requests). Each folder has the standard
`{name, item: [...requests...]}` shape.

## Postman v2.1.0 schema cheatsheet

```json
{
  "info": {
    "name": "Samato Bible — Happy Path",
    "_postman_id": "<generate a uuid>",
    "description": "...",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "Folder name",
      "item": [
        {
          "name": "Request name",
          "request": {
            "method": "POST",
            "header": [
              { "key": "Content-Type", "value": "application/json" }
            ],
            "url": {
              "raw": "{{directAuthUrl}}/api/auth/register",
              "host": ["{{directAuthUrl}}"],
              "path": ["api", "auth", "register"]
            },
            "body": {
              "mode": "raw",
              "raw": "{\"email\":\"{{customerEmail}}\",\"password\":\"{{customerPassword}}\"}",
              "options": { "raw": { "language": "json" } }
            }
          },
          "event": [
            {
              "listen": "test",
              "script": {
                "exec": [
                  "const json = pm.response.json();",
                  "if (json.id) { pm.collectionVariables.set('customerId', json.id); }",
                  "pm.test('status is 2xx', () => pm.expect(pm.response.code).to.be.oneOf([200, 201, 204]));"
                ]
              }
            }
          ]
        }
      ]
    }
  ],
  "event": [
    {
      "listen": "prerequest",
      "script": { "exec": ["..."] }
    }
  ]
}
```

## Critical: variable scope

Use `pm.collectionVariables.set()` in Tests (not `pm.environment.set`).
Collection variables live with the collection, not the environment.
This means a user can switch environments without losing their captured
JWTs and IDs.
