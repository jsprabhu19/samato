# auth-service (port 9000)

> Plain-English purpose: the auth-service is the **trust anchor** of the Samato platform — the one service every other service trusts to answer "who is this caller and what are they allowed to do?" It runs the **Spring Authorization Server** (an OAuth2 / OIDC issuer), stores user passwords (BCrypt-hashed) and roles, mints **RS256-signed JWTs**, and publishes the public half of its signing key as a **JWKS** so every other service can verify tokens locally without phoning home. It demonstrates the **OAuth2 / OIDC**, **JWT (asymmetric signing)**, and **stateless resource-server** patterns; in production you would more often buy this (Keycloak / Auth0 / Cognito), but the bible wants to *show* the internals rather than configure a black box.

---

## 1. Where it sits in the system

```
                                 ┌─────────────────────────┐
                                 │   browser / curl / app  │
                                 └──────────┬──────────────┘
                                            │ (1) login / register
                                            ▼
   ┌──────────────────────┐         ┌────────────────┐
   │   api-gateway        │ ──────► │  auth-service  │
   │  (JwtAuthFilter)     │  JWT    │  port 9000     │
   │  validates via JWKS  │  verify │                │
   └──────────┬───────────┘  (every │  /oauth2/token │
              │              request)│  /.well-known/ │
              │ route forwards       │     jwks.json  │
              ▼                       │  /api/auth/*   │
   ┌──────────────────────┐            └────────┬───────┘
   │ order / payment /    │ ◄── Feign /me ──────┤
   │ restaurant / user /  │                     │
   │ search services      │                     │
   └──────────────────────┘                     │
            │                                   │
            │   (downstream services fetch      │
            │    /.well-known/jwks.json ONCE    │
            │    and cache it; auth-service is  │
            │    NOT in the per-request path)   │
            └───────────────────────────────────┘
```

- **Calls in:** browser/custom login form (password grant at `/oauth2/token`), anonymous user (POST `/api/auth/register`), the api-gateway's `JwtAuthFilter` (JWKS fetch at boot), and every downstream service at startup for the same JWKS fetch.
- **Calls out (synchronous):** none. auth-service does not call any other service.
- **Kafka:** **none**. The auth-service has no `@KafkaListener` and no producer; identity never needs an event because every consumer already trusts the same `iss` claim.
- **Persistence:** PostgreSQL database `auth` (separate database per service; created by `postgres/init-databases.sh` in compose).

---

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/auth-service` |
| Port | **9000** (read from `application.yml` `server.port`) |
| Database(s) | PostgreSQL `auth` on `localhost:5432` (compose overrides to `postgres:5432`) |
| Publishes topics | (none) |
| Consumes topics | (none) |
| REST endpoints (custom) | `POST /api/auth/register`, `GET /api/auth/me`, `GET /api/auth/debug/token`, `GET /api/auth/dev-token`, `GET /.well-known/jwks.json` |
| Framework endpoints (Spring AS) | `POST /oauth2/token`, `POST /oauth2/revoke`, `GET /oauth2/authorize`, `GET /oauth2/jwks`, `GET /.well-known/openid-configuration`, `GET /oauth2/introspect` |
| Depends on | `shared` (no Kafka — this service never uses `shared-kafka`); service-discovery (`eureka`) is **on the classpath** but `spring.cloud.config.enabled: false` for local dev |

---

## 3. File-by-file walkthrough

This section walks every `.java` file under `services/auth-service/src/main/java/`. The list below matches the directory tree (`config/`, `domain/`, `security/`, `web/`, plus the top-level `AuthServiceApplication`).

### 3.1 `AuthServiceApplication.java` (root)

- **What it is** — the Spring Boot main entry point.
- **Why it exists** — bootstraps the Spring context, starts the embedded Tomcat on port 9000, and registers the service with Eureka.
- **Spring annotations:**
  - `@SpringBootApplication` — combines `@Configuration`, `@EnableAutoConfiguration`, and `@ComponentScan`; marks this class as the application root so component-scanning starts here and finds every other `@Component` / `@Service` / `@RestController` in the package.
  - `@EnableDiscoveryClient` — turns on Eureka client registration so the api-gateway and other services can look this service up by name (`samato-auth-service`).
- **What it calls** — `SpringApplication.run(...)` (static). That's it; the rest is auto-wired.
- **What calls it** — the JVM, via `java -jar` or `docker compose up auth-service`.
- **Configuration keys** — none directly; all the bean wiring is driven by `application.yml`.

### 3.2 `config/DevDataSeeder.java`

- **What it is** — a `CommandLineRunner` that seeds five users and two OAuth clients on startup.
- **Why it exists** — the bible's local-dev workflow needs deterministic, known credentials every time ("same password for alice every reboot"). Production uses real migrations; this runner is a no-op when the active profile is `prod` (or any profile not in `{"dev", "default"}`).
- **Spring annotations:**
  - `@Component` — registers the class as a Spring-managed bean so it gets instantiated on startup.
  - `@Profile({"dev", "default"})` — only register the bean when the active Spring profile is `dev` or `default`; it is silently absent in `prod`.
  - `@Override` (on `run`) — overrides the `CommandLineRunner` interface method.
  - `@Transactional` (on `run`) — wraps the whole seed in one DB transaction so a partial failure rolls everything back; saves are committed atomically.
- **What it calls:**
  - `UserRepository` — `users.existsById(...)`, `users.existsByEmail(...)`, `users.save(...)`, `users.count()`.
  - `OAuthClientRepository` — `clients.findByClientId(...)`, `clients.save(...)`, `clients.count()`.
  - `PasswordEncoder` (bean from `AuthServerConfig#passwordEncoder`) — `encoder.encode(...)`.
  - Uses: `UserAccount` (entity), `OAuthClient` (entity), `Role` (enum).
- **What calls it** — Spring Boot, after context startup. `CommandLineRunner.run` is invoked once per application start.
- **Configuration keys:** none; profile-driven.

**Seeded identities:**

| Email | Password | Role(s) | UUID |
|---|---|---|---|
| alice@example.com | `password123` | CUSTOMER | `11111111-...` |
| bob@example.com   | `password123` | RESTAURANT_OWNER | `22222222-...` |
| carol@example.com | `password123` | DRIVER | `33333333-...` |
| dave@example.com  | `password123` | ADMIN | `44444444-...` |
| service@system    | `password123` | ADMIN | `55555555-...` |

| client_id | client_secret | redirect_uri |
|---|---|---|
| `api-gateway` | `gateway-secret-please-rotate` | (none) |
| `spa-client`  | `spa-secret-please-rotate`     | `http://localhost:5173/callback` |

The seeder is **idempotent**: it checks both `existsById` and `existsByEmail` before inserting, and for clients it re-hashes if the stored value is not a real BCrypt hash (legacy/clear-text recovery).

### 3.3 `domain/UserAccount.java`

- **What it is** — a JPA `@Entity` mapping the `users` table; one row per registered user.
- **Why it exists** — the auth-service's view of a user is intentionally **thin**: email + BCrypt password hash + a `Set<Role>`. The richer profile (display name, address, preferences) lives in `user-service` — see the "auth ≠ user profile" interview talking point in the per-service designer note. The two services are joined by `id`.
- **Spring annotations:**
  - `@Entity` — registers the class with JPA; Hibernate creates/validates a table for it.
  - `@Table(name = "users", indexes = { @Index(name = "idx_users_email", columnList = "email", unique = true) })` — pins the table name to `users` (avoids the SQL reserved word `user`) and creates a unique index on `email` for fast `findByEmail` lookups and to enforce uniqueness at the DB level.
  - `@Id` — marks `id` as the primary key.
  - `@GeneratedValue` — Hibernate generates a UUID automatically.
  - `@Column(columnDefinition = "uuid")` — pins the column type to `uuid` (PostgreSQL native) instead of `varchar`.
  - `@ElementCollection(targetClass = Role.class, fetch = FetchType.EAGER)` — declares the `roles` field as a collection of basic/embeddable values (not entities) that is loaded together with the parent. `EAGER` is required so `JwtRolesCustomizer` and `JpaUserDetailsService` see the roles without an extra query.
  - `@CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))` — specifies the join table for the element collection (`user_roles(user_id, role)`).
  - `@Enumerated(EnumType.STRING)` — stores the enum as its string name (`"CUSTOMER"`), not its ordinal — keeps the DB stable across renumberings.
  - `@PrePersist` / `@PreUpdate` — JPA lifecycle callbacks that stamp `createdAt` and `updatedAt`.
- **What it calls** — nothing; entities are pure data.
- **What calls it** — `UserRepository`, `JpaUserDetailsService`, `JwtRolesCustomizer`, `DevDataSeeder`, `RegistrationController`, `DevTokenController`, `MeController`.
- **Configuration keys** — none.

### 3.4 `domain/UserRepository.java`

- **What it is** — a Spring Data JPA repository for `UserAccount`.
- **Why it exists** — the framework generates the CRUD implementation; you just declare the query method names.
- **Spring annotations:** none on the interface itself; the magic comes from extending `JpaRepository<UserAccount, UUID>`, which marks the interface as a repository bean at scan time.
- **What it calls:** `UserAccount` (entity).
- **What calls it:** `RegistrationController` (`existsByEmail`, `save`), `MeController` (`findById` from the base class), `DevTokenController` (`findByEmail`), `DevDataSeeder` (`existsById`, `existsByEmail`, `save`, `count`), `JpaUserDetailsService` (`findByEmail`).
- **Configuration keys:** none.

### 3.5 `domain/Role.java`

- **What it is** — a Java `enum` with four values: `CUSTOMER`, `RESTAURANT_OWNER`, `DRIVER`, `ADMIN`.
- **Why it exists** — the role set is small and stable; an enum is simpler than a `roles` table. If the product's roles grow or vary per tenant, replace this with a table — see the comment in the source file.
- **Spring annotations:** none.
- **What it calls:** nothing.
- **What calls it:** `UserAccount` (the `Set<Role> roles` field), `RegistrationController` (assigns `CUSTOMER` to new sign-ups), `DevDataSeeder` (seeds each demo user's roles), `MeController` (maps `Role::name` to strings), `JwtRolesCustomizer` (maps `Role` to the `roles` claim).
- **Configuration keys:** none.

### 3.6 `domain/OAuthClient.java`

- **What it is** — a JPA `@Entity` for the `oauth_clients` table; one row per registered OAuth2 client (e.g. the api-gateway, a future SPA, a service-to-service account).
- **Why it exists** — Spring Authorization Server needs to authenticate the client (verify its `client_secret`) on every token request. Storing clients in the DB means you can register a new client or disable one without redeploying.
- **Spring annotations:**
  - `@Entity`, `@Table(name = "oauth_clients", ...)` — same as `UserAccount`, with a unique index on `client_id` so the `findByClientId` lookup is fast and uniqueness is enforced at the DB.
  - `@Id`, `@GeneratedValue`, `@Column`, `@PrePersist` — same meanings as in `UserAccount`.
- **What it calls:** nothing.
- **What calls it:** `OAuthClientRepository`, `JpaRegisteredClientRepository`, `DevDataSeeder`.
- **Configuration keys:** none.

### 3.7 `domain/OAuthClientRepository.java`

- **What it is** — Spring Data JPA repository for `OAuthClient`.
- **Why it exists** — exposes the single custom finder `findByClientId` that `JpaRegisteredClientRepository` needs on every token request.
- **Spring annotations:** none on the interface; `JpaRepository<OAuthClient, UUID>` is what wires it.
- **What it calls:** `OAuthClient`.
- **What calls it:** `JpaRegisteredClientRepository` (`findById` from the base, plus the custom `findByClientId`), `DevDataSeeder` (`findByClientId`, `save`, `count`).
- **Configuration keys:** none.

### 3.8 `security/AuthServerConfig.java`

- **What it is** — the heart of the service. A `@Configuration` class that wires:
  1. The OAuth2 / OIDC authorization-server filter chain (`@Order(1)`).
  2. A default Spring Security filter chain for everything else (`@Order(2)`).
  3. A `PasswordEncoder` bean (BCrypt cost 12).
  4. An RSA `JWKSource` (the JWT signing key — generated at startup).
  5. A `JwtDecoder` for the auth-service to validate its **own** tokens (used by `/api/auth/me`).
  6. `AuthorizationServerSettings` with the issuer URL.
- **Why it exists** — without this, Spring Security's defaults would block the `/oauth2/**` endpoints AND fail to mint JWTs. The class is the bridge between Spring Security's auto-config and the bible's specific auth model.
- **Spring annotations:**
  - `@Configuration` — marks the class as a source of bean definitions; Spring will invoke its `@Bean` methods at startup.
  - `@Bean` (on every method) — registers the return value as a Spring-managed bean; method name becomes the bean name.
  - `@Order(1)` (on `authServerFilterChain`) — runs this filter chain **before** any other; the matcher (`/oauth2/**`, `/.well-known/openid-configuration`) limits it to those paths.
  - `@Order(2)` (on `defaultSecurityFilterChain`) — runs second for everything else; the default rule is "any request must be authenticated except the public list."
- **What it calls:**
  - `OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http)` — applies Spring AS's recommended defaults.
  - `OAuth2AuthorizationServerConfigurer` — programmatic config (here, enabling OIDC).
  - `BCryptPasswordEncoder(12)` — constructor with cost factor 12 (~250 ms per hash).
  - `KeyPairGenerator.getInstance("RSA")` — JCA API; generates a fresh 2048-bit RSA key pair at startup.
  - `JWKSet`, `RSAKey`, `ImmutableJWKSet` — Nimbus JOSE+JWT classes for building the key set.
  - `OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)` — helper that builds a `JwtDecoder` from the same `JWKSource` so the service can verify its own tokens.
  - `AuthorizationServerSettings.builder().issuer("http://localhost:9000").build()` — the `iss` claim value that goes into every issued JWT.
- **What calls it:** the Spring framework (bean wiring), and indirectly every token request via the filter chain.
- **Configuration keys:** none directly; the issuer URL is **hard-coded** to `http://localhost:9000` — a follow-up is to make it env-driven (see §7 gotchas).

**Two filter chains — why two?** Spring Security can only have one `@Order(1)` chain that matches `/oauth2/**`, but `/.well-known/jwks.json` is needed by other services at startup. Splitting lets the AS chain handle the framework endpoints and the default chain permit `/.well-known/**` plus the public custom endpoints (`/api/auth/login`, `/api/auth/register`, `/api/auth/dev-token`).

### 3.9 `security/JpaRegisteredClientRepository.java`

- **What it is** — an adapter that bridges the `oauth_clients` DB table to Spring Authorization Server's `RegisteredClientRepository` interface.
- **Why it exists** — the AS calls this on **every token request** to authenticate the client (verify the presented `client_secret` against the stored hash). Without this adapter, the AS would have no way to know your clients.
- **Spring annotations:**
  - `@Component` — registers the class as a bean so Spring AS can `@Autowired` it.
  - `@Override` — on all three interface methods.
- **What it calls:**
  - `OAuthClientRepository.findById` and `findByClientId` (DB lookups).
  - `RegisteredClient.withId(...).clientId(...).clientSecret(...).build()` — builds the framework's view of a client; the `.clientSecret(hash)` passes the BCrypt hash verbatim because `DelegatingPasswordEncoder` detects the `$2a$` prefix and dispatches to `BCryptPasswordEncoder.matches`.
- **What calls it:** Spring Authorization Server's filter chain, on every `POST /oauth2/token`.
- **Configuration keys:** none.

### 3.10 `security/JpaUserDetailsService.java`

- **What it is** — a `UserDetailsService` that loads a `UserAccount` by email and wraps it as Spring Security's `User`.
- **Why it exists** — the AS needs a way to authenticate the **resource owner** (the end user) during the password grant. The default `InMemoryUserDetailsManager` would force us to declare users in `application.yml`; this custom version reads from the DB.
- **Spring annotations:**
  - `@Service` — registers the class as a service-layer bean (semantically identical to `@Component` but conventionally used in the service layer).
  - `@Override` — on `loadUserByUsername`.
- **What it calls:** `UserRepository.findByEmail(email)`.
- **What calls it:** Spring Security's authentication manager during password-grant flow.
- **Configuration keys:** none.

**Interview note** — the `ROLE_` prefix is Spring's convention. Downstream services use `@PreAuthorize("hasRole('CUSTOMER')")`, which Spring's `JwtAuthenticationConverter` populates from the `roles` JWT claim (via `JwtRolesCustomizer`, next file).

### 3.11 `security/JwtRolesCustomizer.java`

- **What it is** — an `OAuth2TokenCustomizer<JwtEncodingContext>` that adds three claims to every access token: `roles`, `user_id`, `email`.
- **Why it exists** — without these claims, downstream services would have to call back to `auth-service` on every request to know who the caller is and what they can do. Putting the claims **in the token** is the whole point of JWT.
- **Spring annotations:**
  - `@Component` — registers the bean so the AS picks it up automatically.
  - `@Override` — on `customize`.
- **What it calls:** `UserAccount` getters (the principal comes from `UsernamePasswordAuthenticationToken` after `JpaUserDetailsService` resolves it). Note: it pulls roles from the **`UserAccount` object** (which has the freshest roles from the DB) rather than from the `Authentication`'s authorities list, so a freshly-issued token always has the latest roles.
- **What calls it:** the AS token-endpoint code path, just before signing.
- **Configuration keys:** none.

### 3.12 `web/RegistrationController.java`

- **What it is** — the user sign-up endpoint, `POST /api/auth/register`.
- **Why it exists** — OAuth2 defines **client** registration, not user registration. User sign-up is application-specific; we keep it here for simplicity. In a real product, it would live in a separate user-onboarding service.
- **Spring annotations:**
  - `@RestController` — marks the class as a web controller whose method return values are serialized to the response body (usually as JSON).
  - `@RequestMapping("/api/auth")` — sets the base path for every method in this class.
  - `@PostMapping("/register")` — `POST /api/auth/register`.
  - `@ResponseStatus(HttpStatus.CREATED)` — overrides the default 200 OK to 201 Created.
  - `@Valid` (parameter) — triggers Bean Validation on the request body.
  - `@RequestBody` — deserializes the HTTP request body into the `RegisterRequest` record.
  - `@Email`, `@NotBlank`, `@Size(min=8, max=100)` (on the record fields) — validation constraints; failures throw `MethodArgumentNotValidException`, handled by `GlobalExceptionHandler` in the `shared` module (see [shared-and-kafka](shared-and-kafka.md)).
- **What it calls:**
  - `UserRepository.existsByEmail(...)` — duplicate check.
  - `PasswordEncoder.encode(...)` — BCrypt-hash the password.
  - `UserRepository.save(...)` — persist the new user.
  - `DomainException("EMAIL_TAKEN", ..., 409)` — thrown when the email is already in use. `GlobalExceptionHandler` converts this to a JSON error response — see [shared-and-kafka](shared-and-kafka.md).
- **What calls it:** anyone (the path is `permitAll`); typically the registration form on the SPA or a curl in the bible's docs.
- **Configuration keys:** none.

**Request shape (record):**
```json
{ "email": "alice@example.com", "password": "password123" }
```
**Response shape (201):**
```json
{ "id": "<uuid>", "email": "alice@example.com" }
```

### 3.13 `web/MeController.java`

- **What it is** — `GET /api/auth/me`; returns the caller's id, email, and roles.
- **Why it exists** — the "who am I?" endpoint. The JWT subject is the user's UUID; we look up the fresh roles from the DB so a revocation takes effect immediately (a deleted user gets 404, even with a valid token).
- **Spring annotations:**
  - `@RestController`, `@RequestMapping("/api/auth")`, `@GetMapping("/me")` — same meanings as in `RegistrationController`.
  - `@AuthenticationPrincipal Jwt jwt` — Spring's argument resolver that injects the validated JWT as a method parameter; the resource-server filter chain does the signature check.
- **What it calls:** `UserRepository.findById(UUID)`. If absent, throws `AccessDeniedException("user not found")` — Spring Security converts this to 403 by default.
- **What calls it:** any authenticated client (the path is `anyRequest().authenticated()` from `AuthServerConfig#defaultSecurityFilterChain`).
- **Configuration keys:** none.

**Response shape (200):**
```json
{ "id": "<uuid>", "email": "alice@example.com", "roles": ["CUSTOMER"] }
```

### 3.14 `web/TokenDebugController.java`

- **What it is** — `GET /api/auth/debug/token`; returns the parsed claims of the caller's token.
- **Why it exists** — debugging tool. When wiring up a new downstream service, `curl` this with a token to see exactly what the JWT contains.
- **Spring annotations:** `@RestController`, `@RequestMapping("/api/auth")`, `@GetMapping("/debug/token")`, `@AuthenticationPrincipal Jwt jwt`.
- **What it calls:** JWT getters only — no DB.
- **What calls it:** a developer with a valid token. **Not exposed via the gateway** (the gateway's `/api/auth/**` route in Phase 1 forwards everything to this service, but in practice this endpoint is hit directly against port 9000).
- **Configuration keys:** none.

### 3.15 `web/DevTokenController.java`

- **What it is** — `GET /api/auth/dev-token?email=...`; issues a real RS256 JWT for any seeded user, bypassing the OAuth2 grant flow.
- **Why it exists** — **workaround.** Spring Authorization Server 1.3.x dropped the resource-owner password grant, but the bible's design depends on it. For local dev, you can call this endpoint to get a working token without going through `/oauth2/token`. The token has the same shape (same RSA key, same `iss`, same `sub`, same claims) so the gateway accepts it.
- **Spring annotations:**
  - `@RestController`, `@RequestMapping("/api/auth")`, `@GetMapping("/dev-token")`, `@RequestParam("email") String email` — all standard.
  - `@Profile({"dev", "default"})` — disables this controller in any other profile; in production the bean does not exist.
- **What it calls:**
  - `UserRepository.findByEmail(email)`.
  - `JWKSource<SecurityContext>` (the bean from `AuthServerConfig#jwkSource`) — wrapped in a `NimbusJwtEncoder` for signing.
  - `JwtEncoder.encode(...)` — produces the JWT string.
- **What calls it:** a developer in dev. The compose file does not pin a profile, so this controller is on by default.
- **Configuration keys:** none directly; the issuer URL is hard-coded to `http://localhost:9000` to match `AuthServerConfig.authorizationServerSettings().issuer(...)`.

**Response shape (200):**
```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "openid profile read write"
}
```

### 3.16 `web/JwksController.java`

- **What it is** — `GET /.well-known/jwks.json`; returns the public signing key(s) as a JWKS document.
- **Why it exists** — Spring AS's default filter chain (`authServerFilterChain`, `@Order(1)`) handles `/oauth2/jwks` but does **not** cover `/.well-known/**`. The bible's service configs all point at the canonical OIDC path `/.well-known/jwks.json`, so we expose it here to match.
- **Spring annotations:** `@RestController`, `@RequestMapping("/.well-known")`, `@GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)`.
- **What it calls:** `JWKSource.get(...)` to select keys, `JWKSet.toString(true)` to render them as public-only JSON.
- **What calls it:** the api-gateway, user-service, order-service, payment-service, restaurant-service, and search-service — each fetches this URL once at startup to verify incoming JWTs. The set of keys here is identical to the one at `/oauth2/jwks`; we support both for OIDC-canonical friendliness.
- **Configuration keys:** none.

**Response shape (200, public-only JWKSet):**
```json
{ "keys": [ { "kty": "RSA", "kid": "<uuid>", "use": "sig", "alg": "RS256", "n": "...", "e": "AQAB" } ] }
```

### 3.17 `application.yml` (subsection)

Every key in `services/auth-service/src/main/resources/application.yml`:

| Key | Value | Plain-English meaning |
|---|---|---|
| `server.port` | `9000` | The HTTP port. |
| `server.shutdown` | `graceful` | Finish in-flight requests on SIGTERM. |
| `spring.application.name` | `samato-auth-service` | Service name; the Eureka registration name. |
| `spring.cloud.config.enabled` | `false` | Don't fetch config from config-service in dev. Flip to `true` for environments that run config-service. |
| `spring.profiles.active` | `${SPRING_PROFILES_ACTIVE:default}` | Default profile is `default` (so `DevDataSeeder` runs). Override with `SPRING_PROFILES_ACTIVE=prod` to disable the seeder. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/auth` | PostgreSQL connection string. The compose file overrides the host to `postgres`; the database name is `auth`. |
| `spring.datasource.username` / `password` | `fd` / `fd` | DB credentials. |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate validates that the entity model matches the DB schema; it does **not** create or alter tables. Flyway owns the schema. |
| `spring.jpa.properties.hibernate.format_sql` | `false` | Don't pretty-print SQL to logs. |
| `spring.flyway.enabled` | `true` | Run Flyway migrations on startup. |
| `spring.flyway.locations` | `classpath:db/migration` | Where to find the `.sql` files. |
| `spring.flyway.baseline-on-migrate` | `true` | If the DB is non-empty, baseline it instead of failing. |
| `spring.security.oauth2.authorizationserver.access-token.format` | `self-contained` | Issue JWTs (not opaque tokens). |
| `spring.security.oauth2.authorizationserver.access-token.lifetime` | `PT15M` | Access tokens expire 15 minutes after issue. |
| `spring.security.oauth2.authorizationserver.refresh-token.lifetime` | `P7D` | Refresh tokens are valid for 7 days. |
| `spring.security.oauth2.authorizationserver.grant-types.password` | `enabled` | The OAuth2 resource-owner password grant is on. The bible's login form uses this. |
| `spring.security.oauth2.authorizationserver.grant-types.refresh_token` | `enabled` | Clients can exchange a refresh token for a new access token. |
| `spring.security.oauth2.authorizationserver.grant-types.client_credentials` | `enabled` | Machine-to-machine grant (the api-gateway can get a token for itself). |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | The Eureka server URL. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Actuator endpoints exposed. |
| `management.endpoint.health.probes.enabled` | `true` | K8s liveness/readiness probes (`/actuator/health/liveness`, `/actuator/health/readiness`). |
| `management.tracing.sampling.probability` | `1.0` | Sample 100% of traces. |
| `management.zipkin.tracing.endpoint` | `http://localhost:9411/api/v2/spans` | Where to send Zipkin spans. |
| `logging.pattern.level` | `"%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"` | Log line includes app name, trace id, span id, correlation id. |
| `logging.level.org.springframework.security` | `INFO` | Quiet the security framework. |
| `logging.level.com.samato` | `DEBUG` | Verbose for our own code. |

---

## 4. Endpoints (controllers)

The auth-service exposes two kinds of endpoints: **custom REST endpoints** (hand-written controllers) and **framework endpoints** (auto-wired by Spring Authorization Server). Both are served on port 9000.

### 4.1 Custom endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/auth/register` | public | Sign up a new user (CUSTOMER role). |
| `GET`  | `/api/auth/me` | JWT | Return the caller's id, email, roles. |
| `GET`  | `/api/auth/dev-token` | public (dev only) | Issue a JWT for any seeded user. **Disabled in `prod` profile via `@Profile`.** |
| `GET`  | `/api/auth/debug/token` | JWT | Dump the parsed JWT claims (debug only). |
| `GET`  | `/.well-known/jwks.json` | public | Public signing keys (JWKS). |

#### `POST /api/auth/register`

- **Controller:** `RegistrationController#register`
- **Auth:** permitAll (in `AuthServerConfig#defaultSecurityFilterChain`).
- **Request body:**
  ```json
  { "email": "alice@example.com", "password": "password123" }
  ```
  Validation: `email` must look like an email and not be blank; `password` must be 8-100 chars and not blank. Validation failures → 400 via `GlobalExceptionHandler`.
- **Response (201):**
  ```json
  { "id": "<uuid>", "email": "alice@example.com" }
  ```
- **Downstream:** inserts a `users` row + a `user_roles` row. No Kafka, no Feign calls.
- **Errors:** 409 `EMAIL_TAKEN` if the email is already registered; 400 on validation failure.
- **Example curl:**
  ```bash
  curl -X POST http://localhost:9000/api/auth/register \
    -H "Content-Type: application/json" \
    -d '{"email":"eve@example.com","password":"password123"}'
  ```

#### `GET /api/auth/me`

- **Controller:** `MeController#me`
- **Auth:** authenticated (bearer JWT in `Authorization` header).
- **Request:** none.
- **Response (200):**
  ```json
  { "id": "11111111-1111-1111-1111-111111111111", "email": "alice@example.com", "roles": ["CUSTOMER"] }
  ```
- **Downstream:** one `users.findById` call. If the user no longer exists, returns 403.
- **Example curl:**
  ```bash
  TOKEN=$(curl -X POST http://localhost:9000/oauth2/token \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -u "api-gateway:gateway-secret-please-rotate" \
    -d "grant_type=password&username=alice@example.com&password=password123" \
    | jq -r .access_token)

  curl http://localhost:9000/api/auth/me -H "Authorization: Bearer $TOKEN"
  ```

#### `GET /api/auth/dev-token?email=...`

- **Controller:** `DevTokenController#devToken`
- **Auth:** permitAll **in `dev` or `default` profile**; the controller bean does not exist in `prod` thanks to `@Profile({"dev","default"})`.
- **Query params:** `email` (required, the seeded user's email).
- **Response (200):**
  ```json
  {
    "access_token": "eyJ...",
    "token_type": "Bearer",
    "expires_in": 3600,
    "scope": "openid profile read write"
  }
  ```
- **Downstream:** one `users.findByEmail` call; signs a JWT with the same RSA key as the AS, so the gateway accepts it.
- **Errors:** 404 `USER_NOT_FOUND` if the email is not a seeded user.
- **Example curl:**
  ```bash
  curl "http://localhost:9000/api/auth/dev-token?email=alice@example.com"
  ```

#### `GET /api/auth/debug/token`

- **Controller:** `TokenDebugController#debug`
- **Auth:** authenticated.
- **Response (200):** a `DebugView` record with `sub`, `iss`, `aud`, `exp`, `user_id`, `email`, `roles`.
- **Example curl:**
  ```bash
  curl http://localhost:9000/api/auth/debug/token -H "Authorization: Bearer $TOKEN"
  ```

#### `GET /.well-known/jwks.json`

- **Controller:** `JwksController#jwks`
- **Auth:** permitAll (matched by `/.well-known/**` in `defaultSecurityFilterChain`).
- **Response (200):** JWKSet JSON with the public key only (the private key never leaves the JVM).
- **Example curl:**
  ```bash
  curl http://localhost:9000/.well-known/jwks.json | jq .
  ```

### 4.2 Framework endpoints (Spring Authorization Server)

The `@Order(1)` `authServerFilterChain` in `AuthServerConfig` activates the following standard OAuth2 / OIDC paths. You don't write controllers for these; the AS auto-wires them.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/oauth2/token` | The token endpoint. Supports `password`, `refresh_token`, `client_credentials`. |
| `POST` | `/oauth2/revoke` | Revoke a token. (Not used by the bible in Phase 2.) |
| `POST` | `/oauth2/introspect` | RFC 7662 introspection. (Not used by the bible in Phase 2.) |
| `GET`  | `/oauth2/authorize` | The authorization endpoint. The bible doesn't use authorization-code flow. |
| `GET`  | `/oauth2/jwks` | Same JWKS as `/.well-known/jwks.json`; Spring AS's default location. |
| `GET`  | `/.well-known/openid-configuration` | OIDC discovery document. |

**Example: get a token via password grant**
```bash
curl -X POST http://localhost:9000/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "api-gateway:gateway-secret-please-rotate" \
  -d "grant_type=password&username=alice@example.com&password=password123&scope=openid profile"

# Response:
# { "access_token": "eyJ...", "refresh_token": "...", "token_type": "Bearer",
#   "expires_in": 900, "scope": "openid profile" }
```

**Example: refresh a token**
```bash
curl -X POST http://localhost:9000/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "api-gateway:gateway-secret-please-rotate" \
  -d "grant_type=refresh_token&refresh_token=$REFRESH"
```

---

## 5. Database schema

Only one migration file: `services/auth-service/src/main/resources/db/migration/V1__init.sql`. It creates three tables.

### 5.1 `users`

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, generated by Hibernate (`@GeneratedValue`). |
| `email` | `VARCHAR(255)` | `NOT NULL UNIQUE`. Also has index `idx_users_email` for fast `findByEmail`. |
| `password_hash` | `VARCHAR(255)` | `NOT NULL`. BCrypt cost 12. |
| `created_at` | `TIMESTAMP` | `NOT NULL`. Stamped by `@PrePersist`. |
| `updated_at` | `TIMESTAMP` | `NOT NULL`. Stamped by `@PrePersist` and `@PreUpdate`. |

- **Indexes:** `idx_users_email` on `(email)`.
- **Writes:** `RegistrationController#register` (`save`), `DevDataSeeder#seedUser` (`save`).
- **Reads:** `JpaUserDetailsService#loadUserByUsername` (`findByEmail`), `MeController#me` (`findById`), `DevTokenController#devToken` (`findByEmail`), `UserRepository` count in `DevDataSeeder`.

### 5.2 `user_roles`

| Column | Type | Notes |
|---|---|---|
| `user_id` | `UUID` | `NOT NULL`, FK → `users(id) ON DELETE CASCADE`. |
| `role` | `VARCHAR(50)` | `NOT NULL`. Stores the enum name (`CUSTOMER`, `ADMIN`, ...). |

- **Primary key:** `(user_id, role)` — composite.
- **Writes:** `UserAccount.roles` `@ElementCollection` is persisted automatically on `users.save(...)`; explicit writes via `RegistrationController` (always `Set.of(CUSTOMER)`) and `DevDataSeeder`.
- **Reads:** loaded eagerly with `UserAccount` (no separate query — Hibernate fetches them in the same round-trip).

### 5.3 `oauth_clients`

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK. |
| `client_id` | `VARCHAR(100)` | `NOT NULL UNIQUE`. Also has index `idx_oauth_clients_client_id`. |
| `client_secret_hash` | `VARCHAR(255)` | `NOT NULL`. BCrypt hash. The framework's `DelegatingPasswordEncoder` matches against this on every token request. |
| `redirect_uri` | `VARCHAR(500)` | nullable. |
| `enabled` | `BOOLEAN` | `NOT NULL DEFAULT TRUE`. |
| `created_at` | `TIMESTAMP` | `NOT NULL`. |

- **Indexes:** `idx_oauth_clients_client_id` on `(client_id)`.
- **Writes:** `DevDataSeeder#seedClient` (idempotent re-hash on legacy formats).
- **Reads:** `JpaRegisteredClientRepository#findById` and `findByClientId` (called on every token request).

### 5.4 No outbox table

Unlike `order-service`, `payment-service`, and `restaurant-service`, the auth-service does **not** have an `outbox_events` table — because it doesn't publish Kafka events.

---

## 6. Kafka integration

**None.** The auth-service has:
- No `kafka-clients` / `spring-kafka` dependency in `pom.xml`.
- No `KafkaTemplate` bean.
- No `@KafkaListener` methods.
- No `OutboxPublisher` or any outbox poller.
- No Avro schema files in this module.

This is intentional. The whole point of JWT is that **identity is verifiable locally** — every downstream service fetches the JWKS once at startup and verifies signatures on its own. There is no need for an event stream from auth.

If you ever do need to broadcast "user X was deleted" or "user Y got role Z" to other services, the right pattern is:
1. Add a `kafka` dependency to this service.
2. Reuse the `shared-kafka` module's `KafkaProducerConfig` bean.
3. Publish an Avro event (e.g. `UserDeactivatedEvent`) via the same outbox pattern used elsewhere.

---

## 7. Common "if you change X, also update Y" notes

These are the cross-file dependencies that aren't obvious from imports alone — the gotchas that cost beginners hours.

1. **Hard-coded issuer URL.** `AuthServerConfig.authorizationServerSettings().issuer("http://localhost:9000")` and `DevTokenController` set `JwtClaimsSet.builder().issuer("http://localhost:9000")` both bake in the same URL. If you change the port or run behind a reverse proxy, the `iss` claim will no longer match the JWKS URL, and every downstream service will reject tokens with `InvalidIssuerException`. Make this env-driven (e.g. `AuthorizationServerSettings.builder().issuer(envIssuer).build()`) before deploying anywhere that isn't `localhost:9000`.

2. **The signing key is generated fresh on every start.** `AuthServerConfig#jwkSource` calls `KeyPairGenerator.getInstance("RSA").initialize(2048).generateKeyPair()` at startup. **All tokens issued by the previous instance become unverifiable** after a restart, because the JWKS now publishes a different `kid`. This is fine for dev (a 15-min access token + restart = small blast radius) but **catastrophic in production**. For prod, load the key from a KMS / Vault and pin the `kid`.

3. **`ClientSecret` format — do NOT prepend `{bcrypt}`.** `JpaRegisteredClientRepository#toRegisteredClient` passes the stored hash verbatim. An earlier code path prepended `{bcrypt}`, which caused the framework to call `BCryptPasswordEncoder.matches(rawInput, rawInput)` — the "looks like BCrypt" check on the raw side then failed. The seeder also has logic to recover from a legacy/clear-text form. If you ever change the encoding, **read the long comment in `JpaRegisteredClientRepository` first**.

4. **`/api/auth/login` is mentioned in `defaultSecurityFilterChain` but doesn't exist as a controller.** The bible's docs refer to `POST /api/auth/login`, but the actual login call is `POST /oauth2/token` (the framework endpoint). The custom controllers are `/register`, `/me`, `/dev-token`, `/debug/token`. If you add a custom login endpoint, also add it to the `permitAll` matcher in `defaultSecurityFilterChain`.

5. **`Role` is an enum, not a table.** Adding a new role means editing `Role.java` and recompiling, **and** editing any test that hard-codes role lists. Don't add roles in a migration.

6. **`user_roles` rows are managed by JPA's `@ElementCollection`, not by hand.** If you need to insert a role directly in SQL, insert into both `users` and `user_roles` in the same transaction; JPA will delete orphan `user_roles` rows on the next `users.save(...)` call (because the collection is a `Set<Role>` field, not a separate entity).

7. **`DevDataSeeder` is profile-gated but runs in `default`.** If you create a new profile (say `staging`) and expect the seeder, you must add `staging` to `@Profile({"dev", "default"})`. Conversely, if you want to disable the seeder in some test profile, give the profile a name that is **not** in the list.

8. **`MeController#me` reads roles from the DB, not the JWT.** The `roles` claim in the JWT may be stale (up to 15 min). If your client needs absolutely-fresh roles, call `/api/auth/me` instead of trusting the claim. This is a deliberate trade-off: a token is fast and self-contained, but roles are best-effort.

9. **`JwksController` and Spring AS both publish a JWKS.** The controller is at `/.well-known/jwks.json` (OIDC-canonical); Spring AS publishes at `/oauth2/jwks` (Spring-default). They are interchangeable. If a new downstream service is configured with `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`, the URL must point to one of these two.

10. **Cross-cutting exception handling is in the `shared` module.** `RegistrationController` throws `DomainException`, which is caught by `GlobalExceptionHandler` in the `shared` module — see [shared-and-kafka](shared-and-kafka.md). This is the only place the auth-service uses the shared module; it does not use `shared-kafka`.

11. **The api-gateway's `JwtAuthFilter` fetches `/.well-known/jwks.json` at startup.** If the auth-service is down when the gateway starts, the gateway will fail to start (or it will log a warning and let all requests through unauthenticated, depending on the gateway's `OnStartup` policy). Order matters in compose: the gateway declares `auth-service: service_started` in `depends_on`.

12. **CORS is not configured for the auth-service directly.** The api-gateway has a `DedupeResponseHeader` global CORS filter; cross-origin requests to the auth-service from a SPA should go **through** the gateway's `/api/auth/**` route, not directly to port 9000. (The gateway's route for `/api/auth/**` is public, no JWT filter — see [shared-and-kafka](shared-and-kafka.md) for the gateway's CORS config.)

13. **`AuthorizationServerSettings.issuer` is **not** the only place the `iss` claim is set.** `DevTokenController` also sets the issuer explicitly when building the `JwtClaimsSet`. Both must agree, or the gateway will see two different `iss` values for tokens issued by the two endpoints.

14. **No CSRF protection.** `defaultSecurityFilterChain` calls `.csrf(AbstractHttpConfigurer::disable)`. This is fine for a JWT-based REST API where the token goes in the `Authorization` header, but you must never accept cookies for auth in this service or you've opened a CSRF hole.

15. **Stateless session policy.** `defaultSecurityFilterChain` sets `SessionCreationPolicy.STATELESS`. There is no HTTP session. If you add a feature that needs one (e.g. a server-rendered login page), revisit this.

---

## 8. See also

- **Per-service designer note:** [services/auth-service/docs/INTERVIEW-NOTES.md](../../services/auth-service/docs/INTERVIEW-NOTES.md) — interview Q&A, pattern summary, trade-offs.
- **Use case: auth flow** — [../use-cases/02-auth-flow.md](../use-cases/02-auth-flow.md).
- **Use case: place an order** (uses the JWT for the `Authorization` header) — [../use-cases/01-place-an-order.md](../use-cases/01-place-an-order.md).
- **Use case: browse and search** (uses the JWT) — [../use-cases/03-browse-and-search.md](../use-cases/03-browse-and-search.md).
- **Use case: refund flow** (uses the JWT) — [../use-cases/04-refund-flow.md](../use-cases/04-refund-flow.md).
- **Architecture guide** — [../01-architecture-guide.md](../01-architecture-guide.md).
- **How auth works end-to-end** — [../02-how-auth-works.md](../02-how-auth-works.md).
- **Shared utilities and Kafka** — [shared-and-kafka.md](shared-and-kafka.md) (covers `DomainException`, `GlobalExceptionHandler`, `CorrelationIdFilter`, `MdcKeys`).
- **Glossary** — [../00-glossary.md](../00-glossary.md).
- **Project-level references:** [ARCHITECTURE.md](../../ARCHITECTURE.md), [PROJECT-STATUS.md](../../PROJECT-STATUS.md), [RUN-THE-BIBLE.md](../../RUN-THE-BIBLE.md), [INTERVIEW-CHEATSHEET.md](../../docs/INTERVIEW-CHEATSHEET.md).
