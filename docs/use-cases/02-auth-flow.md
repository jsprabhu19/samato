# Use case: Register, log in, call a protected API

> Alice opens the Samato app for the first time. She creates an account, gets a JWT, and immediately calls `GET /api/users/me` to see her (initially empty) profile. This is the end-to-end happy path through the entire identity stack — registration → token issuance → gateway validation → resource-server re-validation → first authenticated read.

This document is the **narrative companion** to [../02-how-auth-works.md](../02-how-auth-works.md). That doc explains the mechanism (JWT structure, JWKS, RSA signing, `NimbusJwtDecoder` wiring). This one walks through what *actually happens* when a real user goes through the flow. If you have not read the mechanism doc, skim [§3 "The full JWT lifecycle"](../02-how-auth-works.md#3-the-full-jwt-lifecycle) first; this doc will reference its sections rather than re-explain them.

---

## 1. The story

Alice has just downloaded the Samato app. She has never registered before. Here is the user-visible story:

1. **Sign-up.** Alice taps "Create account" and types `alice@example.com` + a password. The app POSTs to `/api/auth/register`. Within ~250 ms she gets a `201 Created` with her user id. She is now in the `auth` database. No token yet — registration does not log her in.
2. **Log in.** The app POSTs to `/oauth2/token` (or, in dev, to `/api/auth/dev-token?email=alice@example.com`) and gets back an `access_token` (a long base64url string) plus a `refresh_token`. She stores these in memory.
3. **First protected call.** The app immediately calls `GET /api/users/me` with `Authorization: Bearer <access_token>`. The api-gateway validates the token, injects `X-User-Id` and `X-User-Roles` headers, and forwards the request to `user-service`. `user-service` re-validates the token against the same JWKS, runs `@PreAuthorize("isAuthenticated()")`, and reads Alice's UUID from `jwt.getSubject()`. Because no customer profile row exists yet, the controller **creates a skeleton profile** (default `displayName` = `alice`) and returns it. Alice is now logged in.

What changes in the system:
- **`auth` database** (Postgres) gains one row in `users` + one row in `user_roles` (registration), then gains a `users.findById` read on every `/api/auth/me` call.
- **`user_service` database** (Postgres) gains one row in `customer_profiles` on the first `/api/users/me` call (skeleton insert).
- **No Kafka events** are published by this flow — identity is synchronous.

That's the whole story in 200 words. The rest of the doc unpacks each step.

---

## 2. The cast of characters

The actors and trust boundaries. Arrows show request direction; dashed lines show trust relationships (e.g. "holds the public key" or "fetches JWKS at startup").

```
                          +----------------------+
                          |  Alice's browser     |
                          |  (or curl, or SPA)   |
                          +----------+-----------+
                                     |
                                     |  (1) POST /api/auth/register   (no JWT)
                                     |  (2) POST /oauth2/token        (Basic auth + form)
                                     |  (3) GET  /api/users/me         (Authorization: Bearer)
                                     v
                          +----------------------+
                          |   api-gateway  :8080  |
                          |  Spring Cloud        |
                          |  Gateway (WebFlux)   |
                          |  - validates JWT     |----+
                          |  - injects X-User-Id |    |
                          |  - injects X-User-Roles|  |
                          +----------------------+    |
                                     |                |  (4) GET /.well-known/jwks.json
                                     v                |      (cached, no per-request call)
              +----------------------+   +------------v--------+
              |   auth-service :9000 |   |   user-service      |
              |  Spring Auth Server  |<--+   :8081             |
              |                      |   |   (validates JWT    |
              |  - /api/auth/register|   |    locally too;     |
              |  - /api/auth/me      |   |    defence in depth)|
              |  - /api/auth/dev-token   |                      |
              |  - /oauth2/token     |   |   controllers:      |
              |  - /.well-known/jwks |   |   CustomerProfile.. |
              +-----------+----------+   +----------+-----------+
                          |                         |
                          v                         v
                   +-------------+           +-------------+
                   | Postgres    |           | Postgres    |
                   | `auth` DB   |           | `user_      |
                   |             |           |  service` DB|
                   | - users     |           |             |
                   | - user_roles|           | - customer_ |
                   | - oauth_    |           |   profiles  |
                   |   clients   |           | - driver_   |
                   +-------------+           |   profiles  |
                                             | - owner_    |
                                             |   profiles  |
                                             +-------------+

   Trust relationships (who holds what key):
   ┌──────────────────┬────────────────┬───────────────────────────┐
   │ actor            │ holds priv key │ holds public key          │
   ├──────────────────┼────────────────┼───────────────────────────┤
   │ auth-service     │ YES            │ YES (publishes via JWKS)  │
   │ api-gateway      │ no             │ YES (cached from JWKS)    │
   │ user-service     │ no             │ YES (cached from JWKS)    │
   │ (any caller)     │ no             │ (just transmits the JWT)  │
   └──────────────────┴────────────────┴───────────────────────────┘
```

The **trust boundary** runs along the `Authorization: Bearer` header: the JWT is unforgeable because only `auth-service` holds the RSA private key. Every other service can verify but cannot sign. The `X-User-Id` and `X-User-Roles` headers are a **convenience** for downstream logging, not a trust shortcut — every service re-validates the JWT anyway (defence in depth). See [../02-how-auth-works.md §5 "Why the gateway validates first AND downstream services re-validate"](../02-how-auth-works.md#5-why-the-gateway-validates-first-and-downstream-services-re-validate).

---

## 3. Step 1: Alice registers

### 3.1 The HTTP request

```http
POST /api/auth/register HTTP/1.1
Host: localhost:9000
Content-Type: application/json

{ "email": "alice@example.com", "password": "Password123!" }
```

- **Path**: `/api/auth/register` — handled by `RegistrationController#register` at [`services/auth-service/src/main/java/com/samato/authservice/web/RegistrationController.java`](../../services/auth-service/src/main/java/com/samato/authservice/web/RegistrationController.java) (line 43).
- **Body validation** (lines 59-61): `RegisterRequest` is a Java `record` with `@Email @NotBlank` on `email` and `@NotBlank @Size(min=8, max=100)` on `password`. Validation failures throw `MethodArgumentNotValidException` → 400 via the shared `GlobalExceptionHandler`.
- **Auth**: **none.** The path is on the `permitAll()` allow-list in [`AuthServerConfig#defaultSecurityFilterChain`](../../services/auth-service/src/main/java/com/samato/authservice/security/AuthServerConfig.java) (line 101-104). No JWT, no client credentials — anyone can call it.

### 3.2 How the request reaches `auth-service`

```
browser -> api-gateway (port 8080) -> auth-service (port 9000)
```

The path `/api/auth/**` matches the route at [`GatewayRoutesConfig#routes`](../../services/api-gateway/src/main/java/com/samato/apigateway/config/GatewayRoutesConfig.java) (line 36-40):

```java
.route("samato-auth-service", r -> r
        .path("/api/auth/**")
        .filters(f -> f.addRequestHeader("X-Source", "samato-api-gateway"))
        .uri("lb://SAMATO-AUTH-SERVICE"))
```

The `lb://` prefix tells Spring Cloud Gateway to ask Eureka for a healthy instance of `samato-auth-service` (Eureka uppercases the registered id). The filter adds an `X-Source: samato-api-gateway` header so downstream logs can tell "this came through the gateway" from "this came direct."

In `api-gateway/SecurityConfig.java` (line 47), the `pathMatchers("/api/auth/**").permitAll()` rule lets the request through the reactive security filter without a token.

> **Note for callers behind a browser**: the api-gateway also adds CORS headers (see [`CorsConfig.java`](../../services/api-gateway/src/main/java/com/samato/apigateway/config/CorsConfig.java) and `application.yml` line 23-29). For a SPA on `http://localhost:5173`, the `OPTIONS` preflight must succeed first. The dev-mode `allowedOriginPatterns: "*"` makes the preflight always pass — see the [production hardening note in 02-how-auth-works.md §10](../02-how-auth-works.md#10-cors--auth).

### 3.3 What `RegistrationController` does

`RegistrationController#register` ([`RegistrationController.java`](../../services/auth-service/src/main/java/com/samato/authservice/web/RegistrationController.java) line 45-57) does four things, in this order:

1. **Duplicate check.** `users.existsByEmail(req.email())` queries the `users` table via the unique index `idx_users_email` ([`V1__init.sql`](../../services/auth-service/src/main/resources/db/migration/V1__init.sql) line 5 + 17). If a row exists, the controller throws `DomainException("EMAIL_TAKEN", "Email already registered", 409)` — the shared `GlobalExceptionHandler` renders the JSON error envelope.
2. **BCrypt the password.** `encoder.encode(req.password())` calls the `PasswordEncoder` bean from [`AuthServerConfig#passwordEncoder()`](../../services/auth-service/src/main/java/com/samato/authservice/security/AuthServerConfig.java) (line 121-124), which is `new BCryptPasswordEncoder(12)`. Cost factor 12 means each hash takes ~250 ms — slow by design. The raw password is never written anywhere.
3. **Build the entity.** `new UserAccount()` → `setEmail(...)` → `setPasswordHash(...)` → `setRoles(Set.of(Role.CUSTOMER))`. The role is **always** `CUSTOMER` for public sign-up. The other three roles (`RESTAURANT_OWNER`, `DRIVER`, `ADMIN`) are granted by an admin in a real system; the bible seeds them via `DevDataSeeder` for dev.
4. **Persist.** `users.save(u)`. Hibernate inserts one row in `users` (auto-generated UUID via `@GeneratedValue`) and one row in `user_roles` via the `@ElementCollection` mapping on [`UserAccount.java`](../../services/auth-service/src/main/java/com/samato/authservice/domain/UserAccount.java) (line 39-43). Both rows commit in the same JPA transaction.

### 3.4 The Flyway migration that made this possible

The tables did not exist before — they were created on first startup by [`V1__init.sql`](../../services/auth-service/src/main/resources/db/migration/V1__init.sql):

```sql
CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,    -- <- enforces "one account per email" at DB level
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE TABLE user_roles (
    user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     VARCHAR(50)  NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE INDEX idx_users_email ON users(email);

CREATE TABLE oauth_clients (
    id                  UUID         PRIMARY KEY,
    client_id           VARCHAR(100) NOT NULL UNIQUE,
    client_secret_hash  VARCHAR(255) NOT NULL,
    redirect_uri        VARCHAR(500),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL
);
```

Three tables, two indexes. The `email` column is `UNIQUE` **at the SQL level** — the controller's `existsByEmail` check is an optimisation, not the source of truth. A concurrent registration race is impossible because the DB rejects the second insert.

The `oauth_clients` table is also created here, even though `RegistrationController` never touches it — it is the table that Spring Authorization Server's `JpaRegisteredClientRepository` reads on every `/oauth2/token` call to verify the client's `client_secret`. See [Step 2a](#4a-the-standard-oauth2-password-grant-production-shaped).

### 3.5 The HTTP response

```http
HTTP/1.1 201 Created
Content-Type: application/json

{ "id": "8a1b2c3d-4e5f-6789-abcd-ef0123456789", "email": "alice@example.com" }
```

- Status `201 Created` comes from `@ResponseStatus(HttpStatus.CREATED)` on the handler (line 44 of `RegistrationController.java`).
- The `id` is the server-generated UUID — the client never picks user ids.
- **No token is issued here.** Alice is now in the database but she is not yet logged in.

### 3.6 Verification

```bash
# Confirm the row landed.
docker exec -it samato-postgres psql -U fd -d auth -c \
  "SELECT id, email, created_at FROM users WHERE email = 'alice@example.com';"

# Confirm the role row too.
docker exec -it samato-postgres psql -U fd -d auth -c \
  "SELECT user_id, role FROM user_roles;"

# Or via the API: try to register the same email again — should get 409.
curl -i -X POST http://localhost:9000/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"Password123!"}'
```

---

## 4. Step 2: Alice logs in (two paths)

Registration does not return a token. To call any protected endpoint, Alice needs a JWT. The bible has **two** ways to get one, both backed by the same RSA keypair:

- **Path A** — `POST /oauth2/token` (the OAuth2 password grant, via Spring Authorization Server). Production-shaped.
- **Path B** — `GET /api/auth/dev-token?email=...` (a hand-rolled controller that signs with the same RSA key). Dev-only.

Both paths return a real RS256 JWT. The gateway accepts either. Pick A for production-shaped work, B for `curl` experiments.

### 4a. The standard OAuth2 password grant (production-shaped)

#### The HTTP request

```http
POST /oauth2/token HTTP/1.1
Host: localhost:9000
Authorization: Basic YXBpLWdhdGV3YXk6Z2F0ZXdheS1zZWNyZXQtcGxlYXNlLXJvdGF0ZQ==
Content-Type: application/x-www-form-urlencoded

grant_type=password&username=alice@example.com&password=Password123!&scope=openid profile
```

- **Basic auth** carries the OAuth2 *client* credentials — `api-gateway:gateway-secret-please-rotate` base64-encoded. The client row exists in the `oauth_clients` table because `DevDataSeeder` runs on startup ([`DevDataSeeder.java`](../../services/auth-service/src/main/java/com/samato/authservice/config/DevDataSeeder.java) line 58).
- **Form body** carries the *resource owner's* credentials — `username=alice@example.com` and `password=...`. Yes, "username" is the email. That's the OAuth2 spec, not a Samato invention.

#### What happens server-side

The request hits the `@Order(1) authServerFilterChain` ([`AuthServerConfig.java`](../../services/auth-service/src/main/java/com/samato/authservice/security/AuthServerConfig.java) line 65-89), which restricts itself to `/oauth2/**` and `/.well-known/openid-configuration`. Spring AS then runs its own internal filter chain:

1. **Authenticate the client.** `JpaRegisteredClientRepository#findByClientId` ([`JpaRegisteredClientRepository.java`](../../services/auth-service/src/main/java/com/samato/authservice/security/JpaRegisteredClientRepository.java) line 52-56) looks up `api-gateway` in `oauth_clients` and uses `DelegatingPasswordEncoder` to BCrypt-compare the presented `client_secret` against the stored hash. The class Javadoc on lines 60-69 explains an earlier `{bcrypt}` double-encoding bug that was fixed — the hash is now passed **verbatim** so the prefix detection works.

2. **Authenticate the user.** `JpaUserDetailsService#loadUserByUsername` ([`JpaUserDetailsService.java`](../../services/auth-service/src/main/java/com/samato/authservice/security/JpaUserDetailsService.java)) calls `users.findByEmail(email)` and wraps the result as Spring Security's `User`. Wrong password → 400 `invalid_grant`. Unknown email → same 400 (the bible does not differentiate "user not found" from "wrong password" — the response is identical to avoid email enumeration).

3. **Build the access token.** Spring AS's token endpoint constructs a `JwtClaimsSet` and a `JwsHeader` and hands them to a `JwtEncoder` for signing. The `JwtEncoder` uses the `JWKSource<SecurityContext>` bean from `AuthServerConfig#jwkSource` (line 134-144) — the same RSA keypair that's exposed at `/.well-known/jwks.json`.

4. **Add custom claims.** Right before signing, `JwtRolesCustomizer#customize` ([`JwtRolesCustomizer.java`](../../services/auth-service/src/main/java/com/samato/authservice/security/JwtRolesCustomizer.java) line 36-58) runs and adds three claims: `roles` (a `Set<String>` of the user's role names, e.g. `["CUSTOMER"]`), `user_id` (the UUID), and `email`. These are what make `@PreAuthorize("hasRole('CUSTOMER')")` work downstream — see [../02-how-auth-works.md §4 "The payload"](../02-how-auth-works.md#the-payload-decoded) for the full claim set.

5. **Sign with RS256.** The `JwsHeader.with(SignatureAlgorithm.RS256).build()` declares the algorithm; `JwtEncoder.encode(...)` produces the three-part base64url string. The signing happens in-process with the private half of the 2048-bit RSA keypair generated at startup by `AuthServerConfig#generateRsaKey()` (line 168-176). **The private key never leaves the JVM** — the public half is the only thing the JWKS publishes.

#### TokenSettings (lifetime config)

The access-token and refresh-token lifetimes come from [`application.yml`](../../services/auth-service/src/main/resources/application.yml) lines 32-40:

```yaml
spring.security.oauth2.authorizationserver:
  access-token:
    format: self-contained     # JWT, not opaque
    lifetime: PT15M            # 15 minutes
  refresh-token:
    lifetime: P7D              # 7 days
  grant-types:
    password: enabled
    refresh_token: enabled
    client_credentials: enabled
```

15 minutes is short by design — a stolen access token is usable for at most 15 minutes. The 7-day refresh token means Alice does not have to re-enter her password for a week.

#### The HTTP response

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IjAxMjM0NTY3LTg5YWItY2RlZi0xMjM0LTEyMzQ1Njc4OTBhYiJ9.eyJzdWIiOiI4YTFiMmMzZC00ZTVmLTY3ODktYWJjZC1lZjAxMjM0NTY3ODkiLCJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjkwMDAiLCJleHAiOjE3MjEwNDA5MDAsInNjb3BlIjoib3BlbmlkIHByb2ZpbGUgcmVhZCB3cml0ZSIsInJvbGVzIjpbIkNVU1RPTUVSIl0sInVzZXJfaWQiOiI4YTFiMmMzZC00ZTVmLTY3ODktYWJjZC1lZjAxMjM0NTY3ODkiLCJlbWFpbCI6ImFsaWNlQGV4YW1wbGUuY29tIn0.<signature>",
  "refresh_token": "abc123...",
  "token_type": "Bearer",
  "expires_in": 900,
  "scope": "openid profile read write"
}
```

The `access_token` is the long base64url string Alice sends on every subsequent request. Decode it (paste into [jwt.io](https://jwt.io)) to see the header + payload.

> **Anomaly to be aware of.** Spring Authorization Server 1.3.x **dropped** the resource-owner password grant in some configurations — depending on the version, `POST /oauth2/token` with `grant_type=password` may return `unsupported_grant_type`. The bible's [`application.yml`](../../services/auth-service/src/main/resources/application.yml) line 37-38 sets `password: enabled` explicitly to keep it on. If you upgrade Spring AS and the password grant silently stops working, that's the first place to look. The dev-token workaround (Path B, below) exists exactly because of this fragility.

### 4b. The dev-token endpoint (workaround in the dev profile)

The other way to get a token — and the one you'll use most often in local dev — is the hand-rolled `DevTokenController`. It exists because (a) Spring AS 1.3.x's password-grant is fragile, and (b) `curl "http://localhost:9000/api/auth/dev-token?email=alice@example.com"` is just easier than building a form-encoded request with HTTP Basic auth headers.

#### The HTTP request

```bash
curl "http://localhost:9000/api/auth/dev-token?email=alice@example.com"
```

- **No client auth.** No `Authorization: Basic`, no form body. Just the email of a seeded user.
- **No JWT required.** It's a `permitAll()` path (the controller is registered, the path is public, and there's no `Authorization` header to check).

#### What happens server-side

[`DevTokenController#devToken`](../../services/auth-service/src/main/java/com/samato/authservice/web/DevTokenController.java) (line 90-126):

1. **Look up the user.** `users.findByEmail(email)` returns the `UserAccount` (with roles loaded eagerly via the `@ElementCollection`).
2. **Build claims manually.** The controller constructs a `JwtClaimsSet` with `issuer("http://localhost:9000")`, `subject(userId)`, `audience(["samato-api-gateway"])`, `claim("user_id", ...)`, `claim("email", ...)`, `claim("roles", ...)` — the **exact same shape** that `JwtRolesCustomizer` produces for password-grant tokens.
3. **Sign with the same RSA key.** The constructor (line 66-79) builds a `NimbusJwtEncoder` from the **same** `JWKSource<SecurityContext>` bean that the AS uses. The comment on lines 70-79 is explicit: *"Build an encoder that signs with the same RSA key the authorization server publishes in its JWKS endpoint. That way the gateway's signature verification ... accepts tokens issued by this endpoint transparently."*
4. **Return the token.** Same JSON shape as Path A, but with a hard-coded `expires_in: 3600` (1 hour — `TOKEN_TTL_MINUTES = 60` on line 61, longer than the AS's 15 minutes because dev sessions are long).

#### The role it issues

Whatever roles the user has. Alice was registered as `CUSTOMER`, so her dev token has `"roles": ["CUSTOMER"]`. Bob (`bob@example.com`, seeded with `RESTAURANT_OWNER`) gets `"roles": ["RESTAURANT_OWNER"]`. The `DevDataSeeder` ([`DevDataSeeder.java`](../../services/auth-service/src/main/java/com/samato/authservice/config/DevDataSeeder.java) line 49-56) seeds five users — one per role plus a service account.

#### The HTTP response

```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "openid profile read write"
}
```

No `refresh_token` field — the dev controller does not issue one (it would be valid for 7 days against a 1-hour access token, which is a confusing ratio). The token is real, signed with the same RSA key, and the gateway accepts it.

> **The dev-only gate.** The class is annotated `@Profile({"dev", "default"})` (line 57). In a `prod` profile, the bean does not exist and the endpoint is dead. **But the gate is `{"dev", "default"}`, not just `{"dev"}`** — this means a deployment that forgets to set `SPRING_PROFILES_ACTIVE=prod` will still expose the dev-token endpoint. The bible accepts this trade-off (a fresh `docker compose up` "just works" with no env var) but it is worth flagging for prod hardening. See [02-how-auth-works.md §2b "The dev-token anomaly"](../02-how-auth-works.md#2b-the-dev-token-endpoint-workaround-for-spring-as-13x).

### 4c. Either way, the same RSA key

Both paths produce RS256 JWTs signed with the same private key. Both are valid bearer tokens. The gateway's `NimbusReactiveJwtDecoder` (configured with `jwk-set-uri` = auth-service's `/.well-known/jwks.json`) doesn't care which path minted the token — the signature is the signature, the `kid` is the `kid`, the public key lookup is the public key lookup.

---

## 5. Step 3: Alice calls a protected API

Alice now has a JWT. She calls `GET /api/users/me`:

```http
GET /api/users/me HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IjAxMjM0NTY3LTg5YWItY2RlZi0xMjM0LTEyMzQ1Njc4OTBhYiJ9.eyJzdWIiOiI4YTFiMmMzZC00ZTVmLTY3ODktYWJjZC1lZjAxMjM0NTY3ODkifQ.<signature>
```

No client auth, no body, no headers beyond the `Authorization: Bearer`. This request touches **three** services — the api-gateway, user-service, and (at startup) auth-service's JWKS. Here is the trace.

### 5.1 What happens at the api-gateway

The path `/api/users/**` matches the route at [`GatewayRoutesConfig#routes`](../../services/api-gateway/src/main/java/com/samato/apigateway/config/GatewayRoutesConfig.java) (line 43-47):

```java
.route("samato-user-service", r -> r
        .path("/api/users/**")
        .filters(f -> f.addRequestHeader("X-Source", "samato-api-gateway"))
        .uri("lb://SAMATO-USER-SERVICE"))
```

The `lb://` prefix resolves through Eureka. Five things happen before the request is forwarded to user-service:

1. **CorrelationIdWebFilter** ([`CorrelationIdWebFilter.java`](../../services/api-gateway/src/main/java/com/samato/apigateway/config/CorrelationIdWebFilter.java), `Ordered.HIGHEST_PRECEDENCE`) reads or generates an `X-Correlation-Id` header and pushes it into MDC. Every log line emitted during this request will carry the id.

2. **Spring Security reactive filter** ([`api-gateway/SecurityConfig.java`](../../services/api-gateway/src/main/java/com/samato/apigateway/security/SecurityConfig.java) line 42-61) validates the JWT using the `ReactiveJwtDecoder` bean from [`JwtConfig.java`](../../services/api-gateway/src/main/java/com/samato/apigateway/config/JwtConfig.java) (line 30-34):

   ```java
   return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
   ```

   On the first call ever, `NimbusReactiveJwtDecoder` fetches the JWKS from `http://auth-service:9000/.well-known/jwks.json` (or `http://localhost:9000/...` in local dev), parses it into a `Map<kid, JWK>`, and caches it. Subsequent calls just do an in-memory `kid` lookup — no per-request HTTP call to auth-service. Signature mismatch, missing `kid`, or `exp` in the past → 401.

   > **Why does the security filter do JWT validation instead of `JwtAuthFilter`?** In WebFlux, Spring Security installs itself as a `WebFilter` that runs *before* Spring Cloud Gateway's filter chain. A `GlobalFilter` like `JwtAuthFilter` runs *after*. The class Javadoc on `SecurityConfig.java` (line 23-31) puts it bluntly: *"without wiring JWT validation into Spring Security here, every request to a protected route is rejected with an empty 401 before the gateway even sees it."* Don't refactor this out.

3. **JwtAuthFilter** ([`JwtAuthFilter.java`](../../services/api-gateway/src/main/java/com/samato/apigateway/security/JwtAuthFilter.java), `Ordered.HIGHEST_PRECEDENCE + 10`, line 41-108) runs **after** the security filter and does two things:
   - **Re-validates the JWT** with the same `ReactiveJwtDecoder` (defence in depth, even though the security filter just did it).
   - **Mutates the request** to add two headers (line 82-85):
     - `X-User-Id` — Alice's UUID, read from the `user_id` claim (falling back to `sub`).
     - `X-User-Roles` — comma-joined role names (e.g. `CUSTOMER`).

   On `JwtException`, the filter writes a clean 401 JSON body (line 87-90, 93-101) instead of letting the framework's empty 401 leak out.

4. **Route matcher.** Spring Cloud Gateway's `DiscoveryClientRouteLocator` matches `/api/users/**` to the `lb://SAMATO-USER-SERVICE` route and resolves the service id to a host:port via Eureka.

5. **Forward.** The request is forwarded with all original headers (`Authorization`, `X-Correlation-Id`) plus the two injected ones (`X-User-Id`, `X-User-Roles`) plus `X-Source: samato-api-gateway`.

### 5.2 What happens at user-service

The request arrives on port 8081. Three things happen in order:

1. **CorrelationIdFilter** (servlet, from the `shared` module) reads `X-Correlation-Id` and pushes it into MDC — same id the gateway stamped.

2. **SecurityConfig filter chain** ([`user-service/SecurityConfig.java`](../../services/user-service/src/main/java/com/samato/userservice/security/SecurityConfig.java) line 38-52) re-validates the JWT:

   ```java
   @Bean
   public JwtDecoder jwtDecoder(
       @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
       return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
   }
   ```

   The JWKS URI is read from [`user-service/application.yml`](../../services/user-service/src/main/resources/application.yml) line 28: `http://localhost:9000/.well-known/jwks.json`. Same `NimbusJwtDecoder` pattern as the gateway, just servlet instead of reactive. The cache and the `kid` lookup work the same way.

   The **defence-in-depth** moment is here: the gateway already validated this token, but user-service does it again. A bug or compromise at the gateway cannot bypass user-service's authN check. See [02-how-auth-works.md §5 "Why the gateway validates first AND downstream services re-validate"](../02-how-auth-works.md#5-why-the-gateway-validates-first-and-downstream-services-re-validate).

   The `JwtAuthenticationConverter` (line 69-77) extracts the `roles` claim and prefixes each value with `ROLE_`:

   ```java
   grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
   grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
   ```

   This is what makes `@PreAuthorize("hasRole('CUSTOMER')")` work downstream. Without this customisation, the JWT's default `scope` claim would be turned into `SCOPE_*` authorities and `hasRole` would never match. See [02-how-auth-works.md §8 "Roles vs scopes vs authorities"](../02-how-auth-works.md#8-roles-vs-scopes-vs-authorities).

3. **MeController#me** ([`CustomerProfileController.java`](../../services/user-service/src/main/java/com/samato/userservice/web/CustomerProfileController.java) line 40-46) runs:

   ```java
   @GetMapping("/me")
   @PreAuthorize("isAuthenticated()")
   public CustomerProfile getMine(@AuthenticationPrincipal Jwt jwt) {
       UUID id = UUID.fromString(jwt.getSubject());
       return repo.findByUserId(id)
               .orElseGet(() -> createSkeletonProfile(id, jwt.getClaimAsString("email")));
   }
   ```

   Three things happen here:
   - **Subject extraction.** `jwt.getSubject()` returns the `sub` claim — Alice's UUID string. The `customer_profiles` PK is `user_id` and matches this UUID (logical reference, no FK — services don't share databases).
   - **Profile lookup.** `repo.findByUserId(id)` queries `customer_profiles` by PK. The row does not exist yet (Alice just registered), so the `Optional` is empty.
   - **Skeleton creation.** `createSkeletonProfile(id, "alice@example.com")` (line 68-77) creates a `CustomerProfile` with the email's local part as the default `displayName` ("alice"). The row is saved. This is a deliberate "fail soft" pattern — instead of 404-ing on a brand-new user, the service makes the row so the next `GET` is a hit.

### 5.3 The HTTP response

```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "userId": "8a1b2c3d-4e5f-6789-abcd-ef0123456789",
  "displayName": "alice",
  "phone": null,
  "photoUrl": null,
  "preferencesJson": null,
  "createdAt": "2026-07-15T12:00:00Z",
  "updatedAt": "2026-07-15T12:00:00Z"
}
```

The response serialises the skeleton profile. Note that `password` is never in the response — it doesn't exist in `user-service` at all. Passwords live in `auth-service`; this is the **auth ≠ user profile** split (see [`UserAccount.java`](../../services/auth-service/src/main/java/com/samato/authservice/domain/UserAccount.java) line 10-21).

### 5.4 The full request flow (sequence diagram)

```
Alice           api-gateway              user-service        auth-service JWKS         Postgres
  |                  |                         |                     |                      |
  | GET /api/users/me|                         |                     |                      |
  | Bearer <jwt>     |                         |                     |                      |
  |----------------->|                         |                     |                      |
  |                  | CorrelationIdWebFilter  |                     |                      |
  |                  | (stamp X-Correlation-Id)|                     |                      |
  |                  |                         |                     |                      |
  |                  | Spring Security:        |                     |                      |
  |                  | NimbusReactiveJwtDecoder.decode(jwt)          |                      |
  |                  |   kid lookup ----------->|                     |                      |
  |                  |   (cached, no HTTP)     |  (first call only:  |                      |
  |                  |                         |   GET /.well-known/jwks.json)               |
  |                  |                         |<------------------->|                      |
  |                  |   signature OK          |                     |                      |
  |                  | JwtAuthFilter:          |                     |                      |
  |                  |   inject X-User-Id,     |                     |                      |
  |                  |          X-User-Roles   |                     |                      |
  |                  | lb://SAMATO-USER-SERVICE->                   |                      |
  |                  |                         |                     |                      |
  |                  |  GET /api/users/me      |                     |                      |
  |                  |  Authorization: Bearer  |                     |                      |
  |                  |  X-User-Id: <uuid>      |                     |                      |
  |                  |  X-User-Roles: CUSTOMER |                     |                      |
  |                  |  X-Correlation-Id: ...  |                     |                      |
  |                  |  X-Source: samato-api-gateway                |                      |
  |                  |------------------------>|                     |                      |
  |                  |                         | CorrelationIdFilter |                      |
  |                  |                         | Spring Security:    |                      |
  |                  |                         |   NimbusJwtDecoder.decode(jwt)              |
  |                  |                         |   (cached JWKS)     |                      |
  |                  |                         | JwtAuthenticationConverter                  |
  |                  |                         |   roles -> ROLE_CUSTOMER                     |
  |                  |                         | @PreAuthorize("isAuthenticated()")          |
  |                  |                         | CustomerProfileController.getMine            |
  |                  |                         |   jwt.getSubject() = <uuid>                 |
  |                  |                         |   repo.findByUserId(uuid) ------------------>|
  |                  |                         |   (no row)                                  |
  |                  |                         |   createSkeletonProfile(uuid, email) ------>|
  |                  |                         |     INSERT INTO customer_profiles           |
  |                  |                         |<------------------                        |
  |                  |                         | 200 {userId, displayName, ...}              |
  |                  |<------------------------|                     |                      |
  |  200 OK          |                         |                     |                      |
  |<-----------------|                         |                     |                      |
```

---

## 6. Step 4: The token expires

The access token's `exp` claim is 15 minutes after `iat` (per `access-token.lifetime: PT15M` in `application.yml`). When that 15 minutes is up, what happens?

### 6.1 What happens when the access token expires

Alice makes a request 16 minutes after login. The gateway's `NimbusReactiveJwtDecoder` decodes the token, sees `exp < now`, throws `JwtValidationException` (or a subclass). The `JwtAuthFilter.onErrorResume(JwtException.class, ...)` (line 87-90) catches it and writes:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{"status":401,"code":"UNAUTHORIZED","message":"Invalid token: <exp claim details>"}
```

The user-service also rejects (same exception type, same HTTP code). The client must re-acquire a token.

### 6.2 The refresh path (no password re-entry)

The 7-day refresh token is the answer. Alice does this:

```http
POST /oauth2/token HTTP/1.1
Host: localhost:9000
Authorization: Basic YXBpLWdhdGV3YXk6Z2F0ZXdheS1zZWNyZXQtcGxlYXNlLXJvdGF0ZQ==
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token&refresh_token=<the-7-day-token>
```

This is the **standard OAuth2 refresh grant** — `grant_type=refresh_token` instead of `password`. The Spring AS token endpoint:

1. Authenticates the **client** the same way as Path A (Basic auth → `oauth_clients` lookup).
2. Looks up the **refresh token** it issued (it's stored in the `oauth2_authorization` table that Spring AS manages internally — you don't see this table, but it's there).
3. If the refresh token is valid and not expired, issues a **new access token** (15 min) plus a **new refresh token** (7 days, depending on rotation config).
4. The new access token has a new `iat` and `exp`, but the same `sub` (Alice's UUID), `roles`, and `kid`.

The new access token is sent on subsequent requests just like the original.

> **Refresh token rotation is not yet enabled.** Each refresh request returns a fresh access token but may return the same refresh token (no rotation). A stolen refresh token is valid for up to 7 days. See [02-how-auth-works.md §11 "Token revocation / Refresh token rotation"](../02-how-auth-works.md#11-whats-deliberately-not-implemented) for the production-hardening list. The `JwtRolesCustomizer` Javadoc hints at this for Phase 8.

The dev-token controller does not issue refresh tokens. If you're in dev and your 1-hour access token expires, just `curl` `/api/auth/dev-token` again.

### 6.3 What if the refresh token also expires

After 7 days, Alice has to log in again with her password. Same as Step 2a. The cycle restarts.

### 6.4 What if the RSA keypair is regenerated

Every time `auth-service` starts, `AuthServerConfig#generateRsaKey()` (line 168-176) generates a **fresh** 2048-bit RSA keypair. The new key has a new `kid`. **Every in-flight token becomes unverifiable** because:

- The JWT header has the old `kid`.
- The cached JWKS in every downstream service still has the old key (until the next refetch).
- `NimbusJwtDecoder` does the `kid` lookup, misses, refetches the JWKS, finds the **new** key, and tries to verify the old signature with the new public key.
- Signature mismatch → 401.

This is the **most operationally dangerous** aspect of the current implementation, called out in [02-how-auth-works.md §11 "Key rotation"](../02-how-auth-works.md#11-whats-deliberately-not-implemented) and in `AuthServerConfig`'s class Javadoc (line 47-56). For a 15-minute access-token lifetime, the blast radius is "users have to log in again within 15 minutes." For a 7-day refresh token, the user can `POST /oauth2/token` with `grant_type=refresh_token` and get a new access token (the refresh token doesn't carry a `kid` — it just needs to match the user's session in the AS's in-memory state).

The recovery path: nothing to do. The next JWKS fetch on the next request picks up the new key, and from that point forward, tokens signed by the new key verify correctly.

For production, the fix is to load the key from KMS/Vault and pin a `kid`; on rotation, publish both old and new keys in the JWKS until the old one's `exp` passes. Not implemented in the bible.

---

## 7. What can go wrong

The full list of failure modes for this use case, each with the actual error code or symptom and the file that produces it.

| Scenario | HTTP | Source / where it comes from |
|---|---|---|
| Wrong password on `/oauth2/token` | 400 `invalid_grant` | Spring AS's `OAuth2TokenEndpointFilter`. The bible does not differentiate "user not found" from "wrong password" — same response either way, to avoid email enumeration. |
| Email already taken on `/api/auth/register` | 409 `EMAIL_TAKEN` | [`RegistrationController.java`](../../services/auth-service/src/main/java/com/samato/authservice/web/RegistrationController.java) line 47 throws `DomainException("EMAIL_TAKEN", "Email already registered", 409)`. Rendered by the shared `GlobalExceptionHandler`. **The DB enforces it too** — `users.email` is `UNIQUE` in `V1__init.sql` line 5 — so even a race condition can't create duplicates. |
| `password` too short on register | 400 | `@Valid` on `RegisterRequest` → `MethodArgumentNotValidException` → shared `GlobalExceptionHandler`. The `@Size(min=8, max=100)` on the password field rejects anything outside 8-100 chars. |
| `email` malformed on register | 400 | `@Email` on `RegisterRequest` rejects `not-an-email` with the same `MethodArgumentNotValidException` path. |
| Missing `Authorization` header on protected call | 401 | `JwtAuthFilter.filter()` line 64-66: `if (authHeader == null || !authHeader.startsWith("Bearer "))` returns `reject(exchange, "Missing or malformed Authorization header")`. |
| JWT signature invalid | 401 | `NimbusReactiveJwtDecoder.decode(token)` throws `BadJwtException` on signature mismatch. `JwtAuthFilter.onErrorResume(JwtException.class, ...)` line 87-90 catches it and writes a clean 401 JSON. |
| JWT expired | 401 | Same path. The `exp` claim check happens inside the decoder. |
| JWT `iss` doesn't match the expected issuer | 401 | The decoder validates `iss` against `AuthorizationServerSettings.issuer("http://localhost:9000")`. If a future deploy changes the issuer URL, every token issued by the old issuer is rejected. **The dev-token controller also sets `issuer("http://localhost:9000")` independently** — they must agree. |
| JWKS endpoint unreachable at startup | Service may fail to start / 401s on every request | `NimbusJwtDecoder` fetches the JWKS **lazily** (not at construction time), so the service starts fine. The first request triggers the fetch; if it fails, the request 401s. The cache stays empty, the fetch keeps failing, until auth-service comes back. **The api-gateway's `depends_on` in compose should put `auth-service` first** so the JWKS is reachable by the time gateway starts. |
| CORS preflight failure on a cross-origin request | Browser blocks the request | The api-gateway's `CorsConfig` and `application.yml` line 23-29 set `allowedOriginPatterns: "*"`, `allowedMethods: "*"`, `allowedHeaders: "*"`, `exposedHeaders: "X-Correlation-Id"`. If a future service adds a new CORS-needing header, update both. See [02-how-auth-works.md §10](../02-how-auth-works.md#10-cors--auth). |
| Token replay attack (stolen JWT) | (no detection — token is valid until `exp`) | A JWT is self-contained and stateless. The bible has **no token revocation list** and **no JTI tracking** — see [02-how-auth-works.md §11 "Token revocation"](../02-how-auth-works.md#11-whats-deliberately-not-implemented). A stolen token is good for up to 15 minutes. Production would need a revocation list, short access tokens, or both. |
| User has the wrong role for the endpoint | 403 | `@PreAuthorize` on the controller method. Example: Alice (CUSTOMER) calls `GET /api/users/drivers/me` — the `hasRole('DRIVER')` check throws `AccessDeniedException` → 403 via Spring Security. |
| User is deleted but the JWT is still valid | (depends on the endpoint) | A JWT is a **session-less** credential. Deleting a user from `users` does not invalidate any in-flight tokens. The 15-minute window is the only protection. Some endpoints mitigate: `MeController#me` (line 41-43) does `users.findById(id).orElseThrow(AccessDeniedException)` and returns 403 if the user is gone. But `CustomerProfileController#getMine` reads from `user_service` and never checks the `auth` DB — a deleted user can still hit `/api/users/me` until their token expires. |
| Wrong audience (`aud`) | 401 (or silently accepted) | The bible's `NimbusJwtDecoder` does **not** validate the `aud` claim. The token carries `aud: ["samato-api-gateway"]` but no service checks it. See [02-how-auth-works.md §11 "Aud validation"](../02-how-auth-works.md#11-whats-deliberately-not-implemented). |
| `DevTokenController` exposed in a non-dev profile | **Critical security issue** | The class is `@Profile({"dev", "default"})`. In a `prod` profile the bean is gone. **But** a deployment that doesn't set `SPRING_PROFILES_ACTIVE=prod` will still expose it. Always set the profile explicitly in non-dev environments. |
| OAuth client disabled in `oauth_clients` | 400 `invalid_client` | `JpaRegisteredClientRepository#toRegisteredClient` does **not** check the `enabled` column. Even if you `UPDATE oauth_clients SET enabled = false WHERE client_id = 'api-gateway'`, the framework will still authenticate the client. The column exists for future use; today it's not consulted. |
| Wrong client_secret | 401 `invalid_client` | BCrypt comparison fails. `DelegatingPasswordEncoder` detects the `$2a$` prefix on the stored hash and dispatches to `BCryptPasswordEncoder.matches(presented, stored)`. The earlier `{bcrypt}` double-encoding bug (commented in `JpaRegisteredClientRepository.java` line 65-69) is fixed. |
| Bcrypt double-encoding regression | 401 on every password grant | The fixed bug, kept here as a reminder. Earlier code prepended `{bcrypt}` to the hash before passing it to `RegisteredClient.clientSecret(...)`, which caused the framework to call `BCryptPasswordEncoder.matches(rawInput, rawInput)` and fail the "looks like BCrypt" check on the raw side. The fix: pass the hash **verbatim**. The seeder (`DevDataSeeder.java` line 80-95) has logic to detect and rewrite legacy/clear-text rows. |

---

## 8. The data trail

For each step, what is written to which database. The bible has 12 databases (9 in use, 3 provisioned for Phase 6+); this use case touches **two** of them.

| Step | Database | Table | Operation | What changes |
|---|---|---|---|---|
| 3. Register | `auth` | `users` | `INSERT` | One new row: `id` (auto-generated UUID), `email`, `password_hash` (BCrypt), `created_at`, `updated_at`. |
| 3. Register | `auth` | `user_roles` | `INSERT` | One new row: `user_id` (the new UUID), `role = 'CUSTOMER'`. Managed by JPA's `@ElementCollection`. |
| 3. Register | `auth` | `oauth_clients` | none | (Seeded by `DevDataSeeder` on startup; not touched by registration.) |
| 4a. Login (password grant) | `auth` | `oauth_clients` | `SELECT` | One read: `findByClientId('api-gateway')` to authenticate the client. |
| 4a. Login | `auth` | `users` | `SELECT` | One read: `findByEmail('alice@example.com')` to authenticate the user. |
| 4a. Login | `auth` | `user_roles` | `SELECT` | Loaded eagerly with the `users` row via the `@ElementCollection`. |
| 4a. Login | `auth` | (Spring AS internal) | `INSERT` | Spring Authorization Server writes to an `oauth2_authorization` table it manages. The bible does not have a Flyway migration for this — Spring AS auto-creates it via `JdbcOAuth2AuthorizationService`. |
| 4b. Dev-token | `auth` | `users` | `SELECT` | One read: `findByEmail('alice@example.com')` (eager loads roles). |
| 5. GET /api/users/me (first call) | `user_service` | `customer_profiles` | `INSERT` | Skeleton row created by `CustomerProfileController#createSkeletonProfile`. |
| 5. GET /api/users/me (subsequent calls) | `user_service` | `customer_profiles` | `SELECT` | The skeleton row is found. |
| 5. PUT /api/users/me | `user_service` | `customer_profiles` | `UPDATE` | `displayName`, `phone`, `photoUrl` set. |
| 5. Any auth'd call | `auth` | none | none | Validation happens against the **cached JWKS** — no DB read per request. (The JWKS is cached; only on `kid` miss does the decoder refetch.) |

**No Kafka events** are published by this flow. Identity is synchronous.

---

## 9. The role of the two JWKS endpoints

The auth-service publishes the **same** JWKSet at **two** URLs:

| URL | Owner | When served |
|---|---|---|
| `/oauth2/jwks` | Spring Authorization Server's `@Order(1)` filter chain | Always, as part of the AS's default endpoints. |
| `/.well-known/jwks.json` | Custom [`JwksController`](../../services/auth-service/src/main/java/com/samato/authservice/web/JwksController.java) (annotated `@RequestMapping("/.well-known")`) | Always, served by a hand-written controller. |

Both return identical JSON. The `JwksController` Javadoc (line 26-37) is explicit: *"The set of keys published here is exactly the same as what `/oauth2/jwks` returns. The two are interchangeable. The .well-known path is the OIDC-canonical one; the /oauth2 path is Spring AS's default. We support both."*

**The custom `JwksController` is load-bearing.** Without it, the `.well-known/jwks.json` path falls through to the `@Order(2)` default security chain, which calls `anyRequest().authenticated()` ([`AuthServerConfig.java`](../../services/auth-service/src/main/java/com/samato/authservice/security/AuthServerConfig.java) line 105) and returns 401. Every downstream service's `NimbusJwtDecoder` would fetch a 401 instead of the JWKSet. **The bible standardises on `/.well-known/jwks.json`** (the OIDC-canonical path); `application.yml` files across the services all point at it:

| Service | Config key | URL |
|---|---|---|
| `api-gateway` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (via env in compose) | `http://auth-service:9000/.well-known/jwks.json` |
| `user-service` | [`application.yml`](../../services/user-service/src/main/resources/application.yml) line 28 | `http://localhost:9000/.well-known/jwks.json` |
| `order-service` | (per 02-how-auth-works.md §6) | `${AUTH_JWKS_URI:http://localhost:9000/.well-known/jwks.json}` |
| `payment-service` | (per 02-how-auth-works.md §6) | `${AUTH_JWKS_URI:http://localhost:9000/.well-known/jwks.json}` |
| `restaurant-service` | (per 02-how-auth-works.md §6) | `http://localhost:9000/.well-known/jwks.json` |
| `search-service` | (per 02-how-auth-works.md §6) | `http://localhost:9000/.well-known/jwks.json` |

The framework's `/oauth2/jwks` is **not used by any service** in the bible today. It exists because Spring AS ships it. Both endpoints read from the same `JWKSource<SecurityContext>` bean (`AuthServerConfig#jwkSource`, line 134-144), so there is exactly one source of truth in the JVM.

This is one of the four **load-bearing anomalies** called out in [02-how-auth-works.md §6](../02-how-auth-works.md#6-the-two-jwks-endpoints-the-anomaly). For the full anomaly breakdown (the second filter chain in payment-service, the missing `@CircuitBreaker` annotations, the JSON-on-the-wire Kafka anomaly), see that doc.

---

## 10. See also

- [../02-how-auth-works.md](../02-how-auth-works.md) — **the mechanism**. This doc is the narrative companion; that one is the technical reference. Read the §3 lifecycle there for the JWT structure and the per-step annotated code references.
- [../use-cases/01-place-an-order.md](../use-cases/01-place-an-order.md) — the **prerequisite** is the JWT from this flow. Once Alice has a token, she can place an order; that use case walks the saga.
- [../use-cases/03-browse-and-search.md](../use-cases/03-browse-and-search.md) — another **protected read API** (`GET /api/restaurants` and `GET /api/search`), same gateway-validates-then-service-re-validates pattern.
- [../01-architecture-guide.md](../01-architecture-guide.md) — the system map. The "Authentication" subsection of §7 is the one-paragraph version of [02-how-auth-works.md](../02-how-auth-works.md).
- [../00-glossary.md](../00-glossary.md) — every term used in this doc (JWT, JWKS, NimbusJwtDecoder, RS256, OAuth2, [defence in depth](../00-glossary.md), [cors](../00-glossary.md#cors-cross-origin-resource-sharing)) is defined in plain English.
- [../services/auth-service.md](../services/auth-service.md) — the full per-service walkthrough: every controller, the JWKS controller, the dev-token controller, the security config, `JwtRolesCustomizer`, the Flyway migration.
- [../services/user-service.md](../services/user-service.md) — the resource-server pattern (`SecurityConfig` + `JwtAuthenticationConverter`), the `AuthClient` Feign client (wired but not injected — see §7.1 of the per-service doc), the three profile controllers.
- [../services/api-gateway.md](../services/api-gateway.md) — `JwtConfig`, `JwtAuthFilter`, `SecurityConfig`, the route table, the CORS config. The class-level Javadoc on `SecurityConfig` (line 23-31) explains why JWT validation is in the reactive security chain and not just in the global filter.

Repo-level:

- [../../ARCHITECTURE.md](../../ARCHITECTURE.md) — the ADR list. ADR-11 ("Self-hosted Spring Authorization Server") is the decision that landed the bible on Spring AS.
- [../../RUN-THE-BIBLE.md](../../RUN-THE-BIBLE.md) — how to bring the 18-container stack up locally.
- [../../PROJECT-STATUS.md](../../PROJECT-STATUS.md) — what's running today vs. what's planned.
- [../INTERVIEW-CHEATSHEET.md](../INTERVIEW-CHEATSHEET.md) — quick-reference answers to common microservice interview questions, including auth.

---

*This doc was written as the "narrative companion" to 02-how-auth-works.md. If something here disagrees with the mechanism doc, the mechanism doc wins — it cites the line numbers; this one cites the concepts.*
