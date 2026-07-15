# How Authentication Works in Samato

> Samato's authentication is built around **stateless RS256 JWTs** issued by a single Spring Authorization Server. The auth-service (port 9000) is the only place that holds the RSA private key; every other service is a **resource server** that fetches the public half from a JWKS endpoint and validates bearer tokens **locally**, without phoning home per request. The api-gateway does the cryptographic check once at the edge, then re-emits the call with `X-User-Id` and `X-User-Roles` headers so downstream services can authorize without re-parsing the token. Every protected service re-validates anyway (defence in depth). This guide walks the full lifecycle — registration, login, token structure, JWKS, validation, propagation, and the four load-bearing anomalies the inventory surfaced.

---

## 1. The cast of characters

The actors below are real containers / services; arrows show the **trust relationships** and the HTTP direction of the JWKS fetch (dashed) and the authenticated request path (solid).

```
                          +----------------------+
                          |  browser / mobile /  |
                          |  curl / partner      |
                          +----------+-----------+
                                     |
                                     |  (1) Authorization: Bearer <jwt>
                                     |      on every protected call
                                     v
                          +----------------------+
                          |   api-gateway  :8080 |
                          |  Spring Cloud       |
                          |  Gateway (WebFlux)  |
                          |  - validates JWT     |
                          |  - injects X-User-Id |
                          |  - injects X-User-Roles
                          +----+--+-----+--+-----+
                               |  |     |  |     \
                               |  |     |  |      \ (routes)
                               v  v     v  v       v
            +----------+  +---------+ +------+ +-------+ +--------+ +---------+
            |  auth-   |  |  user-  | |order-| |search-| |payment-| |restaurant|
            |  service |  |  service| |service| |service| |service | |  service  |
            |  :9000   |  |  :8081  | |:8083 | |:8087  | |:8084   | |  :8082   |
            +----+-----+  +----+----+ +--+----+ +---+---+ +---+----+ +----+----+
                 |              |          |         |         |             |
                 |              |          |         |         |             |
                 |              +----------+---------+---------+-------------+
                 |                         |    |     |    |    |    |
                 |                         v    v     v    v    v    v
                 |              +-----------------------------------+
                 |              |   every protected service        |
                 |              |   NimbusJwtDecoder.withJwkSetUri  |
                 |              |   caches JWKS; validates locally |
                 |              +-----------------------------------+
                 |
                 |  (2) GET /.well-known/jwks.json
                 |      (cached, no per-request call)
                 v
        +----------------------------------+
        |  auth-service exposes            |
        |  - /.well-known/jwks.json (OURS) |
        |  - /oauth2/jwks      (Spring AS) |
        |  (both serve the same JWKSet)    |
        +----------------------------------+


        Trust relationships (who holds what key):
        ┌──────────────┬───────────────┬───────────────────────────────────┐
        │  service     │  has priv key │  has public key                  │
        ├──────────────┼───────────────┼───────────────────────────────────┤
        │  auth-service│     YES       │  YES (publishes it via JWKS)      │
        │  api-gateway │     no        │  YES (downloaded + cached)        │
        │  user/order/ │     no        │  YES (each downloads + caches)    │
        │  payment/etc │               │                                   │
        └──────────────┴───────────────┴───────────────────────────────────┘
```

**The trust story in one sentence**: the **auth-service** is the only place that can sign a token; every other service can **verify** a token (because they hold the public key) but cannot **forge** one. Verification is **offline** — no per-request call back to auth-service, the JWKS is fetched once and cached.

---

## 2. The vocabulary in 90 seconds

Every term below has a longer plain-English definition and a "where it shows up in Samato" pointer in the [glossary](./00-glossary.md). Use the table as a quick lookup while reading the rest of the doc.

| Term | One-line definition | Where in the glossary |
|---|---|---|
| [JWT](./00-glossary.md#jwt-json-web-token) | A signed, base64-encoded JSON object with three parts (header, payload, signature). | Glossary. |
| [JWS](./00-glossary.md#jwt-json-web-token) | A JWT with a signed signature (vs. an unsigned/plain JWT). Samato tokens are JWS. | Glossary. |
| [JWK](./00-glossary.md#jwk--jwks) | A single JSON Web Key — one public (or private) key in JSON form. | Glossary. |
| [JWKS](./00-glossary.md#jwk--jwks) | A JSON Web Key **Set** — a JWKSet document listing every currently valid public key. | Glossary. |
| [RS256](./00-glossary.md#rs256) | RSA + SHA-256. The asymmetric algorithm Samato uses to sign tokens. | Glossary. |
| Claim | A field in the JWT payload (`iss`, `sub`, `exp`, custom fields). | [JWT claims](./00-glossary.md#jwt-claims). |
| `sub` | The "subject" claim — Samato uses the user's UUID here. | [JWT claims](./00-glossary.md#jwt-claims). |
| `iss` | The "issuer" claim — the URL of the auth-service that minted the token. | [JWT claims](./00-glossary.md#jwt-claims). |
| `aud` | The "audience" claim — who the token is intended for. | [JWT claims](./00-glossary.md#jwt-claims). |
| `exp` | The "expiry" claim (epoch seconds). Samato's access tokens live 15 minutes. | [JWT claims](./00-glossary.md#jwt-claims). |
| [Scope](./00-glossary.md#scope) | An OAuth2 permission string like `orders:read`. | Glossary. |
| [Role](./00-glossary.md#authority--role--scope) | A `ROLE_*` authority extracted from the JWT's `roles` claim. | Glossary. |
| [Authority](./00-glossary.md#authority--role--scope) | Spring's generic "what can you do" object — a role is an authority with the `ROLE_` prefix. | Glossary. |
| Bearer | The HTTP auth scheme: `Authorization: Bearer <token>`. | [Resource server](./00-glossary.md#resource-server). |
| [Resource Server](./00-glossary.md#resource-server) | A service that **accepts** JWTs (vs. the **Authorization Server** that **issues** them). | Glossary. |
| [Authorization Server](./00-glossary.md#spring-authorization-server) | A service that issues tokens, serves the JWKS, registers clients. Samato's `auth-service`. | Glossary. |
| [NimbusJwtDecoder](./00-glossary.md#jwtdecoder) | Spring Security's JWT validator, backed by the Nimbus JOSE library. Configured via `.withJwkSetUri(...)` everywhere. | Glossary. |

> **Don't re-explain a term here.** The glossary has the full plain-English definition. This table is a reminder, not a substitute.

---

## 3. The full JWT lifecycle

The next six subsections walk one request end-to-end. Each step cites the actual file and config key that implements it.

### Step 1 — User registers

```
   client                api-gateway              auth-service                  Postgres
     |                       |                          |                          |
     |  POST /api/auth/      |                          |                          |
     |  register (no JWT)    |                          |                          |
     | --------------------> |  route: /api/auth/**     |                          |
     |                       |  permitAll (no JWT)      |                          |
     |                       | -----------------------> |                          |
     |                       |                          |  RegistrationController  |
     |                       |                          |  - validate body (@Valid)|
     |                       |                          |  - UserRepository        |
     |                       |                          |    .existsByEmail        |
     |                       |                          |  - PasswordEncoder       |
     |                       |                          |    .encode (BCrypt 12)   |
     |                       |                          |  - users.save + roles    |
     |                       |                          | -----------------------> |
     |                       |                          |  INSERT users + user_roles
     |                       |                          | <----------------------- |
     |                       | <----------------------- |  201 {id, email}         |
     | <-------------------- |                          |                          |
```

**Source files**:
- `services/auth-service/src/main/java/com/samato/authservice/web/RegistrationController.java` — `@PostMapping("/register")`, `@Valid RegisterRequest` (email `@Email`, password `@Size(min=8, max=100)`), `PasswordEncoder.encode(...)` (BCrypt cost 12), `UserRepository.save(...)` with default `Set.of(Role.CUSTOMER)`.
- `services/auth-service/src/main/java/com/samato/authservice/domain/UserAccount.java` — the `@Entity` with `@ElementCollection(targetClass = Role.class, fetch = EAGER)` for roles. Password is stored as `password_hash`; the raw password is never written.
- `services/auth-service/src/main/resources/db/migration/V1__init.sql` — creates the `users`, `user_roles`, and `oauth_clients` tables. The unique index `idx_users_email` enforces "one account per email" at the DB level (not just in code).
- `services/auth-service/src/main/java/com/samato/authservice/security/AuthServerConfig.java#defaultSecurityFilterChain` — `permitAll` on `/api/auth/**` (line 101) so registration requires no token.
- `shared/src/main/java/com/samato/shared/errors/DomainException.java` — `EMAIL_TAKEN` (409) when the email already exists; rendered as JSON by the shared `GlobalExceptionHandler` (see [shared-and-kafka.md](./services/shared-and-kafka.md)).

**Example curl**:

```bash
curl -X POST http://localhost:9000/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"eve@example.com","password":"password123"}'
# 201 → { "id": "<uuid>", "email": "eve@example.com" }
```

### Step 2 — User logs in (or hits the dev token endpoint)

There are **two** ways to get a token today; both are real RS256 JWTs signed by the same RSA key.

#### 2a. The standard OAuth2 password grant (production-shaped)

```
   client                   auth-service                       Postgres
     |                            |                                |
     |  POST /oauth2/token        |                                |
     |  -u "api-gateway:gateway-secret-please-rotate"  |
     |  -d "grant_type=password&username=alice@example.com&password=password123" |
     | -------------------------> |                                |
     |                            |  1. Spring AS filter chain     |
     |                            |     (authServerFilterChain,    |
     |                            |      @Order(1))                |
     |                            |  2. JpaRegisteredClientRepo    |
     |                            |     .findByClientId(api-gateway)
     |                            |     -> BCrypt matches secret   |
     |                            |  3. JpaUserDetailsService      |
     |                            |     .loadUserByUsername       |
     |                            |     -> UserAccount + roles     |
     |                            |  4. JwtRolesCustomizer adds    |
     |                            |     roles, user_id, email      |
     |                            |     claims                     |
     |                            |  5. Signs with RSA private key |
     |                            |     (RS256, NimbusJwtEncoder)  |
     |                            |                                |
     | <------------------------- |  200 { access_token, refresh_token, ... }
```

**Source files**:
- `services/auth-service/.../security/AuthServerConfig.java#authServerFilterChain` — `@Order(1)`, matched on `/oauth2/**` and `/.well-known/openid-configuration`.
- `services/auth-service/.../security/JpaRegisteredClientRepository.java` — looks up the OAuth client in `oauth_clients` by `client_id`, matches the BCrypt-hashed `client_secret`.
- `services/auth-service/.../security/JpaUserDetailsService.java` — `findByEmail`, returns a `User` wrapping `UserAccount`.
- `services/auth-service/.../security/JwtRolesCustomizer.java` — adds three custom claims: `roles` (`Set<String>`), `user_id` (the UUID as string), `email`. (Source line 55-57: `context.getClaims().claim("roles", roles); ... claim("user_id", ...); claim("email", ...);`.)
- `application.yml` — `spring.security.oauth2.authorizationserver.grant-types.password: enabled`, plus `access-token.lifetime: PT15M` and `refresh-token.lifetime: P7D`.

**Example curl**:

```bash
curl -X POST http://localhost:9000/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "api-gateway:gateway-secret-please-rotate" \
  -d "grant_type=password&username=alice@example.com&password=password123"
# { "access_token": "eyJ...", "refresh_token": "...",
#   "token_type": "Bearer", "expires_in": 900, "scope": "..." }
```

#### 2b. The dev-token endpoint (workaround for Spring AS 1.3.x)

```
   client                   auth-service
     |                            |
     |  GET /api/auth/dev-token?  |
     |  email=alice@example.com   |
     |  (NO client_id, NO secret) |
     | -------------------------> |
     |                            |  DevTokenController#devToken
     |                            |  - users.findByEmail
     |                            |  - Build JwtClaimsSet with
     |                            |    issuer=http://localhost:9000
     |                            |    sub=<user-uuid>
     |                            |    roles=[CUSTOMER] etc.
     |                            |  - Sign with NimbusJwtEncoder
     |                            |    (same RSA key as Spring AS)
     | <------------------------- |  200 { access_token, ... }
```

**Source file**: `services/auth-service/.../web/DevTokenController.java`. The class is annotated `@Profile({"dev", "default"})` — the bean does not exist in `prod`. The Javadoc on line 39 spells out the reason: *"Spring Authorization Server 1.3.x dropped the resource-owner password grant entirely, but the bible's design depends on it."* It is signed with the **same RSA key** as the AS-issued tokens (the `jwkSource` bean from `AuthServerConfig#jwkSource`), so the gateway treats them identically.

**Example curl**:

```bash
curl "http://localhost:9000/api/auth/dev-token?email=alice@example.com"
# { "access_token": "eyJ...", "token_type": "Bearer",
#   "expires_in": 3600, "scope": "openid profile read write" }
```

> **The dev-token anomaly**: it is `@Profile({"dev", "default"})`, not just `dev`. This means any deployment that does not explicitly set `SPRING_PROFILES_ACTIVE=prod` will still expose it. The same profile gate is on `DevDataSeeder` (line 30). The trade-off: the bible wants the dev experience to "just work" on a fresh `docker compose up` with no env var, so the seeder and the dev-token controller both run by default. See [§6 "What's deliberately not implemented"](./00-glossary.md) for the production-hardening list.

### Step 3 — Client stores the token and sends it on every call

The client keeps the `access_token` in memory (or localStorage on a SPA — the bible does not specify). Every protected request now carries:

```http
GET /api/orders HTTP/1.1
Host: api.samato.example
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IjAxMjM0NTY3LTg5YWItY2RlZi0xMjM0LTEyMzQ1Njc4OTBhYiJ9.<payload>.<signature>
```

A SPA built on the Vite dev server (port 5173) calls the api-gateway (port 8080) — never the auth-service or a downstream service directly. The browser handles the preflight `OPTIONS` request via the gateway's [CORS config](./services/api-gateway.md#33-configcorsconfigjava).

### Step 4 — Request hits the api-gateway

```
   client              api-gateway (WebFlux)                    auth-service JWKS
     |                       |                                          |
     |  GET /api/orders      |                                          |
     |  Authorization:       |                                          |
     |  Bearer eyJ...        |                                          |
     | --------------------> |                                          |
     |                       |  1. CorrelationIdWebFilter:             |
     |                       |     ensure X-Correlation-Id (HIGHEST)   |
     |                       |  2. Spring Security reactive filter:    |
     |                       |     - read Authorization                |
     |                       |     - ReactiveJwtDecoder.decode(token)  |
     |                       |       (NimbusReactiveJwtDecoder,         |
     |                       |        backed by cached JWKS)           |
     |                       |     - on success: SecurityContext has   |
     |                       |       Authentication(JwtAuthentication) |
     |                       |     - on failure: 401                    |
     |                       |  3. JwtAuthFilter (GlobalFilter):       |
     |                       |     - re-validate (defence in depth)    |
     |                       |     - add X-User-Id header (sub)         |
     |                       |     - add X-User-Roles header           |
     |                       |       (comma-joined roles claim)         |
     |                       |  4. Route by path prefix:               |
     |                       |     /api/orders/**                      |
     |                       |     -> lb://SAMATO-ORDER-SERVICE        |
     |                       |     (Eureka lookup, load-balancer)      |
     |                       |  5. Forward to downstream               |
     |                       |     (preserves Authorization,           |
     |                       |      X-User-Id, X-User-Roles,            |
     |                       |      X-Correlation-Id, X-Source)        |
     |                       |                                          |
     |                       |  (At first ever request)                |
     |                       |     GET /.well-known/jwks.json  -------> |
     |                       |     cache public keys, retry on kid miss |
     |                       |     <------- 200 {keys: [...]}           |
```

**Source files**:
- `services/api-gateway/.../config/CorrelationIdWebFilter.java` — `@Order(HIGHEST_PRECEDENCE)`, stamps `X-Correlation-Id` into MDC.
- `services/api-gateway/.../config/JwtConfig.java` — bean factory for `ReactiveJwtDecoder`. Line 33: `NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()`. The `jwkSetUri` value is read from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (every service's `application.yml`).
- `services/api-gateway/.../security/SecurityConfig.java` — `@EnableWebFluxSecurity`, `oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))`, `.pathMatchers("/api/auth/**").permitAll()`, `.anyExchange().authenticated()`. The class Javadoc warns that in WebFlux Spring Security runs as a `WebFilter` **before** the Spring Cloud Gateway filter chain — without this filter chain, the gateway would 401 every protected request.
- `services/api-gateway/.../security/JwtAuthFilter.java` — a `GlobalFilter` (runs after Spring Security, before the route) that **re-validates the JWT** and **injects** `X-User-Id` and `X-User-Roles` (constants `USER_ID_HEADER = "X-User-Id"` and `ROLES_HEADER = "X-User-Roles"` on lines 44-45). On success it mutates the request via `exchange.mutate().request(r -> r.header(...))` and forwards; on failure it writes a 401 JSON body directly to the response. The `onErrorResume(JwtException.class, ...)` on line 87 is what converts a `BadJwtException` (e.g. signature mismatch) into a clean 401 instead of leaking a stack trace.
- `services/api-gateway/.../config/GatewayRoutesConfig.java` — the routing table. `/api/orders/**` → `lb://SAMATO-ORDER-SERVICE`, etc.

### Step 5 — Downstream service receives the request

The downstream service has its **own** `SecurityConfig` (per service) that re-validates the JWT against the same JWKS. This is **defence in depth**: the gateway can be wrong or buggy, the downstream service still says "this token is valid."

```
   api-gateway         order-service                      auth-service JWKS
     |                       |                                     |
     |  forwarded request:   |                                     |
     |  Authorization: Bearer|                                     |
     |  X-User-Id: <uuid>    |                                     |
     |  X-User-Roles:        |                                     |
     |    CUSTOMER           |                                     |
     |  X-Correlation-Id:... |                                     |
     |  X-Source: samato-    |                                     |
     |    api-gateway        |                                     |
     | --------------------> |                                     |
     |                       |  1. CorrelationIdFilter (servlet)   |
     |                       |     reads X-Correlation-Id          |
     |                       |  2. Spring Security servlet filter: |
     |                       |     NimbusJwtDecoder.decode(token)  |
     |                       |     (cached JWKS)                   |
     |                       |  3. JwtAuthenticationConverter      |
     |                       |     - reads "roles" claim           |
     |                       |     - prefixes with "ROLE_"         |
     |                       |     - builds SimpleGrantedAuthority |
     |                       |  4. @PreAuthorize SpEL evaluated    |
     |                       |     ("hasRole('CUSTOMER')")         |
     |                       |  5. Controller method runs          |
     |                       |                                     |
     |                       |  (At first ever request)            |
     |                       |    GET /.well-known/jwks.json ----> |
     |                       |    cache, retry on kid miss         |
     |                       |    <----- 200 {keys: [...]}        |
```

**Source files (using order-service as the example; the pattern is identical in user/restaurant/search/payment-service)**:
- `services/order-service/.../security/SecurityConfig.java` — `@EnableMethodSecurity`, `oauth2ResourceServer().jwt(...)`, `NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()` (line 70-72).
- `services/order-service/src/main/resources/application.yml` — line 33: `jwk-set-uri: ${AUTH_JWKS_URI:http://localhost:9000/.well-known/jwks.json}` (env-overridable for Docker; the override used in compose is `http://auth-service:9000/.well-known/jwks.json`).
- `services/user-service/.../security/SecurityConfig.java#jwtAuthenticationConverter` (lines 70-77) — the converter pattern that makes `hasRole('...')` work:

```java
@Bean
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    var grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
    grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
    return converter;
}
```

- `services/order-service/.../web/OrderController.java` (and every other controller) — `@PreAuthorize("hasRole('CUSTOMER')")` etc. on each handler.

The same `SecurityConfig` shape is copy-pasted across `user-service`, `restaurant-service`, `search-service`, `order-service`, `payment-service`. Each fetches its JWKS from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (the api-gateway does the same — see [Step 4](#step-4--request-hits-the-api-gateway)).

> **The gateway is the only place that re-injects the user into headers as `X-User-Id`/`X-User-Roles`.** Downstream services *can* read these (and many do for logging), but they do **not** rely on them for authz — they re-validate the JWT and run `@PreAuthorize` against the freshly-extracted authorities. This is what "defence in depth" means in practice: the headers are a convenience for the gateway's *own* downstream logging, not a trust shortcut.

### Step 6 — Service-to-service calls (the gap)

For service-to-service calls (e.g. `order-service` → `restaurant-service` via OpenFeign), the bible **does not** mint a separate `client_credentials` token today. The `FeignAuthForwarder` (in `order-service`) simply **forwards the original user's bearer token** on the outbound call. The downstream service then re-validates it.

```
   order-service           restaurant-service
     |                            |
     |  Feign GET /api/restaurants/{id}
     |  Authorization: Bearer <user-jwt>   (forwarded)
     |  X-Correlation-Id: <id>             (FeignCorrelationIdInterceptor)
     |  X-Trace-Id / X-Span-Id            (FeignCorrelationIdInterceptor)
     | --------------------------->        |
     |                            |  Re-validates JWT against JWKS
     |                            |  (same auth-service public key)
     | <------------------------- |
```

**The OAuth2 `client_credentials` grant is enabled in `application.yml`** (`spring.security.oauth2.authorizationserver.grant-types.client_credentials: enabled`) and the `OAuthClient` table has rows for `api-gateway` and `spa-client`. But **no Feign client in the codebase currently exchanges a client-credentials token**. The `@FeignClient AuthClient` in user-service (intended for a Phase 7 "ask auth-service who this user is" pattern) is fully wired with a fallback (`AuthClientFallback`) and Resilience4j config — but no controller injects it. See [user-service.md §7.1](./services/user-service.md#71-authclient-is-defined-but-not-injected-anywhere) for the full story.

**This is a documented gap**, not a bug. The current "forward the user's token" approach is correct when the downstream service is acting **on behalf of that user** (e.g. "place an order, then call payment-service to charge this customer's card"). A separate client-credentials token would be needed only when the downstream service is acting **as itself** (e.g. an analytics service that scrapes aggregate data, not user-scoped data). The bible has no such service today.

---

## 4. The JWT structure

A real RS256 JWT from a `DevTokenController` call (anonymised). The three parts are base64url-encoded; the dots are the separators.

```
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IjAxMjM0NTY3LTg5YWItY2RlZi0xMjM0LTEyMzQ1Njc4OTBhYiJ9
  . <payload>
  . <signature>
```

### The header (decoded)

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "01234567-89ab-cdef-1234-1234567890ab"
}
```

| Field | Meaning |
|---|---|
| `alg: "RS256"` | RSA + SHA-256. Asymmetric — issuer signs with a private key, verifiers check with the public key from the JWKS. |
| `typ: "JWT"` | Token type. Always `JWT` here. |
| `kid` | "Key ID" — the UUID of the specific RSA key. A JWKS can publish multiple keys (for rotation); verifiers pick the right one by `kid`. Samato today publishes one key (generated fresh at startup — see [§11 "What's deliberately not implemented"](#11-whats-deliberately-not-implemented)). |

### The payload (decoded)

```json
{
  "iss": "http://localhost:9000",
  "sub": "11111111-1111-1111-1111-111111111111",
  "aud": ["samato-api-gateway"],
  "iat": 1721040000,
  "exp": 1721040900,
  "scope": "openid profile read write",
  "roles": ["CUSTOMER"],
  "user_id": "11111111-1111-1111-1111-111111111111",
  "email": "alice@example.com"
}
```

| Claim | Source | Meaning |
|---|---|---|
| `iss` | `AuthorizationServerSettings.issuer(...)` in `AuthServerConfig` AND `JwtClaimsSet.builder().issuer("http://localhost:9000")` in `DevTokenController`. **Both must agree**, or downstream services reject the token (`InvalidIssuerException`). | The URL of the authorization server. Verifiers check this matches the issuer they expect. |
| `sub` | `user.getId().toString()` — the user's UUID. | The "subject" — who the token is about. Every controller in the bible reads `jwt.getSubject()` to get the caller. |
| `aud` | `audience(List.of("samato-api-gateway"))` (dev-token; framework default for AS-issued tokens is the client id). | The intended audience. Spring Security validates this if configured; the bible does not. |
| `iat` | `Instant.now()` at issuance. | Issued-at (epoch seconds). |
| `exp` | AS-issued: `access-token.lifetime: PT15M` (15 min). Dev-token: `TOKEN_TTL_MINUTES = 60` (1 hour, hard-coded on line 61 of `DevTokenController`). | Expiry (epoch seconds). After this, the token is rejected. |
| `scope` | The OAuth2 scopes the user (and client) asked for. | Space-separated string in the standard; turned into `SCOPE_` authorities by the default `JwtGrantedAuthoritiesConverter` (not used in `@PreAuthorize` today). |
| `roles` | **Custom claim**, added by `JwtRolesCustomizer` (line 55). | The user's roles as a `Set<String>`, e.g. `["CUSTOMER"]`. After `JwtAuthenticationConverter` prefixes with `ROLE_`, the authority is `ROLE_CUSTOMER`. |
| `user_id` | **Custom claim**, added by `JwtRolesCustomizer` (line 56). | The user's UUID. Redundant with `sub` but spares a `String → UUID` conversion. The api-gateway's `JwtAuthFilter` reads this claim (line 75-76) and copies it into the `X-User-Id` header. |
| `email` | **Custom claim**, added by `JwtRolesCustomizer` (line 57). | The user's email. PII — be careful what you log. |

### The signature (conceptual)

The signature is `RSA-SHA256(base64url(header) + "." + base64url(payload), privateKey)`. To verify, a resource server:

1. Decodes the header → reads `alg: "RS256"` and `kid: "<uuid>"`.
2. Looks up the public key with matching `kid` in its cached JWKS.
3. Re-computes the signature with that public key and the same `header.payload` string.
4. Compares the two signatures byte-for-byte. If they differ → reject.

Because the private key lives only in `auth-service`, **no other service can produce a valid signature** — even if a malicious service is fully compromised, it can only read tokens, not write them. This is the whole point of [RS256](./00-glossary.md#rs256) over [HS256](./00-glossary.md#hmac--hmac-sha256): asymmetric.

---

## 5. The JWKS endpoint

[What JWKS is](./00-glossary.md#jwk--jwks) in one sentence: a JSON document that an authorization server publishes, listing every public key it currently uses to sign tokens. Resource servers fetch the document once and cache it.

### The response shape

`GET http://localhost:9000/.well-known/jwks.json` returns:

```json
{
  "keys": [
    {
      "kty": "RSA",
      "kid": "01234567-89ab-cdef-1234-1234567890ab",
      "use": "sig",
      "alg": "RS256",
      "n": "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86z",
      "e": "AQAB"
    }
  ]
}
```

| JWK field | Meaning |
|---|---|
| `kty: "RSA"` | Key type — an RSA key. |
| `kid` | Key ID. Matches the `kid` in the JWT header. |
| `use: "sig"` | Key usage — "signature". Tells the verifier this key is for verifying, not encrypting. |
| `alg: "RS256"` | The signing algorithm this key is intended for. |
| `n` | The RSA modulus (base64url-encoded big-endian integer). Together with `e` it forms the public key. |
| `e` | The RSA public exponent (`AQAB` = 65537, the standard value). |

**The public key is exposed, the private key is not.** `n` and `e` are enough to **verify** a signature (multiply two large primes together to make `n`; given `e` and `n`, anyone can check `signature^e mod n == padded-hash`). They are not enough to **forge** a signature — that requires the private key (`d`), which only `auth-service` holds.

**The `kid` is how rotation works.** When a JWKS has more than one key, the `kid` in the JWT header tells the verifier which one to use. Today Samato publishes one key; Phase 8 hardening adds multi-key rotation (the Javadoc on `AuthServerConfig#jwkSource` says exactly this).

### How the caching works (every resource server)

`NimbusJwtDecoder.withJwkSetUri(uri).build()` (used by every service) does the following under the hood:

1. **On first call to `.decode(token)`** — fetch the JWKS over HTTP, parse it into `JWK` objects, build an internal `Map<kid, JWK>` for O(1) lookup.
2. **For each subsequent call** — read the `kid` from the token header, look it up in the map, verify the signature. No HTTP call.
3. **On `kid` miss** (the cache doesn't have that key) — refetch the JWKS once, refresh the map, retry. This is how key rotation is handled with no service restart.

In the api-gateway's reactive stack, the same logic lives in `NimbusReactiveJwtDecoder` — the api-gateway is the only reactive resource server in the bible (see [api-gateway.md](./services/api-gateway.md)).

### Why the gateway validates first AND downstream services re-validate

**The gateway validates first** to do the early-reject: an unauthenticated request never touches a downstream service. It also injects the `X-User-Id` and `X-User-Roles` headers for log correlation.

**Downstream services re-validate** so that a bug or compromise at the gateway does not propagate. A request that bypasses the gateway (e.g. a test client that calls `http://order-service:8083/api/orders/...` directly inside the Docker network) is still protected. This is the textbook "**defence in depth**" trade-off: a small per-service CPU cost for the guarantee that no single layer's failure opens the system.

> The `JwtAuthFilter` Javadoc (line 38) puts it bluntly: *"In a paranoid setup, downstream services ALSO validate the JWT themselves — defense in depth. We do this in user-service."* — and the same code is in fact wired into every service's `SecurityConfig`.

---

## 6. The two JWKS endpoints (the anomaly)

This is the **first of the four load-bearing anomalies** the inventory surfaced. Read it carefully.

### What is on the wire

`auth-service` publishes the **same JWKSet** at **two URLs**:

| URL | Owner | When it's served |
|---|---|---|
| `/oauth2/jwks` | Spring Authorization Server framework (the `authServerFilterChain` at `@Order(1)`) | Always, as part of the AS's default endpoints. |
| `/.well-known/jwks.json` | Custom `JwksController` (`@RequestMapping("/.well-known")`) | Always, served by a hand-written controller. |

Both return identical JSON: `{ "keys": [ { kty, kid, use, alg, n, e }, ... ] }`. The `JwksController` is explicit about this on line 33-37 of its Javadoc: *"The set of keys published here is exactly the same as what `/oauth2/jwks` returns. The two are interchangeable. The .well-known path is the OIDC-canonical one; the /oauth2 path is Spring AS's default. We support both."*

### Which one does the rest of the system actually use?

Every service's `application.yml` is configured to fetch from the **custom one** (`/.well-known/jwks.json`):

| Service | `application.yml` line | URL |
|---|---|---|
| `api-gateway` | (via `JwtConfig` and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` env) | `http://auth-service:9000/.well-known/jwks.json` |
| `user-service` | `application.yml:28` | `http://localhost:9000/.well-known/jwks.json` |
| `restaurant-service` | `application.yml:28` | `http://localhost:9000/.well-known/jwks.json` |
| `search-service` | `application.yml:15` | `http://localhost:9000/.well-known/jwks.json` |
| `order-service` | `application.yml:33` | `${AUTH_JWKS_URI:http://localhost:9000/.well-known/jwks.json}` |
| `payment-service` | `application.yml:33` | `${AUTH_JWKS_URI:http://localhost:9000/.well-known/jwks.json}` |
| `config-repo/samato-order-service.yml:23` | (override) | `http://samato-auth-service:9000/.well-known/jwks.json` |
| `config-repo/samato-payment-service.yml:22` | (override) | `http://samato-auth-service:9000/.well-known/jwks.json` |

**The framework's `/oauth2/jwks` is not used by any service.** It exists because Spring AS ships it, but the bible standardises on the OIDC-canonical `/.well-known/jwks.json` path.

### Why the custom controller exists

The `JwksController` Javadoc explains (line 26-31):

> *"The bible's service configs use `/.well-known/jwks.json` as the JWKS URL (the canonical OIDC location). Spring AS's default filter chain (authServerFilterChain, order 1) handles the actual `/oauth2/jwks` but does NOT cover `/.well-known/**`. The default security chain (order 2) requires authentication by default, so the .well-known path returns 403 — which is why every downstream service has been failing JWT verification."*

In other words: if you remove `JwksController`, the `.well-known/jwks.json` path falls through to the **default security chain** (which calls `anyRequest().authenticated()` on line 105 of `AuthServerConfig`), and every downstream service's `NimbusJwtDecoder` fetches a **401** instead of the JWKSet. **The custom controller is load-bearing.**

### Trade-off

Two endpoints means two attack surfaces. The risk is tiny (a JWKS is public by design), but it does mean a change to the JWKSet must be reflected in both places. In practice this is a non-issue because both endpoints read from the same `JWKSource<SecurityContext>` bean (`AuthServerConfig#jwkSource`) — so there is only one source of truth in the JVM.

---

## 7. The two security filter chains (the anomaly)

The inventory flagged that two services have **two** Spring Security filter chains configured. This is by design but easy to misorder.

### The api-gateway has the dev-token allow-list chain (anomaly 1a)

Wait — re-reading the source, the api-gateway actually has the **same pattern as every other service**: one `SecurityWebFilterChain` (`@Bean springSecurityFilterChain` in `SecurityConfig.java`) plus a `GlobalFilter` (`JwtAuthFilter`). That is **not** two security filter chains in Spring's terms — the `GlobalFilter` is part of the gateway's filter chain, not a separate Spring Security chain. The inventory wording was a slight over-simplification.

What the api-gateway **does** have is two layers:

| Layer | Class | Order | Purpose |
|---|---|---|---|
| Spring Security reactive filter | `SecurityConfig#springSecurityFilterChain` | runs first (WebFilter) | Validates JWT, returns 401 on failure. |
| Spring Cloud Gateway GlobalFilter | `JwtAuthFilter` | `HIGHEST_PRECEDENCE + 10` (line 106) | Re-validates, injects `X-User-Id` / `X-User-Roles` headers. |

Both are necessary. The `SecurityConfig` Javadoc (line 28-31) explains: *"In WebFlux, Spring Security installs itself as a `WebFilter` that runs BEFORE the Spring Cloud Gateway filter chain. A `GlobalFilter` like `JwtAuthFilter` runs AFTER — so without wiring JWT validation into Spring Security here, every request to a protected route is rejected with an empty 401 before the gateway even sees it. The previous version of this class delegated JWT validation entirely to `JwtAuthFilter`, which is unreachable in practice."*

If you reorder (e.g. move the JWT validation logic out of `SecurityConfig` and rely only on `JwtAuthFilter`), every protected request 401s. This is exactly the bug the class Javadoc warns about.

### payment-service has the JWT chain + the HMAC chain (anomaly 1b)

`payment-service` genuinely **has two Spring Security filter chains**, because Razorpay webhooks are signed with [HMAC](./00-glossary.md#hmac--hmac-sha256), not JWT. Both chains live in the `security` package:

| Filter chain | File | Order | Matcher | Purpose |
|---|---|---|---|---|
| `webhookFilterChain` | `RazorpayWebhookSecurityConfig.java` | `Ordered.HIGHEST_PRECEDENCE` (line 29) | `/api/payments/webhooks/**` | Disables CSRF, permits all on the matcher, lets the controller verify the `X-Razorpay-Signature` HMAC itself. |
| `filterChain` (default) | `SecurityConfig.java` | (default order, lower) | everything else | Standard JWT validation, `@EnableMethodSecurity`, `@PreAuthorize` honoured. |

```
   Razorpay's server         payment-service
     |                            |
     |  POST /api/payments/       |
     |    webhooks/razorpay       |
     |  X-Razorpay-Signature:     |
     |    <hmac-sha256-hex>       |
     |  body: { ... }             |
     | -------------------------> |
     |                            |  webhookFilterChain (@Order HIGHEST_PRECEDENCE)
     |                            |  matches /api/payments/webhooks/**
     |                            |  permitAll(); csrf().disable()
     |                            |  -> controller verifies HMAC
     |                            |     (X-Razorpay-Signature ==
     |                            |      HMAC-SHA256(secret, body))
     |                            |  -> 200 if match, 401 if not
     | <------------------------- |
```

The class Javadoc on `RazorpayWebhookSecurityConfig` (line 20-22) spells it out: *"This bean is annotated `@Order(1)` so it runs BEFORE the default JWT chain. The default chain's matcher doesn't include the webhook path, so it never sees webhook requests."*

**What happens if you reorder them.** If you swap the `@Order` values — the JWT chain runs first, matches everything (including the webhook path), and rejects the request because it has no `Authorization: Bearer` header. **Razorpay's webhooks get 401'd**. The bug is silent in unit tests but catastrophic in prod: every payment notification is dropped.

**Why HMAC instead of JWT here?** Razorpay (the gateway) is a third-party. You can't give them an account in your `oauth_clients` table. The only credential both sides can share is a pre-shared secret — exactly what HMAC is for. The trust anchor for the webhook is "Razorpay knows the secret," not "Razorpay has a private key."

---

## 8. Roles vs scopes vs authorities

These three terms look interchangeable. They are not.

### The three concepts

| Concept | What it is | Spring type | Samato example |
|---|---|---|---|
| **Authority** | A single "permission" string. | `GrantedAuthority` (interface). | `"ROLE_DRIVER"`, `"SCOPE_orders:write"`. |
| **Role** | An authority with the `ROLE_` prefix. | `SimpleGrantedAuthority("ROLE_DRIVER")`. | `"ROLE_DRIVER"`. |
| **Scope** | An OAuth2 permission string from the JWT's `scope` claim. | `SimpleGrantedAuthority("SCOPE_orders:write")`. | `"SCOPE_orders:write"`. |

The `@PreAuthorize` SpEL functions work differently on each:

| SpEL | Matches |
|---|---|
| `hasRole('DRIVER')` | An authority `ROLE_DRIVER`. **Adds the `ROLE_` prefix for you.** |
| `hasAuthority('ROLE_DRIVER')` | An authority `ROLE_DRIVER` exactly. |
| `hasAuthority('SCOPE_orders:write')` | An authority `SCOPE_orders:write` exactly. |
| `hasAnyRole('CUSTOMER','ADMIN')` | Either `ROLE_CUSTOMER` or `ROLE_ADMIN`. |

### How the conversion happens

Spring's `JwtAuthenticationConverter` (configured in every `SecurityConfig`) reads the JWT and turns the claims into `GrantedAuthority` objects. Samato customises the converter so that the **`roles` claim** is the source, not the default `scope`:

```java
// user-service/SecurityConfig.java (lines 70-77)
@Bean
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    var grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");     // <- custom claim
    grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");        // <- prefix added
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
    return converter;
}
```

So a JWT with `"roles": ["DRIVER", "CUSTOMER"]` becomes two authorities: `ROLE_DRIVER` and `ROLE_CUSTOMER`. `@PreAuthorize("hasRole('DRIVER')")` then matches.

If you used the default converter (no customisation), the JWT's `scope` claim would be turned into `SCOPE_xxx` authorities and `hasRole('DRIVER')` would fail (no `ROLE_DRIVER` to find). The bible's customisation is the load-bearing piece of every controller's authz.

### The four roles

Defined in `services/auth-service/src/main/java/com/samato/authservice/domain/Role.java` (an enum) and **deliberately duplicated** in `services/user-service/src/main/java/com/samato/userservice/domain/Role.java`. The duplication is documented in the user-service `Role` Javadoc: *"if the role set grows in one and not the other, this won't compile, which is a feature not a bug — it forces the conversation."*

| Role | Use case | Example controller |
|---|---|---|
| `CUSTOMER` | Default for new sign-ups (`RegistrationController` always assigns `CUSTOMER`). | `OrderController.place` requires `hasRole('CUSTOMER')`. |
| `RESTAURANT_OWNER` | Restaurant owners; gates `/api/users/restaurant-owners/**` and (eventually) the owner-side of `restaurant-service`. | `RestaurantOwnerProfileController` requires `hasRole('RESTAURANT_OWNER')`. |
| `DRIVER` | Delivery drivers; gates `/api/users/drivers/**`. | `DriverProfileController` requires `hasRole('DRIVER')`. |
| `ADMIN` | Back-office / super-user. | `CustomerProfileController.getById` allows `hasRole('ADMIN') or authentication.name == #id.toString()`. |

### Where scopes fit (and why the bible doesn't use them much)

The OAuth2 `scope` claim is the canonical way to express fine-grained permissions ("this token can write orders, but only read restaurants"). The bible parses scopes into `SCOPE_*` authorities via the default `JwtGrantedAuthoritiesConverter` behavior (it runs alongside the custom roles converter, both contribute to the final `Authentication.authorities` list), but no `@PreAuthorize` expression in the codebase uses `hasAuthority('SCOPE_...')` today. The roles system is the primary authz mechanism; scopes are wired in for forward compatibility. To use one:

```java
@PreAuthorize("hasAuthority('SCOPE_orders.write')")
public OrderResponse placeOrder(...) { ... }
```

This would also require adding `"orders.write"` to the scope in the `DevTokenController` `claims.claim("scope", ...)` line (currently `"openid profile read write"`) and configuring the scope on the OAuth client.

---

## 9. Service-to-service tokens (the Phase 7 gap)

The OAuth2 `client_credentials` grant is enabled (`spring.security.oauth2.authorizationserver.grant-types.client_credentials: enabled` in `application.yml`) and the `oauth_clients` table has rows. The `OAuthClient` table is populated by `DevDataSeeder` with `api-gateway` and `spa-client` (each with a hashed secret and a redirect URI). But **no Feign client in the codebase currently exchanges a client-credentials token**. Today, every service-to-service call (via OpenFeign) **forwards the end-user's bearer token** instead of minting a new machine-to-machine one.

### What the bible has wired (but not used)

In `services/user-service/.../client/AuthClient.java`:

```java
@FeignClient(name = "samato-auth-service", fallback = AuthClientFallback.class)
public interface AuthClient {
    @GetMapping("/api/auth/me")
    AuthMeResponse me(@RequestHeader("Authorization") String bearer);
}
```

This is a fully-wired OpenFeign client with:
- a `name` that resolves through Eureka (`samato-auth-service` → host:port).
- a fallback class (`AuthClientFallback`) that throws `DomainException("AUTH_UNREACHABLE", ..., 503)` on failure.
- Resilience4j config in `application.yml` (circuit-breaker sliding-window 10, failure-rate 50%, wait 10s; retry 3x with exponential backoff starting at 500ms; time limiter 3s).
- Feign timeouts in `application.yml` (connect 2000ms, read 5000ms).

**But no controller injects `AuthClient`.** A grep for `AuthClient` finds zero call sites. The class Javadoc says: *"The wiring exists (Feign client, fallback, Resilience4j config in `application.yml`) but no controller or service injects `AuthClient` today. The client is in place so Phase 7 can flip the 'user existence check' from 'trust the JWT subject' to 'ask auth-service via Feign.'"*

### What "client_credentials" would look like (when implemented)

When Phase 7 adds a service that needs to act as itself (not on behalf of a user), the pattern will be:

```java
// Pseudocode for a future ClientCredentialsTokenService
public String getClientToken() {
    return webClient.post()
        .uri("http://samato-auth-service:9000/oauth2/token")
        .header(HttpHeaders.AUTHORIZATION,
                "Basic " + base64("client-id:client-secret"))
        .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
        .retrieve()
        .bodyToMono(TokenResponse.class)
        .map(TokenResponse::getAccessToken)
        .cache();   // <-- cache until near expiry
}
```

Then a Feign client would inject the token via a `RequestInterceptor`. The bible has not done this yet because no current service needs it.

### Why the gap is acceptable today

Today's service-to-service calls are all **user-scoped**: the order-service calls restaurant-service to validate an order for a specific customer; it calls payment-service to charge that customer. The end-user's token is the right credential to forward — it conveys "act on behalf of alice@example.com" and every downstream service already has the JWKS to verify it. A separate client-credentials token would be needed for **machine-scoped** work (e.g. an analytics service aggregating across all orders), which the bible does not have.

---

## 10. CORS + auth

[CORS](./00-glossary.md#cors-cross-origin-resource-sharing) is the browser-side rule for whether JS on `app.samato.com` can call `api.samato.com`. The server returns `Access-Control-Allow-*` headers saying yes or no.

### Why CORS is on the gateway and not on the services

Every browser request hits the api-gateway first. The downstream services are reachable only through the gateway (they don't need to know about CORS — there's no scenario where a browser talks to them directly). Centralising CORS in one place means:

- One config to change when adding a new origin.
- One place to enforce the `allowedOriginPatterns: "*"` dev rule (and tighten in prod).
- No per-service `CorsConfig` duplication.

`services/api-gateway/.../config/CorsConfig.java` registers a `CorsWebFilter` bean with `allowedOriginPatterns: "*"`, `allowedMethods: "*"`, `allowedHeaders: "*"`, `exposedHeaders: "X-Correlation-Id"`. The `application.yml` has a parallel `spring.cloud.gateway.globalcors` block; both apply (the bean is more explicit and easier to read in code review).

### The preflight `OPTIONS` request

A SPA on `http://localhost:5173` calls `POST http://localhost:8080/api/orders` with a custom `Authorization` header. The browser, not the SPA, sends an `OPTIONS` first to check the server's policy:

```http
OPTIONS /api/orders HTTP/1.1
Origin: http://localhost:5173
Access-Control-Request-Method: POST
Access-Control-Request-Headers: Authorization, Content-Type
```

The gateway's CORS filter must respond with:

```http
HTTP/1.1 200 OK
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: POST
Access-Control-Allow-Headers: Authorization, Content-Type
Access-Control-Allow-Credentials: true
```

`SecurityConfig` (the gateway) handles this correctly because `pathMatchers("/api/auth/**").permitAll()` and the OAuth2 resource server config don't interfere with the preflight — the `CorsWebFilter` runs before the security chain. (`POSTMAN-PREFLIGHT-TROUBLESHOOTING.md` has more on debugging failed preflights.)

### How the JWT is sent on cross-origin requests

In Samato, the JWT is sent in the **`Authorization: Bearer ...` header** on every cross-origin call. This is the simplest model and is the right choice for a stateless API:

- **No cookies** — cookies require `Access-Control-Allow-Credentials: true` and `withCredentials: true` on the fetch, and they invite CSRF. The bible has CSRF off (the `defaultSecurityFilterChain` in auth-service and the equivalent in every service disables it) and uses `SessionCreationPolicy.STATELESS` — there is no session to put a cookie on.
- **No localStorage for SSR** — if you ever need server-side rendering with auth, you'll switch to a BFF pattern (gateway holds the token, browser holds an opaque session cookie). Not needed today.
- **`Authorization` header is "simple"** — actually, it's not — `Authorization` is **not** on the CORS "simple header" list, which is why preflight is required for the first cross-origin call. After the preflight succeeds, the browser caches the policy and subsequent calls don't re-preflight.

For dev, the `allowedOriginPatterns: "*"` is a dev concession — the comment in `CorsConfig` flags it. In prod, replace `"*"` with the specific origin(s) (e.g. `https://app.samato.com`).

---

## 11. What's deliberately not implemented

These are the pieces a real production system would have but the bible leaves out. The list is also captured in [00-glossary.md](./00-glossary.md#auth) and [ARCHITECTURE.md](../ARCHITECTURE.md).

| Feature | Status | Where it would slot in |
|---|---|---|
| **Token revocation** | Not implemented. A token is valid until `exp`. There is no blacklist. | A new table `revoked_tokens(jti, exp)` consulted on every request; or short-lived access tokens + a revocation list maintained by `auth-service`. |
| **Refresh token rotation** | Refresh tokens are issued (7-day lifetime) but not rotated — a stolen refresh token can be used until expiry. | Each `/oauth2/token` call with `grant_type=refresh_token` would also issue a new refresh token and revoke the old one. The Javadoc on `JwtRolesCustomizer` hints at this for Phase 8. |
| **Multi-factor auth** | Not implemented. A password is the only factor. | Add a TOTP step to `/oauth2/token` (or replace the password grant with an authorization-code + PKCE flow that includes MFA). |
| **Password reset** | Not implemented. There is no `forgot password` endpoint. | New `POST /api/auth/forgot-password` (generates a token, emails it) and `POST /api/auth/reset-password` (validates token, updates hash). |
| **Account lockout** | Not implemented. There is no rate limit on `/api/auth/register` or `/oauth2/token`. | Add Resilience4j rate-limiter to the password-grant endpoint; lock the account after N failures. |
| **Key rotation** | RSA keypair is generated fresh on every `auth-service` start. **All in-flight tokens become unverifiable after a restart.** | Load the key from KMS/Vault and pin a `kid`; on rotation, publish both old + new keys in the JWKS until the old one's `exp` passes. The `AuthServerConfig#jwkSource` Javadoc spells this out. |
| **CSRF** | Disabled in every service (correct for a stateless JWT API). If you ever add a non-JSON endpoint, you must enable it. | Per-service `SecurityConfig` `.csrf(...)` block; would need a `CookieCsrfTokenRepository` + a `CsrfTokenRequestAttributeHandler`. |
| **Aud validation** | Tokens carry `aud: ["samato-api-gateway"]` but no service validates the audience. | Configure `JwtDecoder` with a `OAuth2TokenValidator<Jwt>` that checks `jwt.getAudience().contains(...)`. |

> **The key-rotation item is the most operationally dangerous.** Today, a single `docker compose restart auth-service` invalidates **every** access token (the new RSA key has a new `kid`, the cached JWKS in every service still has the old `kid`, and `NimbusJwtDecoder` will see the mismatch and 401 every protected request). Phase 8 hardening must address this before any production deployment.

---

## 12. Common beginner questions

### "Why RS256 and not HS256?"

[RS256](./00-glossary.md#rs256) is **asymmetric**: the issuer signs with a **private** key, verifiers check with a **public** key. The private key only exists in `auth-service`; every other service holds only the public half (downloaded from the JWKS). The blast radius of a compromised downstream service is small — the attacker can verify tokens but cannot forge them.

[HS256 (HMAC-SHA256)](./00-glossary.md#hmac--hmac-sha256) is **symmetric**: the same secret signs and verifies. Every service that wants to verify a token must also hold the signing secret. **Every service becomes a potential leak point.** The bible uses RS256 precisely to avoid that.

The `AuthServerConfig` Javadoc (line 39-46) puts it as an interview Q&A: *"Q: 'Why RS256 not HS256?' A: Asymmetric. The auth-service signs with a private key; other services verify with the public key. They NEVER need the signing key, so the blast radius of a compromised service is much smaller."*

### "Why doesn't the gateway just forward the Authorization header?"

It **does** forward the Authorization header. It also **validates** the token first (because the WebFlux security chain runs before any GlobalFilter, and an unvalidated token must be rejected at the edge to avoid waste), and it **injects** `X-User-Id` and `X-User-Roles` headers for downstream logging convenience.

The headers are **not** used for authz by downstream services. Every downstream service re-validates the JWT via its own `NimbusJwtDecoder`. The `X-User-Id` / `X-User-Roles` headers are a log-correlation convenience, not a trust shortcut. The `JwtAuthFilter` Javadoc (line 38) is explicit: *"Downstream services don't have to parse the JWT themselves; they read the headers and trust them because the gateway has already validated the signature. (In a paranoid setup, downstream services ALSO validate the JWT themselves — defense in depth. We do this in user-service.)"*

### "What if auth-service is down?"

Three scenarios:

1. **A user is already logged in (has a valid access token).** The token is still valid. The api-gateway and every downstream service validate the token **locally** against the cached JWKS — no call to auth-service. The request succeeds. Token validation continues to work for up to 15 minutes (the access-token lifetime) after auth-service goes down.
2. **A user tries to log in.** `POST /oauth2/token` requires auth-service to be up (it has to verify the password and sign the token). The login fails. The user sees a 5xx.
3. **A key rotation happens while auth-service is down.** New tokens cannot be issued. But the existing keys are still cached everywhere, so old tokens still verify.

This is one of the big wins of the JWKS design: **identity verification is decoupled from identity issuance.**

### "What if the JWKS endpoint is down at startup?"

`NimbusJwtDecoder.withJwkSetUri(...).build()` does **not** fetch the JWKS at construction time. It fetches lazily on the first call to `.decode(token)`. So:

- If auth-service is down when a downstream service starts, the downstream service **still starts**. The first request to a protected endpoint triggers the JWKS fetch, which will fail with a `JwkFetchException` → the request 401s.
- Subsequent requests also 401 (the cache is empty, the fetch keeps failing) until auth-service comes back.
- Once auth-service is back, the next request triggers a successful fetch and everything works. No service restart needed.

If the JWKS endpoint is up at startup but you flip the key, `NimbusJwtDecoder` refetches on the next `kid` miss — see [`JwtConfig` Javadoc line 24-25](./services/api-gateway.md).

### "What if the RSA key is regenerated?"

**Every in-flight token becomes unverifiable.** The new RSA key has a new `kid`. The `kid` in the token header still points to the old key. The cached JWKS still has only the old key (until the next refetch). The decoder's `kid` lookup misses, it refetches the JWKS, gets the new key, but the token's signature was made with the old key. **Signature mismatch → 401.**

Recovery: every user has to log in again (and get a token signed by the new key). For a 15-minute access token lifetime, this is a "wait 15 minutes and log in again" outage, not a permanent one. For a 7-day refresh token, the user can hit `/oauth2/token` with `grant_type=refresh_token` and get a new access token (the refresh token doesn't carry the `kid` — it just needs to match the user's session in `auth-service`'s in-memory state, which is rebuilt from the `oauth_clients` table).

This is the "key rotation" item in [§11](#11-whats-deliberately-not-implemented) and the reason the `AuthServerConfig#jwkSource` Javadoc warns against this in production.

### "Can I use a different IdP (Okta, Auth0, Keycloak)?"

**Yes.** Every service is configured via a single key: `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`. Point that URL at the new IdP's JWKS endpoint (e.g. `https://your-tenant.okta.com/oauth2/v1/keys`), make sure the new IdP issues tokens with the same claim names (`sub`, `roles`, `user_id`, `email` — see the [§4 payload table](#the-payload-decoded)), and the system works without any Java code changes.

The changes you do need to make:

1. **Disable `auth-service`** in `docker-compose.yml` (or replace it with a deploy of your IdP). The login UI now hits the IdP, not `/api/auth/login`.
2. **Replace the dev-token controller** — `DevTokenController` issues tokens with the same RSA key as Spring AS. If your IdP is Okta, you can't use this. Use a real OAuth2 flow.
3. **Make sure the JWT claims match.** Okta's default `groups` claim is not `roles`. Map it: either customise `JwtAuthenticationConverter` to read `groups`, or add a token-claim mapping in the IdP. The user-service converter (line 70-77 of `SecurityConfig.java`) is the template.
4. **Update the issuer URL.** `AuthorizationServerSettings.issuer(...)` in `AuthServerConfig` is hard-coded to `http://localhost:9000`. If the issuer changes, every token issued by the new IdP will fail `iss` validation. Make this env-driven (the Javadoc on `AuthServerConfig#authorizationServerSettings` calls this out as a follow-up).
5. **Delete the `users` / `user_roles` / `oauth_clients` tables** if you move the profile store to the IdP too — or keep them and just don't use them, if you want to keep the `auth-service` for profile data only.

The fact that **every service reads its JWKS URL from one config key** and **validates locally** is what makes this swap possible. The cost of a centralised auth model is the ability to swap out the IdP in a day.

---

## 13. See also

- [use-cases/02-auth-flow.md](./use-cases/02-auth-flow.md) — end-to-end walkthrough: register → login → call a protected endpoint. The hands-on counterpart to this doc.
- [services/auth-service.md](./services/auth-service.md) — full per-service walkthrough: every controller, the JWKS controller, the dev-token controller, the security config, the `JwtRolesCustomizer`, the Flyway migration.
- [services/api-gateway.md](./services/api-gateway.md) — `JwtConfig`, `JwtAuthFilter`, `SecurityConfig`, the route table, the CORS config.
- [services/user-service.md](./services/user-service.md) — the resource-server pattern (`SecurityConfig` + `JwtAuthenticationConverter`), the `AuthClient` Feign client wired but not injected, the three profile controllers using `@PreAuthorize`.
- [01-architecture-guide.md](./01-architecture-guide.md) — the system map. The "Authentication" subsection of §7 is the one-paragraph version of this doc.
- [00-glossary.md](./00-glossary.md) — every term used here is defined there. Keep it open in a tab.
- [services/shared-and-kafka.md](./services/shared-and-kafka.md) — `MdcKeys` (the four MDC keys the log pattern uses), `CorrelationIdFilter` (the servlet filter that stamps `X-Correlation-Id`), `GlobalExceptionHandler` (the JSON error envelope).
- [INTERVIEW-CHEATSHEET.md](./INTERVIEW-CHEATSHEET.md) — quick-reference answers to common microservice interview questions, including the auth ones.

Repo-level:

- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the ADR list (especially [ADR-11](../ARCHITECTURE.md#key-architectural-decisions-adrs): "Self-hosted Spring Authorization Server").
- [../RUN-THE-BIBLE.md](../RUN-THE-BIBLE.md) — how to bring the 18-container stack up locally.
