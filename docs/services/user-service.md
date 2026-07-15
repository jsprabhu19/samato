# user-service (port 8081)

> Plain-English purpose: this service stores the **profile** side of a user's identity (display name, photo, phone, preferences for customers; vehicle, license plate, GPS for drivers; business name, tax ID for restaurant owners). It is deliberately separate from `auth-service`, which owns passwords, OAuth clients, and roles. Together the two services implement the standard split between **authentication (who you are)** and **profile (what you look like to the product)**. Design patterns demonstrated: **OAuth2 resource server** (validates JWTs locally against auth-service's JWKS), **role-based authorization at the service** (per-method `@PreAuthorize`), **repository-per-aggregate** (three tables, three repos, three controllers), **OpenFeign client with circuit-breaker config** (talks to auth-service with a typed interface and Resilience4j wrapping), and **logical-references-not-foreign-keys across services** (the `userId` UUID is the join key, not a DB FK).

## 1. Where it sits in the system

```
                              Internet / Mobile App
                                     |
                                     v
                       +----------------------------+
                       |   api-gateway (port 8080)  |
                       |  - validates JWT (JWKS)    |
                       |  - routes /api/users/**    |
                       +----------------------------+
                                     |
                                     v
                       +----------------------------+
                       |  user-service (port 8081)  |
                       |  - reads JWT from header   |
                       |  - reads/writes profiles   |
                       |  - Feign -> auth-service   |
                       +----------------------------+
                          |              |       |
                          v              v       v
                  +-----------+  +-------------+ +--------------------+
                  | Postgres  |  | auth-service| | (future: delivery- |
                  | user_     |  | (port 9000) | |  service consumes  |
                  | service   |  | via Feign   | |  /api/users/       |
                  | DB        |  |             | |  drivers/on-duty)  |
                  +-----------+  +-------------+ +--------------------+
```

- **Calls into it**: the API gateway (for browser/mobile requests, every request under `/api/users/**` is routed here after the gateway's `JwtAuthFilter` has validated the token).
- **It calls**: `auth-service` over HTTP using the `AuthClient` OpenFeign interface (`GET /api/auth/me` to look up the current user; the Feign client is defined but **not yet wired into a controller** — see "if you change X, also update Y" in §7).
- **Events**: none. user-service neither publishes nor consumes Kafka topics (it does **not** depend on the `shared-kafka` module). All inter-service communication is synchronous via Feign + REST.

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/user-service` |
| Port | `8081` (read from `application.yml` line 2; matches the gateway route `samato-user-service` and the Eureka app id) |
| Database(s) | PostgreSQL `user_service` on `localhost:5432` (Docker service name `postgres`; in compose: `jdbc:postgresql://postgres:5432/user_service`, user `fd`, password `fd`) |
| Publishes topics | none |
| Consumes topics | none |
| REST endpoints | `GET /api/users/me`, `PUT /api/users/me`, `GET /api/users/{id}`, `GET /api/users/drivers/me`, `PUT /api/users/drivers/me`, `PUT /api/users/drivers/me/location`, `GET /api/users/drivers/on-duty`, `GET /api/users/restaurant-owners/me`, `PUT /api/users/restaurant-owners/me` |
| Depends on | `auth-service` (via `AuthClient` Feign), `auth-service` JWKS endpoint (for JWT validation), `discovery-service` (Eureka at `http://localhost:8761/eureka/`), `postgres` (Postgres 16), `api-gateway` (entry point), and the shared library `com.samato:shared` (for `DomainException`, `FeignCorrelationIdInterceptor`) |

The endpoint list is also captured in the cross-service inventory at `docs/inventory/endpoints-and-use-cases.json` under `byUseCase.auth-flow`.

## 3. File-by-file walkthrough

user-service has **15 Java files** under `services/user-service/src/main/java/`. They fall into four groups: bootstrap, security, client (Feign), and the three profile slices (customer / driver / restaurant owner). Walkthrough in source order:

### 3.1 `UserServiceApplication.java`

- **What it is**: the Spring Boot entry point — the `main(String[])` that calls `SpringApplication.run(...)` to start the embedded Tomcat on port 8081.
- **Why it exists**: every Spring Boot service needs one `main`; this one also turns on the bits of Spring Cloud that this service uses.
- **Spring annotations**:
  - `@SpringBootApplication`: the umbrella annotation that turns on auto-configuration, component scanning from this package, and the "this is a Spring Boot app" marker.
  - `@EnableDiscoveryClient`: registers this service with the Eureka server at `http://localhost:8761/eureka/` so the API gateway and other services can find it by name (`samato-user-service`) instead of by hard-coded host.
  - `@EnableFeignClients`: enables Spring Cloud OpenFeign so the `AuthClient` interface in this module is wired up to a real HTTP client.
- **What it calls**: nothing (entry point only).
- **What calls it**: the JVM at process start.
- **Configuration keys**: none directly. Port, app name, Eureka URL, Feign config all live in `application.yml` and are read by other beans.

### 3.2 `security/SecurityConfig.java`

- **What it is**: the Spring Security configuration for the whole service — defines the `SecurityFilterChain` (which requests need auth, which don't), the `JwtDecoder` (how to validate incoming JWTs), and the `JwtAuthenticationConverter` (how to turn JWT claims into Spring Security roles).
- **Why it exists**: user-service is a **resource server** — it accepts JWT bearer tokens from the API gateway and validates them locally. Without this file every request would be 401.
- **Spring annotations**:
  - `@Configuration`: marks the class as a source of Spring beans.
  - `@EnableMethodSecurity`: turns on method-level `@PreAuthorize` so the controllers can write `hasRole('DRIVER')` and have it actually enforced.
  - `@Bean` (on `filterChain`, `jwtDecoder`, `jwtAuthenticationConverter`): each of these is a method that returns an object Spring should manage.
- **What it calls**: `HttpSecurity` (built by Spring Security; this file configures it), `NimbusJwtDecoder.withJwkSetUri(...)` (the library that downloads the public key from auth-service and validates signatures), `JwtGrantedAuthoritiesConverter` (the helper that maps JWT claims to `GrantedAuthority` objects).
- **What calls it**: nothing — Spring instantiates it during startup. Downstream, every web request flows through the `filterChain` bean it produces.
- **Configuration keys**:
  - `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` — the URL the JWT decoder fetches the auth-service public key from. In local dev `http://localhost:9000/.well-known/jwks.json`; in compose it's `http://auth-service:9000/.well-known/jwks.json`.

The filter chain does four things worth highlighting in plain English:
1. **CSRF off** — JSON APIs don't need it.
2. **Stateless sessions** — no `JSESSIONID` cookie, the JWT in the `Authorization` header is the only credential.
3. **Permits `/actuator/health/**` and `/actuator/info`** — K8s liveness/readiness probes don't have JWTs.
4. **Everything else requires a valid JWT** — and on failure, returns `401 UNAUTHORIZED` (no redirect to a login page, because this is an API).

The `JwtAuthenticationConverter` does the one **custom** bit: it tells Spring to read the JWT's `roles` claim (not the default `scope`/`scp`) and prefix values with `ROLE_`. That's what makes `@PreAuthorize("hasRole('DRIVER')")` work — the JWT literally says `"roles": ["DRIVER"]` and Spring turns it into the authority `ROLE_DRIVER`.

### 3.3 `client/AuthClient.java`

- **What it is**: an OpenFeign **declarative HTTP client** — a Java interface where each method maps to an HTTP call against `auth-service`.
- **Why it exists**: so the rest of user-service can call auth-service using a typed method (`authClient.me(bearer)`) instead of hand-writing `RestTemplate`/`WebClient` boilerplate. Feign derives the URL, headers, and JSON serialization from the method signature.
- **Spring annotations**:
  - `@FeignClient(name = "samato-auth-service", fallback = AuthClientFallback.class)`: tells Spring "when something injects `AuthClient`, build a proxy that does HTTP calls to the Eureka-registered service `samato-auth-service`; if the call fails, call `AuthClientFallback` instead." The `name` is a service id, not a URL — Eureka resolves it to a host:port at call time.
- **What it calls**: the only declared method is `me(String bearer)` which maps to `GET /api/auth/me` on auth-service. The return type is the nested `AuthMeResponse(String id, String email, Set<String> roles)` record.
- **What calls it**: **nobody, in the current source tree** — see the anomaly in §7. The wiring exists (Feign client, fallback, Resilience4j config in `application.yml`) but no controller or service injects `AuthClient` today. The client is in place so Phase 7 can flip the "user existence check" from "trust the JWT subject" to "ask auth-service via Feign."
- **Configuration keys** (consumed via the `FeignConfig` in §3.4):
  - `spring.cloud.openfeign.client.config.default.connect-timeout: 2000`
  - `spring.cloud.openfeign.client.config.default.read-timeout: 5000`
  - `spring.cloud.openfeign.client.config.default.logger-level: BASIC`
  - `resilience4j.circuitbreaker.instances.authService.*` and `resilience4j.retry.instances.authService.*` — **configured but not yet annotated** on the method. The class javadoc states these are intended for Phase 7.

### 3.4 `client/AuthClientFallback.java`

- **What it is**: a `@Component` implementing the `AuthClient` interface, used by Feign when the real call fails (auth-service is down, times out, or returns 5xx).
- **Why it exists**: to give a **typed fallback** — the caller still gets an `AuthClient` and can handle a `DomainException("AUTH_UNREACHABLE", ..., 503)` cleanly. Without it, Feign would throw a generic `FeignException` and the upstream code would have to translate.
- **Spring annotations**:
  - `@Component`: makes Spring pick this class up during component scan and use it as the fallback bean whenever an `AuthClient` is needed.
- **What it calls**: `DomainException` (from `com.samato.shared.errors` — see [shared-and-kafka.md](./shared-and-kafka.md)).
- **What calls it**: Feign's runtime, transparently, whenever the real `AuthClient.me(...)` call throws.
- **Configuration keys**: none.

### 3.5 `config/FeignConfig.java`

- **What it is**: a tiny `@Configuration` that exposes the shared `FeignCorrelationIdInterceptor` as a Spring bean.
- **Why it exists**: Spring Cloud OpenFeign only auto-registers `RequestInterceptor` beans — if `FeignCorrelationIdInterceptor` (defined in the `shared` module) is not declared as a bean, every Feign call would mint a fresh correlation id, breaking cross-service log correlation. This file is the one-line "publish it as a bean" glue.
- **Spring annotations**:
  - `@Configuration`: source of Spring beans.
  - `@Bean` (on `correlationIdInterceptor()`): registers the interceptor.
- **What it calls**: `new FeignCorrelationIdInterceptor()` (from `com.samato.shared.observability`, see [shared-and-kafka.md](./shared-and-kafka.md)).
- **What calls it**: Spring at startup. Feign's runtime then calls the interceptor on every outbound call.
- **Configuration keys**: none.

### 3.6 `domain/CustomerProfile.java`

- **What it is**: a JPA entity mapping the `customer_profiles` table.
- **Why it exists**: customers have a different shape of profile than drivers or restaurant owners — name, phone, photo, **preferences** (a JSONB column for free-form per-user settings). Splitting it out keeps each table tight.
- **Spring annotations**:
  - `@Entity`: makes this class a JPA entity (Hibernate will manage it).
  - `@Table(name = "customer_profiles")`: explicit table name.
- **What it calls**: nothing.
- **What calls it**: `CustomerProfileController` (constructs and saves it), `CustomerProfileRepository` (returns it from queries). See also the migration that creates its table: `db/migration/V1__init.sql`.
- **Configuration keys**: none (column types are pinned via `columnDefinition`, e.g. `columnDefinition = "uuid"` and `columnDefinition = "jsonb"`).

The `@PrePersist` and `@PreUpdate` hooks set the `createdAt` / `updatedAt` timestamps — Hibernate calls them automatically.

### 3.7 `domain/CustomerProfileRepository.java`

- **What it is**: a Spring Data JPA repository for `CustomerProfile`.
- **Why it exists**: the standard "I need find/save/delete" interface. Spring Data builds the implementation at startup from the method names.
- **Spring annotations**: none on the class itself (Spring Data picks it up because it extends `JpaRepository` and is in a scanned package).
- **What it calls**: `CustomerProfile` (the entity).
- **What calls it**: `CustomerProfileController` (the only caller).
- **Configuration keys**: none.

The custom method `findByUserId(UUID userId)` becomes a `SELECT ... FROM customer_profiles WHERE user_id = ?` query — Spring Data derives the JPQL from the method name.

### 3.8 `domain/DriverProfile.java`

- **What it is**: a JPA entity mapping the `driver_profiles` table. Holds vehicle info, on-duty flag, and current GPS coordinates.
- **Why it exists**: drivers have a different profile again — vehicle, license plate, and **real-time location**. The `on_duty` boolean drives a partial index for the "who's working right now?" query.
- **Spring annotations**:
  - `@Entity`, `@Table(name = "driver_profiles")` — same as `CustomerProfile`.
- **What it calls**: nothing.
- **What calls it**: `DriverProfileController` and `DriverProfileRepository`.
- **Configuration keys**: none.

The class javadoc flags the obvious scaling problem: location updates are write-heavy in production. The bible puts the long-term plan (Redis + periodic Postgres snapshot) in `INTERVIEW-NOTES.md`.

### 3.9 `domain/DriverProfileRepository.java`

- **What it is**: Spring Data JPA repository for `DriverProfile`. Has one custom method, `findByOnDutyTrue()`.
- **Why it exists**: standard CRUD plus the "list on-duty drivers" query.
- **Spring annotations**: none.
- **What it calls**: `DriverProfile` (the entity).
- **What calls it**: `DriverProfileController.listOnDuty()`.
- **Configuration keys**: none.

The `findByOnDutyTrue()` method hits the partial index `idx_driver_profiles_on_duty ... WHERE on_duty = TRUE` created by the V1 migration.

### 3.10 `domain/RestaurantOwnerProfile.java`

- **What it is**: JPA entity mapping `restaurant_owner_profiles`. Holds business-level data: business name, contact email/phone, tax ID.
- **Why it exists**: restaurant owners have a different profile shape from customers and drivers. The **restaurants themselves** (name, address, menu) live in `restaurant-service` — this entity is the **link** between a user account and the businesses they own.
- **Spring annotations**:
  - `@Entity`, `@Table(name = "restaurant_owner_profiles")`.
- **What it calls**: nothing.
- **What calls it**: `RestaurantOwnerProfileController` and `RestaurantOwnerProfileRepository`.
- **Configuration keys**: none.

### 3.11 `domain/RestaurantOwnerProfileRepository.java`

- **What it is**: empty Spring Data JPA repository — inherits the standard `findById`, `save`, `findAll`, `delete` from `JpaRepository<RestaurantOwnerProfile, UUID>`. **No custom query methods.**
- **Why it exists**: the controller only ever looks up the profile by `userId` (the PK) and saves it back, so no custom finder is needed.
- **Spring annotations**: none.
- **What it calls**: `RestaurantOwnerProfile`.
- **What calls it**: `RestaurantOwnerProfileController`.
- **Configuration keys**: none.

### 3.12 `domain/Role.java`

- **What it is**: a four-value enum: `CUSTOMER`, `RESTAURANT_OWNER`, `DRIVER`, `ADMIN`.
- **Why it exists**: documents the role names used in `@PreAuthorize("hasRole('...')")` so there's a single source of truth inside this service. The class javadoc explicitly states this is a **deliberate duplication** of the same enum in `auth-service`; the trade-off is that adding a role to one without the other becomes a compile error in any place that imports both.
- **Spring annotations**: none.
- **What it calls**: nothing.
- **What calls it**: nothing in source today (it's documentation/typedef only — `@PreAuthorize` expressions are strings). It is **not** annotated with anything that makes Spring enumerate or expose it.
- **Configuration keys**: none.

### 3.13 `web/CustomerProfileController.java`

- **What it is**: the `@RestController` exposing `/api/users/me` and `/api/users/{id}`.
- **Why it exists**: this is the **HTTP face** of the customer-profile aggregate. The controller is the only place that turns HTTP requests into repository calls.
- **Spring annotations**:
  - `@RestController`: this class is a web controller; its methods return JSON bodies (not view names).
  - `@RequestMapping("/api/users")`: every method in this class is rooted at `/api/users`.
  - `@GetMapping("/me")`, `@PutMapping("/me")`, `@GetMapping("/{id}")`: per-method HTTP method + path.
  - `@PreAuthorize("isAuthenticated()")` and `@PreAuthorize("hasRole('ADMIN') or authentication.name == #id.toString()")`: enforce authorization per method. Plain English: the first two endpoints just need a valid JWT; the third needs either `ROLE_ADMIN` or the JWT subject must match the `{id}` in the path (i.e. you can only read your own profile).
  - `@AuthenticationPrincipal Jwt jwt`: a method-argument annotation that injects the parsed JWT into the method. We read `jwt.getSubject()` (the `sub` claim = the user's UUID) to know who's calling.
  - `@Valid @RequestBody UpdateProfileRequest req`: `@Valid` triggers Jakarta Bean Validation on the request body (the constraints on `UpdateProfileRequest`); `@RequestBody` deserializes the JSON body into the record.
- **What it calls**: `CustomerProfileRepository` (constructor-injected `repo`). The `createSkeletonProfile` helper uses `DomainException` (from `com.samato.shared.errors`, see [shared-and-kafka.md](./shared-and-kafka.md)) when looking up an unknown user by id.
- **What calls it**: the API gateway, after it has validated the JWT and routed `/api/users/**` to this service.
- **Configuration keys**: none (the JWT decoder and PreAuthorize rules come from `SecurityConfig` and the JWT claims themselves).

The `getOrCreate` pattern (lines 42–45) is worth understanding: the first time a new user calls `/api/users/me`, the profile row doesn't exist yet. Rather than 404, the code creates a "skeleton" profile using the email's local part as the default display name. The user can then PUT to change the defaults. This avoids forcing a "complete your profile" wizard on day one.

### 3.14 `web/DriverProfileController.java`

- **What it is**: `@RestController` for `/api/users/drivers/**` — driver profile read, update, GPS update, and the "on-duty list" query.
- **Why it exists**: the driver profile has its own authorization rules (only `DRIVER` can read/write their own profile; `DRIVER` and `ADMIN` can list on-duty drivers) and its own data shape (vehicle, GPS, on-duty flag), so it gets its own controller.
- **Spring annotations**:
  - `@RestController`, `@RequestMapping("/api/users/drivers")`, `@GetMapping`/`@PutMapping` (one per method) — same plain-English meaning as in the customer controller.
  - `@PreAuthorize("hasRole('DRIVER')")` and `@PreAuthorize("hasRole('DRIVER') or hasRole('ADMIN')")`: per-method role gate.
  - `@AuthenticationPrincipal Jwt jwt`, `@Valid @RequestBody UpdateRequest req`, `@Valid @RequestBody LocationUpdate req`, `@PathVariable UUID id` (only used implicitly via `getOrCreate` reading the JWT subject) — same as in the customer controller.
- **What it calls**: `DriverProfileRepository` (constructor-injected).
- **What calls it**: the API gateway, for any path under `/api/users/drivers/**`.
- **Configuration keys**: none.

Note: `updateLocation` is the hottest write path in the service (drivers push GPS every few seconds). See the scaling note in `INTERVIEW-NOTES.md` and the `currentLatitude`/`currentLongitude` javadoc on `DriverProfile`.

### 3.15 `web/RestaurantOwnerProfileController.java`

- **What it is**: `@RestController` for `/api/users/restaurant-owners/**` — owner profile read and update.
- **Why it exists**: same pattern as the other two — owners have their own data shape and their own role gate (`RESTAURANT_OWNER`).
- **Spring annotations**:
  - `@RestController`, `@RequestMapping("/api/users/restaurant-owners")`, `@GetMapping("/me")`, `@PutMapping("/me")`, `@PreAuthorize("hasRole('RESTAURANT_OWNER')")`, `@AuthenticationPrincipal Jwt jwt`, `@Valid @RequestBody UpdateRequest req` — all explained above.
- **What it calls**: `RestaurantOwnerProfileRepository` (constructor-injected).
- **What calls it**: the API gateway, for any path under `/api/users/restaurant-owners/**`.
- **Configuration keys**: none.

### 3.16 `application.yml` (configuration walkthrough)

`application.yml` is the **single file** under `src/main/resources/` for this service. Below is every key with a plain-English gloss.

| Key | Value | Plain-English meaning |
|---|---|---|
| `server.port` | `8081` | The TCP port this service listens on. The API gateway route and Eureka registration use this name. |
| `server.shutdown` | `graceful` | When the service gets a `SIGTERM`, finish in-flight requests before exiting. Important for K8s rolling deploys. |
| `spring.application.name` | `samato-user-service` | The app id used by Eureka (so other services can find this one as `http://samato-user-service/`) and by log lines. |
| `spring.cloud.config.enabled` | `false` | Don't try to fetch config from `config-service` on startup. The comment in the file says "flip on when config-service is up" — currently this service reads its own local `application.yml` only. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/user_service` | JDBC URL for the Postgres database. In Docker compose it's overridden to `jdbc:postgresql://postgres:5432/user_service`. |
| `spring.datasource.username` / `password` | `fd` / `fd` | Database credentials (set up by `init-databases.sh` in the compose setup). |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate will **check** that the JPA entity columns match the database columns on startup but will **not** create or alter tables. Schema is owned by Flyway. |
| `spring.flyway.enabled` | `true` | Turn on Flyway database migrations. |
| `spring.flyway.locations` | `classpath:db/migration` | Where to look for migration `.sql` files. The classpath here means `services/user-service/src/main/resources/db/migration/`. |
| `spring.flyway.baseline-on-migrate` | `true` | If a database already has tables but no Flyway schema history, create the history table without erroring. Useful for first-time runs. |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `http://localhost:9000/.well-known/jwks.json` | URL the JWT decoder uses to download auth-service's public key. In compose: `http://auth-service:9000/.well-known/jwks.json`. |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | The Eureka server URL. (The `config-repo/application.yml` shipped with the project sets this globally; the service-local yml is set to localhost for dev.) |
| `spring.cloud.openfeign.client.config.default.connect-timeout` | `2000` | How long Feign waits (ms) for the TCP connection to auth-service before failing. |
| `spring.cloud.openfeign.client.config.default.read-timeout` | `5000` | How long Feign waits (ms) for a response body after the connection is open. |
| `spring.cloud.openfeign.client.config.default.logger-level` | `BASIC` | Feign logs the request method + URL + response status (not headers or bodies). |
| `resilience4j.circuitbreaker.instances.authService.sliding-window-size` | `10` | The circuit breaker looks at the last 10 calls to decide if the failure rate is bad. |
| `resilience4j.circuitbreaker.instances.authService.failure-rate-threshold` | `50` | If 50% of the last 10 calls failed, open the circuit (stop sending calls for a while). |
| `resilience4j.circuitbreaker.instances.authService.wait-duration-in-open-state` | `10s` | After the circuit opens, wait 10 seconds before letting one call through to test. |
| `resilience4j.circuitbreaker.instances.authService.permitted-number-of-calls-in-half-open-state` | `3` | When testing, allow 3 calls through; if they succeed, close the circuit again. |
| `resilience4j.retry.instances.authService.max-attempts` | `3` | Retry up to 3 times on a failed call. |
| `resilience4j.retry.instances.authService.wait-duration` | `500ms` | Initial backoff between retries. |
| `resilience4j.retry.instances.authService.enable-exponential-backoff` | `true` | Each retry waits longer than the last (500ms, 1s, 2s...). |
| `resilience4j.retry.instances.authService.exponential-backoff-multiplier` | `2` | The factor the wait time is multiplied by between attempts. |
| `resilience4j.timelimiter.instances.authService.timeout-duration` | `3s` | A single call to auth-service must complete within 3s, otherwise it's cancelled. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Which Spring Boot Actuator endpoints are exposed over HTTP. `health` and `info` for K8s probes; `prometheus` and `metrics` for observability. |
| `management.endpoint.health.probes.enabled` | `true` | Adds the K8s-style `/actuator/health/liveness` and `/actuator/health/readiness` endpoints. |
| `management.tracing.sampling.probability` | `1.0` | Sample 100% of traces (dev/staging value; in production you'd lower this). |
| `management.zipkin.tracing.endpoint` | `http://localhost:9411/api/v2/spans` | Where to ship the collected spans. In compose: `http://zipkin:9411/api/v2/spans`. |
| `logging.pattern.level` | `"%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"` | Log line prefix includes the service name and the current trace/span/correlation id (so a single request can be followed across services). |
| `logging.level.com.samato` | `DEBUG` | Turn on DEBUG for our own packages. |

## 4. Endpoints (controllers)

All endpoints are reached through the **API gateway** at `http://localhost:8080/api/users/...`. The gateway's `JwtAuthFilter` validates the JWT and forwards the request. If you skip the gateway and call `http://localhost:8081/api/users/...` directly, the request still works as long as you send a valid JWT — `user-service` validates locally against auth-service's JWKS, so it doesn't need the gateway in the path.

The full endpoint catalog is also in the cross-service inventory under `byUseCase.auth-flow` — see [docs/inventory/endpoints-and-use-cases.json](../inventory/endpoints-and-use-cases.json).

### 4.1 `CustomerProfileController` — base path `/api/users`

#### `GET /api/users/me` — `getMine`

- **AuthZ**: `@PreAuthorize("isAuthenticated()")` — any logged-in user.
- **Request**: none (the JWT in the `Authorization` header is the only input).
- **Response**: a `CustomerProfile` JSON body:
  ```json
  {
    "userId": "11111111-1111-1111-1111-111111111111",
    "displayName": "alice",
    "phone": "+1-555-0100",
    "photoUrl": "https://cdn.samato.example/avatars/alice.png",
    "preferencesJson": "{\"cuisine\":\"thai\"}",
    "createdAt": "2026-07-01T12:00:00Z",
    "updatedAt": "2026-07-08T09:30:00Z"
  }
  ```
- **Downstream effects**: a single `SELECT` against `customer_profiles`; if no row exists, an `INSERT` (skeleton) is performed and returned. **The user's UUID comes from `jwt.getSubject()`** (the `sub` claim).
- **Example curl**:
  ```bash
  curl -sS http://localhost:8080/api/users/me \
    -H "Authorization: Bearer $JWT"
  ```

#### `PUT /api/users/me` — `updateMine`

- **AuthZ**: `@PreAuthorize("isAuthenticated()")`.
- **Request body** (`UpdateProfileRequest` record):
  ```json
  { "displayName": "Alice", "phone": "+1-555-0100", "photoUrl": "https://..." }
  ```
  All three fields are optional. `displayName` is constrained to 1–100 chars, `phone` to ≤30, `photoUrl` to ≤500 (Jakarta Bean Validation).
- **Response**: the updated `CustomerProfile` JSON.
- **Downstream effects**: a `SELECT` (or skeleton `INSERT`); a partial `UPDATE` of only the fields you sent; `repo.save(...)`.
- **Example curl**:
  ```bash
  curl -sS -X PUT http://localhost:8080/api/users/me \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"displayName":"Alice","phone":"+1-555-0100"}'
  ```

#### `GET /api/users/{id}` — `getById`

- **AuthZ**: `@PreAuthorize("hasRole('ADMIN') or authentication.name == #id.toString()")` — admin, or the user themselves (the JWT subject must equal the path `id`).
- **Request**: path variable `id` is a UUID.
- **Response**: `CustomerProfile` JSON. If the user has no customer profile and you're not them, you get a `404` via `DomainException("USER_NOT_FOUND", "User not found", 404)` — handled by the shared `GlobalExceptionHandler` (see [shared-and-kafka.md](./shared-and-kafka.md)).
- **Downstream effects**: a `SELECT` against `customer_profiles`.
- **Example curl** (admin):
  ```bash
  curl -sS http://localhost:8080/api/users/11111111-1111-1111-1111-111111111111 \
    -H "Authorization: Bearer $ADMIN_JWT"
  ```

### 4.2 `DriverProfileController` — base path `/api/users/drivers`

#### `GET /api/users/drivers/me` — `getMine`

- **AuthZ**: `@PreAuthorize("hasRole('DRIVER')")`.
- **Request**: none.
- **Response**: a `DriverProfile` JSON:
  ```json
  {
    "userId": "22222222-2222-2222-2222-222222222222",
    "fullName": "driver-22222222",
    "vehicleType": "BIKE",
    "licensePlate": "ABC-123",
    "onDuty": true,
    "currentLatitude": 37.7749,
    "currentLongitude": -122.4194,
    "createdAt": "2026-07-01T12:00:00Z",
    "updatedAt": "2026-07-15T08:15:00Z"
  }
  ```
- **Downstream effects**: skeleton `INSERT` on first call, otherwise `SELECT`.
- **Example curl**:
  ```bash
  curl -sS http://localhost:8080/api/users/drivers/me \
    -H "Authorization: Bearer $DRIVER_JWT"
  ```

#### `PUT /api/users/drivers/me` — `updateMine`

- **AuthZ**: `@PreAuthorize("hasRole('DRIVER')")`.
- **Request body** (`UpdateRequest` record, all fields nullable):
  ```json
  { "fullName": "Bob", "vehicleType": "SCOOTER", "licensePlate": "XYZ-987", "onDuty": true }
  ```
- **Response**: the updated `DriverProfile`.
- **Downstream effects**: `SELECT` or skeleton `INSERT`; partial `UPDATE`.
- **Example curl**:
  ```bash
  curl -sS -X PUT http://localhost:8080/api/users/drivers/me \
    -H "Authorization: Bearer $DRIVER_JWT" \
    -H "Content-Type: application/json" \
    -d '{"vehicleType":"SCOOTER","onDuty":true}'
  ```

#### `PUT /api/users/drivers/me/location` — `updateLocation`

- **AuthZ**: `@PreAuthorize("hasRole('DRIVER')")`.
- **Request body** (`LocationUpdate` record):
  ```json
  { "latitude": 37.7749, "longitude": -122.4194 }
  ```
  Both `@NotNull` (Jakarta Bean Validation).
- **Response**: the updated `DriverProfile`.
- **Downstream effects**: one `SELECT` + one `UPDATE` against `driver_profiles`. **This is the hot path**; in production this will write to Redis instead of Postgres (see §7).
- **Example curl**:
  ```bash
  curl -sS -X PUT http://localhost:8080/api/users/drivers/me/location \
    -H "Authorization: Bearer $DRIVER_JWT" \
    -H "Content-Type: application/json" \
    -d '{"latitude":37.7749,"longitude":-122.4194}'
  ```

#### `GET /api/users/drivers/on-duty` — `listOnDuty`

- **AuthZ**: `@PreAuthorize("hasRole('DRIVER') or hasRole('ADMIN')")`.
- **Request**: none.
- **Response**: a JSON array of `DriverProfile` objects (one per `on_duty = TRUE` row).
- **Downstream effects**: a single `SELECT ... WHERE on_duty = TRUE` (hits the partial index `idx_driver_profiles_on_duty`).
- **Example curl**:
  ```bash
  curl -sS http://localhost:8080/api/users/drivers/on-duty \
    -H "Authorization: Bearer $JWT"
  ```

### 4.3 `RestaurantOwnerProfileController` — base path `/api/users/restaurant-owners`

#### `GET /api/users/restaurant-owners/me` — `getMine`

- **AuthZ**: `@PreAuthorize("hasRole('RESTAURANT_OWNER')")`.
- **Request**: none.
- **Response**: a `RestaurantOwnerProfile` JSON:
  ```json
  {
    "userId": "33333333-3333-3333-3333-333333333333",
    "businessName": "My Business",
    "contactEmail": "owner@example.com",
    "contactPhone": "+1-555-0200",
    "taxId": "12-3456789",
    "createdAt": "2026-07-01T12:00:00Z",
    "updatedAt": "2026-07-08T09:30:00Z"
  }
  ```
- **Downstream effects**: `SELECT` or skeleton `INSERT`.
- **Example curl**:
  ```bash
  curl -sS http://localhost:8080/api/users/restaurant-owners/me \
    -H "Authorization: Bearer $OWNER_JWT"
  ```

#### `PUT /api/users/restaurant-owners/me` — `updateMine`

- **AuthZ**: `@PreAuthorize("hasRole('RESTAURANT_OWNER')")`.
- **Request body** (`UpdateRequest` record):
  ```json
  { "businessName": "Acme Eats", "contactEmail": "ops@acme.com", "contactPhone": "+1-555-0200", "taxId": "12-3456789" }
  ```
  `businessName` is `@NotBlank`, `contactEmail` is `@Email`, the rest are optional strings.
- **Response**: the updated `RestaurantOwnerProfile`.
- **Downstream effects**: `SELECT` or skeleton `INSERT`; partial `UPDATE`.
- **Example curl**:
  ```bash
  curl -sS -X PUT http://localhost:8080/api/users/restaurant-owners/me \
    -H "Authorization: Bearer $OWNER_JWT" \
    -H "Content-Type: application/json" \
    -d '{"businessName":"Acme Eats","contactEmail":"ops@acme.com"}'
  ```

## 5. Database schema

This service has a single Flyway migration: `services/user-service/src/main/resources/db/migration/V1__init.sql`. It creates **three tables**, one per profile type, plus two indexes.

### 5.1 `customer_profiles` (created by V1__init.sql, lines 6–16)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `user_id` | `UUID` | `PRIMARY KEY` | Same UUID as auth-service's user id. |
| `display_name` | `VARCHAR(100)` | `NOT NULL` | |
| `phone` | `VARCHAR(30)` | nullable | |
| `photo_url` | `VARCHAR(500)` | nullable | |
| `preferences_json` | `JSONB` | nullable | Postgres-native JSON; serialized as a string from the Java side. |
| `created_at` | `TIMESTAMP` | `NOT NULL` | Set by `@PrePersist`. |
| `updated_at` | `TIMESTAMP` | `NOT NULL` | Set by `@PrePersist` and `@PreUpdate`. |

- **Indexes**: `idx_customer_profiles_user_id` on `(user_id)`. (The PK already covers lookups by user_id, so this is mostly defensive.)
- **Writes to it**: `CustomerProfileController` (skeleton insert in `getOrCreate`, update in `updateMine`).
- **Reads from it**: `CustomerProfileController.getMine`, `updateMine`, `getById`.

### 5.2 `driver_profiles` (created by V1__init.sql, lines 18–30)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `user_id` | `UUID` | `PRIMARY KEY` | |
| `full_name` | `VARCHAR(100)` | `NOT NULL` | |
| `vehicle_type` | `VARCHAR(20)` | `NOT NULL` | Convention: `BIKE` / `SCOOTER` / `CAR`. |
| `license_plate` | `VARCHAR(20)` | nullable | |
| `on_duty` | `BOOLEAN` | `NOT NULL DEFAULT FALSE` | |
| `current_latitude` | `DOUBLE PRECISION` | nullable | |
| `current_longitude` | `DOUBLE PRECISION` | nullable | |
| `created_at` / `updated_at` | `TIMESTAMP` | `NOT NULL` | |

- **Indexes**: `idx_driver_profiles_on_duty` on `(on_duty) WHERE on_duty = TRUE` — a **partial index** that makes the on-duty query O(drivers-on-duty) instead of O(drivers-total).
- **Writes to it**: `DriverProfileController` (skeleton insert in `getOrCreate`; updates in `updateMine`, `updateLocation`).
- **Reads from it**: `DriverProfileController.getMine`, `updateMine`, `updateLocation`, `listOnDuty`.

### 5.3 `restaurant_owner_profiles` (created by V1__init.sql, lines 32–40)

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `user_id` | `UUID` | `PRIMARY KEY` | |
| `business_name` | `VARCHAR(200)` | `NOT NULL` | |
| `contact_email` | `VARCHAR(255)` | `NOT NULL` | |
| `contact_phone` | `VARCHAR(30)` | nullable | |
| `tax_id` | `VARCHAR(50)` | nullable | |
| `created_at` / `updated_at` | `TIMESTAMP` | `NOT NULL` | |

- **Indexes**: none beyond the PK.
- **Writes to it**: `RestaurantOwnerProfileController` (skeleton insert in `getOrCreate`, update in `updateMine`).
- **Reads from it**: `RestaurantOwnerProfileController.getMine`, `updateMine`.

**Foreign keys**: there are **no foreign keys** between any of these tables and any other service's tables. The comment in V1__init.sql (lines 1–4) and the javadoc on `CustomerProfile` both explain: services don't share databases; the `user_id` UUID is a **logical reference** to auth-service's `users` table. The "join" is done in code at the gateway or via Feign.

## 6. Kafka integration

**This service does not publish or consume any Kafka topics.** There is no `KafkaTemplate`, no `@KafkaListener`, no outbox table, and no `shared-kafka` dependency in `pom.xml`.

If you need to add Kafka integration in the future, the patterns to follow are:
- For **publishing events** (e.g. emitting `UserCreated` when a profile is first materialized), look at the order-service outbox in `docs/inventory/call-graph.json` (`outboxTables` array, entry for `order-service`/`outbox_events`).
- For **consuming events**, look at `services/search-service` which has a `@KafkaListener`-based projection pattern.
- For the shared producer/consumer beans and the Avro event classes, see [shared-and-kafka.md](./shared-and-kafka.md).

The reason this service has no Kafka is that **none of its current behavior is interesting to other services asynchronously** — profiles are read on demand, and the gateway composes auth + profile in the synchronous request path.

## 7. Common "if you change X, also update Y" notes

These are the gotchas that will cost a beginner hours if you don't know about them.

### 7.1 `AuthClient` is defined but not injected anywhere

`AuthClient` (with its `AuthClientFallback`, Feign config, and Resilience4j `authService` block) is fully wired — but **no controller or service injects it today**. If you grep for `AuthClient ` (with a trailing space) you'll find zero call sites. The javadoc on `AuthClient` says these are intended for Phase 7 (the federated "check the user exists" pattern). If you add a feature that needs to validate a userId against auth-service:

- Inject `AuthClient` (constructor injection) into the new class.
- The fallback is already wired (`AuthClientFallback.me` throws `DomainException("AUTH_UNREACHABLE", ..., 503)`); catch that in your caller.
- The Resilience4j `authService` policy in `application.yml` is the one that will wrap the call — **but the @CircuitBreaker / @Retry annotations are not yet on the method** (the class javadoc on `AuthClient` says these are planned for Phase 7). So today, the timeouts/retries in `application.yml` are **not** automatically applied to `AuthClient.me(...)`. They take effect only when you add the annotations or wrap the call in a `Resilience4jFeign` builder.

### 7.2 JWT subject is the `userId` — every controller relies on this

Every controller reads the user's UUID from `jwt.getSubject()`. This works because `auth-service` sets the `sub` claim to the user's UUID at token issuance. If you change the claim name in `auth-service` (e.g. to `userId`) you must update **all three controllers** (`CustomerProfileController`, `DriverProfileController`, `RestaurantOwnerProfileController`) — they each call `UUID.fromString(jwt.getSubject())`.

### 7.3 The `roles` JWT claim must use the names in `Role.java`

`SecurityConfig.jwtAuthenticationConverter()` reads the `roles` claim and prefixes with `ROLE_`. So `hasRole('DRIVER')` requires the JWT to contain `"roles": ["DRIVER"]`. If you add a new role in `auth-service` (e.g. `SUPPORT`), you must:

1. Add the new value to `Role.java` in this service (the javadoc explicitly calls this out as the "compile error will save you" coupling).
2. Make sure auth-service issues the claim value **uppercase** and **without the `ROLE_` prefix** (the prefix is added here).
3. Use `hasRole('SUPPORT')` in any new `@PreAuthorize`.

### 7.4 Skeleton creation is silent

Each `getOrCreate` method creates a row on first access with sensible defaults (email local-part as display name, `"My Business"`, `"driver-XXXXXXXX"`, `BIKE` vehicle). If you write a test that asserts "no row exists after a fresh GET", you'll be surprised — the first GET **creates** the row. If you need to assert the no-row state, hit the DB directly, not the controller.

### 7.5 The two YML keys that look like they belong to different sections

`application.yml` has a comment header on line 10 (`# for local dev; flip on when config-service is up`) and then a deeply nested `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` block. The file also has an `eureka.client.service-url` block outside the `spring:` block. When editing, watch the YAML indentation: the `spring.security...` block is **inside** `spring:`, but the `eureka:`, `resilience4j:`, `management:`, and `logging:` blocks are **siblings** of `spring:`. Merging indentation mistakes will cause a silent failure (the `jwk-set-uri` may revert to a default that points at `localhost:9000` even in compose).

### 7.6 `pom.xml` depends on `shared` but not `shared-kafka`

This service intentionally has no Kafka dependency. If you add a Kafka producer or consumer, you must add `com.samato:shared-kafka` to `pom.xml` (and follow the patterns in [shared-and-kafka.md](./shared-and-kafka.md)). Don't try to add `spring-kafka` directly — the team standardizes on the `shared-kafka` bean factories.

### 7.7 The `Role` enum is duplicated on purpose

`Role.java` in this service and the equivalent in `auth-service` are **separate files with the same values**. The javadoc on `Role` says: "if the role set grows in one and not the other, this won't compile, which is a feature not a bug — it forces the conversation." If you find yourself wanting to "deduplicate" them, stop and read the comment first. A shared library is a coupling; two duplicate enums are a duplication. The team chose duplication at four roles.

### 7.8 `AuthClientFallback` is wired but never called today

`AuthClientFallback` is registered as the fallback on `@FeignClient(name = "samato-auth-service", fallback = AuthClientFallback.class)`. But because no code injects `AuthClient` (see §7.1), the fallback's `me(String bearer)` method is **never invoked in practice**. If you add a caller and see a `DomainException("AUTH_UNREACHABLE", ..., 503)`, that came from this fallback — auth-service was down or slow.

### 7.9 The Spring Security CSRF disable + stateless combo

`SecurityConfig` sets `.csrf(...disable)` and `.sessionManagement(...STATELESS)`. If you ever add a non-JSON endpoint (e.g. a form-login page) to this service, **don't** rely on the existing filter chain — CSRF and session policy will need to be re-thought.

### 7.10 The `ddl-auto: validate` contract

`application.yml` has `spring.jpa.hibernate.ddl-auto: validate`. This means Hibernate will **fail to start** if any `@Entity` column doesn't match a real database column. So if you add a field to `DriverProfile` without adding it to `V2__some_name.sql`, the service won't start. Always add a new Flyway migration for any entity change.

### 7.11 The hot path on `updateLocation`

`DriverProfileController.updateLocation` is a `PUT` to Postgres on every GPS push. In production this will need to move to Redis (write to a `driver:location:{userId}` key with a TTL) and only snapshot to Postgres every N updates. The `DriverProfile` javadoc and `INTERVIEW-NOTES.md` both call this out. Don't add caching in this service without re-reading those notes.

### 7.12 Prometheus scrape job is missing user-service

The Prometheus job in `compose/prometheus/prometheus.yml` (per `docs/inventory/infrastructure.json` line 944) only lists `config-service`, `discovery-service`, and `api-gateway` as static scrape targets. If you want Prometheus to scrape `user-service`'s `/actuator/prometheus`, add it to the `static_configs` block — the comment in the inventory notes that "service discovery via DNS" was the intent but isn't actually wired.

## 8. See also

- **Per-service designer note** (rationale, interview Q&A, scaling trade-offs): [services/user-service/docs/INTERVIEW-NOTES.md](../../services/user-service/docs/INTERVIEW-NOTES.md)
- **Use cases that touch user-service**:
  - [Auth flow](../use-cases/02-auth-flow.md) — every JWT-protected call lands here after the gateway.
  - **Browse and search** [browse-and-search](../use-cases/03-browse-and-search.md) is not a user-service use case, but a future `delivery-service` will consume `/api/users/drivers/on-duty` to assign drivers to orders.
- **Architecture guide** (microservice topology, gateway, service discovery, JWT flow): [../01-architecture-guide.md](../01-architecture-guide.md)
- **How auth works** (the JWT lifecycle, JWKS, role mapping — explains why `user-service` is a resource server): [../02-how-auth-works.md](../02-how-auth-works.md)
- **Shared library walkthrough** (every cross-cutting class — `DomainException`, `GlobalExceptionHandler`, `CorrelationIdFilter`, `FeignCorrelationIdInterceptor`, `MdcKeys`): [./shared-and-kafka.md](./shared-and-kafka.md)
- **Glossary** (every Spring and microservice term used above, defined in plain English): [../00-glossary.md](../00-glossary.md)
- **Top-level project docs** (overview, run-the-bible, status, cheatsheet):
  - [../../ARCHITECTURE.md](../../ARCHITECTURE.md)
  - [../../PROJECT-STATUS.md](../../PROJECT-STATUS.md)
  - [../../RUN-THE-BIBLE.md](../../RUN-THE-BIBLE.md)
  - [../INTERVIEW-CHEATSHEET.md](../INTERVIEW-CHEATSHEET.md)
