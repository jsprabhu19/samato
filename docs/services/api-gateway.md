# api-gateway (port 8080)

> Plain-English purpose: the **front door** of the entire system. Every request from a browser, mobile app, or partner hits this service first; the gateway decides which downstream microservice should handle the call, validates the JWT bearer token, sets a correlation id for tracing, applies CORS and (when activated) rate limits, and forwards the request. It is a textbook example of the **API Gateway** pattern, implemented on Spring Cloud Gateway's reactive (WebFlux) stack.

## 1. Where it sits in the system

```
                  +--------------------+
   client (web,   |   api-gateway      |
   mobile, BFF)-->|  port 8080         |-----> auth-service      (public, no JWT)
                  |  (Eureka SAMATO-   |-----> user-service      (JWT required)
                  |   API-GATEWAY)     |-----> order-service     (JWT required)
                  |                    |-----> restaurant-service(JWT required)
                  |                    |-----> search-service    (JWT required)
                  |                    |-----> payment-service   (JWT for most; HMAC for /api/payments/webhooks/**)
                  +--------------------+
        ^                |
        |  JWKS fetch    |  registers in
        |                v
   auth-service  <----  discovery-service  (Eureka, port 8761)
   port 9000           port 8761
        |
        |  Redis (rate-limit, future Phase 7)
        v
       redis  (port 6379)
```

The api-gateway itself does **not** publish or consume Kafka topics, does **not** own a database, and does **not** run business logic. It is pure infrastructure: routing, security, observability glue. All requests start here and end up at one of the six business services; the gateway is a thin, stateless shim in front of them.

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/api-gateway` |
| Port | `8080` (from `server.port` in `application.yml`) |
| Web stack | Reactive / WebFlux (`spring.main.web-application-type: reactive`) |
| Database(s) | None — the gateway is stateless and never writes to a database |
| Publishes topics | None |
| Consumes topics | None |
| Service discovery | Eureka client; registers as `SAMATO-API-GATEWAY` |
| Routes (defined in `GatewayRoutesConfig`) | `/api/auth/**` → `lb://SAMATO-AUTH-SERVICE`; `/api/users/**` → `lb://SAMATO-USER-SERVICE`; `/api/orders/**` → `lb://SAMATO-ORDER-SERVICE`; `/api/restaurants/**` → `lb://SAMATO-RESTAURANT-SERVICE`; `/api/search/**` → `lb://SAMATO-SEARCH-SERVICE`; `/api/payments/**` → `lb://SAMATO-PAYMENT-SERVICE`; `/actuator/**` → the gateway's own actuator |
| Public paths (no JWT) | `/api/auth/**`, `/actuator/health/**`, `/actuator/info` |
| Depends on | `auth-service` (for JWKS public keys), `discovery-service` (Eureka), `redis` (rate-limit, future), `shared` (correlation-id MDC keys + the `CorrelationIdFilter.HEADER` constant) |
| Spring Security | `oauth2ResourceServer().jwt(...)` — token validated against auth-service's JWKS via `NimbusReactiveJwtDecoder` |
| Health endpoint | `GET http://localhost:8080/actuator/health` |
| Routes endpoint | `GET http://localhost:8080/actuator/gateway/routes` |
| Metrics | `GET http://localhost:8080/actuator/prometheus` |

> **What "depends on" really means here.** The gateway calls `auth-service` over HTTP only to fetch the JWKS (public keys) — never to authenticate a single user request. The user-token validation itself happens locally in-process using the cached JWKS. Eureka is a *registration* dependency, not a runtime-per-request dependency: routes use the cached instance list, which is refreshed every few seconds by the Eureka client.

## 3. File-by-file walkthrough

The api-gateway has eight `.java` files. Each is small, single-purpose, and lives under one of two packages: `com.samato.apigateway.config` (routing, CORS, JWT decoder, correlation id) and `com.samato.apigateway.security` (the actual security filter chain and rate-limit key resolver).

### 3.1 `ApiGatewayApplication.java`

**What it is.** The Spring Boot main class — the entry point that boots the gateway.

**Why it exists.** Every Spring Boot app needs a `main` method that hands control to `SpringApplication.run`. This one is additionally annotated with `@EnableDiscoveryClient` so the gateway registers itself with the Eureka server and can resolve `lb://SERVICE-NAME` URIs in its route table.

**Spring annotations.**

- `@SpringBootApplication` — a meta-annotation that turns on auto-configuration, component scanning (from this package downward), and a single `@Configuration` class. Plain English: "this is a Spring Boot app; please configure me."
- `@EnableDiscoveryClient` — tells Spring Cloud to register this service with the configured discovery server (Eureka, here) and to fetch the registry of other services so that `lb://SERVICE` URIs can be resolved at request time.

**What it calls.** Nothing directly — it is the bootstrap, not a worker. Spring's auto-configuration wires up the actual routes, filters, and security chain based on `@Configuration` classes discovered by component scan.

**What calls it.** The JVM invokes `main(String[])` once at container start. The Docker `ENTRYPOINT` in `services/api-gateway/Dockerfile` invokes this class.

**Configuration keys it reads from `application.yml`.** All of them, indirectly (via Spring Boot's auto-configuration). The directly visible ones are `spring.application.name`, `server.port`, and `spring.cloud.gateway.*`.

### 3.2 `config/CorrelationIdWebFilter.java`

**What it is.** A reactive WebFlux filter that runs at the very top of the request chain (`Ordered.HIGHEST_PRECEDENCE`) and attaches a correlation id to every incoming request.

**Why it exists.** Every log line, every Zipkin span, and every downstream call needs a single `correlationId` value that ties the whole request together. The shared `CorrelationIdFilter` (in the `shared` module) only works on the **servlet** stack — it extends `OncePerRequestFilter` — so it is invisible to WebFlux. This class is the **reactive equivalent** for the gateway. Without it, every downstream service would invent its own correlation id and we would lose end-to-end trace continuity.

**Spring annotations.**

- `@Component` — registers this class as a Spring-managed bean so it is picked up by component scan and added to the WebFlux filter chain automatically.
- `@Order(Ordered.HIGHEST_PRECEDENCE)` — sets the filter to run *before* any other filter (security, gateway routing, you name it). The MDC must be populated before anything else logs.

**What it calls.**

- `com.samato.shared.observability.CorrelationIdFilter.HEADER` (a `public static final String` constant equal to `"X-Correlation-Id"`) — the header name is shared with the servlet filter so every service uses the same header.
- `com.samato.shared.observability.MdcKeys.CORRELATION_ID` — the MDC key under which the id is stored for the duration of the request.
- `org.slf4j.MDC.put` / `MDC.remove` — the SLF4J Mapped Diagnostic Context that adds the id to every log line.
- `reactor.core.publisher.Mono` and `doFinally(...)` — the reactive type that the WebFlux filter chain returns; `doFinally` cleans the MDC after the response is written, even on error.

**What calls it.** The WebFlux runtime invokes it on every request. No Java code calls it directly.

**Configuration keys it reads from `application.yml`.** None directly. The correlation-id is auto-generated if the caller did not send `X-Correlation-Id`; the only thing the filter reads is the HTTP header of the same name.

### 3.3 `config/CorsConfig.java`

**What it is.** A Spring `@Configuration` that registers a `CorsWebFilter` bean allowing cross-origin requests from any origin.

**Why it exists.** When a browser-based frontend (the Vite dev server on port 5173, the production React app, etc.) calls the gateway from a different origin, the browser refuses the response unless the server sends the right CORS headers. This filter adds those headers. The `allowedOriginPatterns: "*"` is dev-friendly; the comment in the file flags it as something to lock down in production.

**Spring annotations.**

- `@Configuration` — marks this class as a source of Spring beans.
- `@Bean` (on the method) — registers the `CorsWebFilter` as a Spring bean so the WebFlux filter chain picks it up.

**What it calls.** None — this is a bean factory. The `CorsWebFilter` is constructed in place using `CorsConfiguration` and `UrlBasedCorsConfigurationSource`.

**What calls it.** Spring's WebFlux filter chain. The `CorsWebFilter` is auto-registered as a global filter on the reactive server.

**Configuration keys it reads from `application.yml`.** None — the CORS rules are hard-coded in Java. Note that `application.yml` also defines a `spring.cloud.gateway.globalcors` block; the `CorsConfig` bean's `UrlBasedCorsConfigurationSource` for `/**` overlaps with that YAML. In practice the YAML config and the bean config both apply; the bean is more explicit and easier to read in code review.

### 3.4 `config/GatewayRoutesConfig.java`

**What it is.** The routing table. This is the file interviewers point at when they ask "show me your routing config."

**Why it exists.** Spring Cloud Gateway is just a filter pipeline plus a route matcher until you tell it which paths go where. This class is where each public path prefix (`/api/orders/**`, `/api/auth/**`, etc.) is bound to a downstream Eureka service id. The `lb://` prefix is what tells the gateway to look the service up in Eureka and load-balance across its healthy instances.

**Spring annotations.**

- `@Configuration` — bean-source marker.
- `@Bean` (on the `routes` method) — registers a `RouteLocator` bean. Spring Cloud Gateway consumes that bean to build its internal routing table at startup.

**What it calls.**

- `RouteLocatorBuilder` (constructor-injected by Spring) — fluent API for declaring routes.
- For every route: `path(...)` (a predicate matching the request path), `filters(f -> f.addRequestHeader("X-Source", "samato-api-gateway"))` (adds a debugging header to the forwarded request so logs and access logs in downstream services can be told apart from direct calls), and `uri("lb://SERVICE-NAME")` (the Eureka-relative target).

**What calls it.** Spring Cloud Gateway's `GatewayAutoConfiguration` consumes the `RouteLocator` bean at startup and registers each route as a `Predicate` + `Filter` pair in the reactive server. Requests are matched at runtime by path.

**Configuration keys it reads from `application.yml`.** None — all routes are hard-coded in Java. The `spring.cloud.gateway.discovery.locator.enabled: false` line in YAML tells Spring Cloud Gateway *not* to auto-create a route per registered Eureka service; we want the explicit table for auditability and per-route filter control.

**Notable gotcha — `stripPrefix` mismatch.** The comment block in this file documents an inconsistency: the auth/user/order/payment routes *do not* use `stripPrefix(1)`, so the `/api` prefix is forwarded as-is. The downstream services' controllers are mapped at `/api/orders/...`, so this happens to work. If you ever add a new service, copy the existing pattern (no `stripPrefix`, controllers mapped at `/api/...`).

### 3.5 `config/JwtConfig.java`

**What it is.** A bean factory for a `ReactiveJwtDecoder` that fetches the signing keys from `auth-service`'s JWKS endpoint.

**Why it exists.** The gateway is the **edge** of the system; it is the one place that should validate the JWT once and tell downstream services "this request is authenticated as user X." The decoder fetches the public keys from `auth-service` over HTTP, caches them, and verifies each bearer token's signature locally. This way, every protected request avoids a round-trip to `auth-service` for the actual validation — only the key fetch is HTTP, and Spring caches the JWKS internally so a `kid` miss triggers a single refetch.

**Spring annotations.**

- `@Configuration` — bean-source marker.
- `@Bean` (on `jwtDecoder`) — registers the decoder as a Spring bean.
- `@Value(...)` (parameter-level) — injects the `jwk-set-uri` property from `application.yml` (or, more usually, the `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` environment variable that the Docker compose file passes in).

**What it calls.**

- `NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()` — Spring Security's reactive JWT decoder, backed by the Nimbus JOSE library. The *servlet* equivalent would be `NimbusJwtDecoder`; the two are separate types because the gateway is reactive.

**What calls it.** Spring Security's `oauth2ResourceServer().jwt(...)` configuration in `SecurityConfig` picks up the bean by type. `JwtAuthFilter` also injects it directly so it can re-validate and then forward.

**Configuration keys it reads from `application.yml` / environment.**

- `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` — the URL of the JWKS endpoint, e.g. `http://auth-service:9000/.well-known/jwks.json` in Docker and `http://localhost:9000/.well-known/jwks.json` for local dev.

### 3.6 `security/JwtAuthFilter.java`

**What it is.** A `GlobalFilter` (a gateway-level filter that runs on every route) that does two things: (1) re-validates the JWT, and (2) injects `X-User-Id` and `X-User-Roles` headers into the forwarded request so downstream services can authorize without parsing the JWT themselves.

**Why it exists.** Once Spring Security's reactive resource-server filter has accepted the token, this filter runs as part of the Spring Cloud Gateway chain and **decorates** the forwarded request. Downstream services then have a flat header to read (`X-User-Id`) instead of re-parsing the token. This is the standard "trusted-gateway" pattern: the gateway is the one place that does the cryptographic check; everywhere else trusts the gateway-injected headers.

**Spring annotations.**

- `@Component` — registers the filter as a Spring bean so Spring Cloud Gateway picks it up.
- `@Override` (on `filter` and `getOrder`) — standard Java marker that the method is overriding an interface method; no Spring meaning.

**What it calls.**

- `ReactiveJwtDecoder` (constructor-injected) — same bean defined in `JwtConfig`. The filter calls `.decode(token)` which returns a `Mono<Jwt>`.
- `exchange.mutate().request(r -> r.header(...))` — the reactive API for mutating the request before forwarding to the next filter in the chain.

**What calls it.** Spring Cloud Gateway's filter chain invokes it for every route that does not match the public-paths short-circuit (`/api/auth/**`, `/actuator/**`).

**Configuration keys it reads from `application.yml`.** None — the header names (`Authorization`, `X-User-Id`, `X-User-Roles`) are baked into the code. The order is `Ordered.HIGHEST_PRECEDENCE + 10` (a hair after the correlation-id filter at the absolute top).

### 3.7 `security/RateLimitConfig.java`

**What it is.** A bean factory for a `KeyResolver` — the function that decides **which key** the rate-limiter counts requests against.

**Why it exists.** Spring Cloud Gateway's built-in rate-limit filter (Redis-backed) needs a key per request to bucket counts into: "this user has made 47 requests in the last minute" or "this IP has made 312." This bean returns `user:<id>` if the gateway has already injected `X-User-Id`, otherwise `ip:<address>`. Per-user for authenticated routes, per-IP for public ones.

> **Phase 7 note (from the per-service designer note).** The rate-limit filter is *configured* but not yet *activated*. The pom pulls in `spring-boot-starter-data-redis-reactive`, and the key resolver is wired, but the actual `RequestRateLimiterGatewayFilterFactory` route filter is not yet attached to any route in `GatewayRoutesConfig`. This is a planned Phase 7 addition.

**Spring annotations.**

- `@Configuration` — bean-source marker.
- `@Bean` (on `userOrIpKeyResolver`) — registers the resolver.

**What it calls.** Only the `ServerWebExchange` passed in by the rate-limiter; no other classes.

**What calls it.** Spring Cloud Gateway's rate-limit filter, once it is enabled. (Today: nobody yet — see the note above.)

**Configuration keys it reads from `application.yml`.** None.

### 3.8 `security/SecurityConfig.java`

**What it is.** The Spring Security reactive filter chain — the actual gate that rejects unauthenticated requests with a 401 before the gateway even routes them.

**Why it exists.** In the WebFlux stack, Spring Security installs itself as a `WebFilter` that runs **before** Spring Cloud Gateway's filter chain. A `GlobalFilter` like `JwtAuthFilter` runs **after** the Spring Security chain. Without wiring JWT validation into Spring Security here, every request to a protected route would be rejected with an empty 401 before the gateway has a chance to look at it. The class-level Javadoc on this file describes this as a "previous version of this class" bug that was fixed.

**Spring annotations.**

- `@Configuration` — bean-source marker.
- `@EnableWebFluxSecurity` — turns on Spring Security for the WebFlux stack and triggers the reactive `SecurityWebFilterChain` auto-configuration.
- `@Bean` (on `springSecurityFilterChain`) — registers the security filter chain.

**What it calls.**

- `ServerHttpSecurity` (parameter-injected by Spring) — the builder for the reactive security filter chain.
- `oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))` — turns on JWT-based authentication. Spring Security picks up the `ReactiveJwtDecoder` bean from `JwtConfig` automatically.
- `HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)` — the entry point invoked on auth failure; returns a clean 401 instead of redirecting to a login page (which is the default for browser flows but not what we want for an API).

**What calls it.** Spring Security's `WebFilter` chain invokes the configured `SecurityWebFilterChain` for every request before any other WebFilter (including the gateway's routing).

**Configuration keys it reads from `application.yml`.** None directly. The `oauth2ResourceServer().jwt()` block wires up the decoder bean defined in `JwtConfig`, which reads `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.

### 3.9 Application config — `application.yml`

Every key in `services/api-gateway/src/main/resources/application.yml`, in the order it appears, with a one-sentence explanation:

```yaml
server:
  port: 8080
  shutdown: graceful
```
The gateway listens on port 8080 (so the same port compose binds to on the host). `shutdown: graceful` drains in-flight requests before the JVM exits — important for rolling deploys so a request in progress does not get cut off.

```yaml
spring:
  application:
    name: samato-api-gateway
```
The application name. This is the value that becomes the **Eureka service id** (uppercased to `SAMATO-API-GATEWAY`) and is what `lb://SAMATO-API-GATEWAY` in routes resolves to. The `/actuator/**` route uses this id to point at the gateway itself.

```yaml
  main:
    web-application-type: reactive
```
Forces the embedded server to be Netty on the WebFlux stack. The gateway **must** be reactive — Spring Cloud Gateway does not work on the servlet stack.

```yaml
  cloud:
    config:
      enabled: false
```
Config-server lookup is off in this build. The api-gateway reads all its config from this YAML file. Flip to `true` when the config-service is up and you want hot-reload of gateway config.

```yaml
    gateway:
      discovery:
        locator:
          enabled: false
```
By default, Spring Cloud Gateway would auto-create a route per registered Eureka service. We disable that and define routes explicitly in `GatewayRoutesConfig` so each route is auditable, can carry its own filters, and shows up under `/actuator/gateway/routes`.

```yaml
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_UNIQUE
```
A global filter that, when the gateway is load-balanced behind another proxy and the CORS headers are duplicated, keeps only one copy with `RETAIN_UNIQUE`. Prevents browser warnings about multiple `Access-Control-Allow-Origin` headers.

```yaml
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"
            allowedMethods: "*"
            allowedHeaders: "*"
            exposedHeaders: "X-Correlation-Id"
```
YAML-defined CORS fallback (overlaps with `CorsConfig`'s bean). `exposedHeaders: "X-Correlation-Id"` lets the browser's JavaScript see the `X-Correlation-Id` response header — handy for support tickets where a user pastes their correlation id.

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```
Where the Eureka server lives. In the Docker compose file, this is overridden to `http://discovery-service:8761/eureka/` via the `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` env var. The default here is for local dev.

```yaml
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 5
    lease-expiration-duration-in-seconds: 15
```
`prefer-ip-address: true` makes Eureka register the instance's IP rather than its hostname — works around the Docker DNS issue where the hostname is not resolvable from other containers. The 5-second renewal / 15-second expiration are tight values for fast failover during rolling deploys.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics,gateway
```
Which actuator endpoints are exposed. `gateway` is the Spring Cloud Gateway-specific one that lists routes, refreshes the route table, and shows global filters.

```yaml
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
```
Turns on Kubernetes-style liveness/readiness probes and shows full health details in the response body. `show-details: always` is fine for the bible; in production this should be `when_authorized`.

```yaml
  health:
    livenessstate: { enabled: true }
    readinessstate: { enabled: true }
```
Exposes the liveness and readiness sub-endpoints separately. K8s uses these to decide whether to send traffic.

```yaml
  tracing:
    sampling:
      probability: 1.0
```
Sample 100% of requests for distributed tracing. Fine for dev — turn this down to 0.1 (10%) in production to reduce Zipkin storage pressure.

```yaml
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```
Where to send spans. Zipkin runs on port 9411 in Docker compose.

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"
  level:
    org.springframework.cloud.gateway: INFO
    reactor.netty: INFO
    com.samato: DEBUG
```
The log pattern includes the app name, the Zipkin trace id and span id, and the correlation id from MDC. Every log line emitted by the gateway while handling a request is automatically tagged with these, so a grep for `correlationId=abc-123` retrieves the whole request's life. The level block quiets the chatty gateway/netty logs to INFO and keeps the project's own classes at DEBUG.

## 4. Endpoints (controllers)

**The api-gateway has no `@RestController` of its own.** It is a *router*, not a service — every URL the gateway can answer is either:

1. **Forwarded to a downstream service** (the routes in `GatewayRoutesConfig`), or
2. **Served by Spring Boot Actuator** (the management endpoints below).

That said, the routes *the gateway exposes to clients* are real, well-formed endpoints. Below is the full list as a client sees it.

### 4.1 `POST /api/auth/login` — public login

- **Routed to:** `auth-service` (`/api/auth/login` on port 9000)
- **Auth:** none (public route; `pathMatchers("/api/auth/**").permitAll()` in `SecurityConfig`)
- **Request body (from `auth-service`'s controller):** `{ "username": "...", "password": "..." }` — see `services/auth-service/.../RegistrationController.java` and any login controller there.
- **Response:** an OAuth2 access token + refresh token.
- **Example curl:**
  ```bash
  curl -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"username":"alice","password":"hunter2"}'
  ```

### 4.2 `GET /api/users/me` and the rest of `/api/users/**` — user-service proxy

- **Routed to:** `user-service` on port 8081
- **Auth:** bearer JWT required
- **Headers added by gateway:** `X-User-Id`, `X-User-Roles`, `X-Source: samato-api-gateway`, `X-Correlation-Id`
- **Example curl:**
  ```bash
  curl http://localhost:8080/api/users/me \
    -H 'Authorization: Bearer eyJhbGciOi...'
  ```

### 4.3 `POST /api/orders` and the rest of `/api/orders/**` — order-service proxy

- **Routed to:** `order-service` on port 8083
- **Auth:** bearer JWT required
- **Example curl (placing an order — see `../use-cases/01-place-an-order.md`):**
  ```bash
  curl -X POST http://localhost:8080/api/orders \
    -H 'Authorization: Bearer eyJhbGciOi...' \
    -H 'Content-Type: application/json' \
    -d '{"restaurantId":"<uuid>","items":[{"menuItemId":"<uuid>","quantity":2}],"paymentMethod":"RAZORPAY"}'
  ```

### 4.4 `GET /api/restaurants/{id}` and the rest of `/api/restaurants/**` — restaurant-service proxy

- **Routed to:** `restaurant-service` on port 8082
- **Auth:** bearer JWT required
- **Example curl (browse — see `../use-cases/03-browse-and-search.md`):**
  ```bash
  curl http://localhost:8080/api/restaurants/00000000-0000-0000-0000-000000000001 \
    -H 'Authorization: Bearer eyJhbGciOi...'
  ```

### 4.5 `GET /api/search?q=...` — search-service proxy

- **Routed to:** `search-service` on port 8087
- **Auth:** bearer JWT required
- **Example curl:**
  ```bash
  curl 'http://localhost:8080/api/search?q=pizza&city=Bangalore' \
    -H 'Authorization: Bearer eyJhbGciOi...'
  ```

### 4.6 `POST /api/payments/orders` and the rest of `/api/payments/**` — payment-service proxy

- **Routed to:** `payment-service` on port 8084
- **Auth:** bearer JWT for user-facing endpoints; HMAC for `/api/payments/webhooks/**` (validated inside payment-service, the gateway just forwards).
- **Example curl (refund — see `../use-cases/04-refund-flow.md`):**
  ```bash
  curl -X POST http://localhost:8080/api/payments/<payment-uuid>/refunds \
    -H 'Authorization: Bearer eyJhbGciOi...' \
    -H 'Idempotency-Key: 7e2f-4a1b-9c0d' \
    -H 'Content-Type: application/json' \
    -d '{"amount":"100.00","reason":"customer_request"}'
  ```

### 4.7 `GET /actuator/health` — gateway health

- **Auth:** none (public; `pathMatchers("/actuator/health/**", "/actuator/info").permitAll()` in `SecurityConfig`)
- **Response (when up):**
  ```json
  {"status":"UP"}
  ```
- **Example curl:**
  ```bash
  curl http://localhost:8080/actuator/health
  ```

### 4.8 `GET /actuator/gateway/routes` — list of routes

- **Auth:** none in this build (the `/actuator/**` path is routed to the gateway's own actuator; security config permits `/actuator/health/**` and `/actuator/info` only — see the gotcha below).
- **Example curl:**
  ```bash
  curl http://localhost:8080/actuator/gateway/routes
  ```
  Returns one JSON object per route — useful for debugging "which downstream is my request actually hitting?".

### 4.9 `GET /actuator/prometheus` — metrics

- **Auth:** not in the `permitAll` list. K8s service-mesh sidecars (or a Prometheus scrape job) can reach it inside the cluster; clients outside the cluster would need a token.
- **Example curl:**
  ```bash
  curl http://localhost:8080/actuator/prometheus | head -30
  ```

> **Gotcha — `permitAll` scope.** `SecurityConfig` only permits `/api/auth/**`, `/actuator/health/**`, and `/actuator/info`. The `health` route in `GatewayRoutesConfig` covers `/actuator/**` (matching all of them), but the security filter runs *before* routing, so a request to `/actuator/gateway/routes` or `/actuator/prometheus` without a token will be rejected with 401 *before* it reaches the route. To list routes without auth, hit `/actuator/gateway/routes` from inside the cluster (e.g. via the sidecar) or with a valid token.

## 5. Database schema

**The api-gateway has no database and no Flyway migrations.** The `db/migration/` directory under `services/api-gateway/src/main/resources/` does not exist — the gateway is stateless and stores nothing. Auth state lives in `auth-service`; rate-limit counters (future) will live in Redis.

If you ever need to add a table to the gateway, you would:

1. Create `services/api-gateway/src/main/resources/db/migration/V1__init.sql`.
2. Add `spring-boot-starter-data-jpa` and the `postgresql` driver to the pom.
3. Add a `spring.datasource.url` entry to `application.yml`.

Today, none of that exists. The gateway is intentionally database-free.

## 6. Kafka integration

**The api-gateway does not publish or consume any Kafka topic.** There is no `KafkaTemplate` bean, no `@KafkaListener`, no Avro schema. The `shared-kafka` module is also **not** a dependency of the api-gateway pom (the api-gateway pom only depends on `shared`, not `shared-kafka`).

This is by design: the gateway is on the **synchronous HTTP path** for request routing. All event-driven traffic in the system (order placed, payment charged, restaurant created) happens *behind* the gateway, between the business services, and is invisible to clients.

## 7. Common "if you change X, also update Y" notes

These are the cross-file gotchas that cost beginners hours. They are the things the source code does not warn you about.

1. **If you add a new route in `GatewayRoutesConfig`**, also consider whether `SecurityConfig` needs to know. Spring Security's `pathMatchers("/api/auth/**").permitAll()` is the only public allow-list; everything else is `authenticated()`. Adding a new `/api/<service>/**` route usually just works (it falls under the `anyExchange().authenticated()` rule), but if it should be **public**, you must add a new `pathMatchers(...).permitAll()` line to `SecurityConfig` too.

2. **If you add a new header the gateway should forward**, update both `GatewayRoutesConfig` (the `addRequestHeader` call on the route) and the downstream service's controller (where the header is read). The most common case is adding a new `X-*` header for tenant id, request id, or feature flag.

3. **If `auth-service` rotates its signing key** (e.g. generates a new key pair, publishes it on JWKS, retires the old one), no gateway change is needed. Spring Security's `NimbusReactiveJwtDecoder` sees the new `kid` in the next token, fetches the JWKS again, and starts verifying with the new key. The cache invalidates on `kid` miss.

4. **If you change the JWKS URL**, you must update **two** places:
   - `application.yml` key `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (or the `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` env var in `docker-compose.yml`).
   - The `Eureka` service id `auth-service` (the URL must still resolve to a healthy instance).
   Forgetting the env var override means the gateway still uses `http://localhost:9000/...` even inside Docker.

5. **If you add Spring Boot's servlet stack by accident** (e.g. accidentally add `spring-boot-starter-web`), the gateway will fail to start with a "WebFlux is not on the classpath" or "Ambiguous mapping" error. The class header of `ApiGatewayApplication` is annotated with `@SpringBootApplication`, which does not itself force WebFlux — the YAML key `spring.main.web-application-type: reactive` is the actual switch. Keep that line in sync.

6. **The `allowedOriginPatterns: "*"` CORS rule is a dev concession.** In production, replace it with the specific frontend origins (e.g. `https://app.samato.com`). Same for the bean in `CorsConfig` — both should be tightened together.

7. **Do not remove `spring-cloud-starter-data-redis-reactive`** from the pom even though rate limiting is not yet active — it is the dependency the Phase 7 plan keys on. The container's health probe also depends on a reachable Redis host (`SPRING_DATA_REDIS_HOST=redis` is set in compose for this reason — see the `INTERVIEW-NOTES.md` "Bring-up bug fixes" section).

8. **The `stripPrefix(1)` mismatch in `GatewayRoutesConfig`** is documented in a code comment but not fixed. If you ever migrate a service to expect no `/api` prefix on the forwarded URL, you will need to add `.stripPrefix(1)` to that route and rebase the downstream controller's `@RequestMapping`. Touch all four routes (auth, user, order, payment, restaurant) at once for consistency.

9. **The api-gateway is reactive; the downstream services are mostly servlet.** The WebFlux filter chain and the servlet filter chain are different beasts. If you copy a `OncePerRequestFilter` from one of the business services into the gateway, it will silently never run. Use the gateway's own `CorrelationIdWebFilter` as the template for any new reactive filter.

10. **The shared `CorrelationIdFilter.HEADER` constant is the source of truth** for the header name. If you change it there, the gateway's filter, the other services' filters, the `application.yml` `exposedHeaders` list, and any client that reads the header all need to follow. The constant lives in `com.samato.shared.observability.CorrelationIdFilter`.

11. **`JwtAuthFilter`'s `getOrder()` returns `HIGHEST_PRECEDENCE + 10`.** The correlation-id filter is at `HIGHEST_PRECEDENCE`. The security filter chain is the WebFlux top-level filter and runs *before* the gateway's filter chain, so the order between them is not actually configurable here — the security chain always sees the request first. The `+10` is meaningful only against other `GlobalFilter`s.

12. **If you add a controller to the api-gateway** (for example, a `/gateway/version` endpoint), be aware that the routes in `GatewayRoutesConfig` use the gateway's own Eureka id `SAMATO-API-GATEWAY` for `/actuator/**` and would not interfere with your new path. But `SecurityConfig`'s `pathMatchers("/api/auth/**").permitAll()` rule is positional — your new path needs to be in either the `permitAll()` list or fall under `anyExchange().authenticated()`.

## 8. See also

- The per-service designer note: `../../services/api-gateway/docs/INTERVIEW-NOTES.md` — the interview-oriented write-up of this service (patterns, Q&A, trade-offs).
- The architecture guide: `../01-architecture-guide.md` — how the gateway fits into the wider system.
- How auth works end-to-end: `../02-how-auth-works.md` — the JWKS / token-validation story the gateway participates in.
- Auth flow walkthrough: `../use-cases/02-auth-flow.md` — when a user logs in, what the gateway does.
- Place an order walkthrough: `../use-cases/01-place-an-order.md` — the gateway's role in the saga's first call.
- Browse and search walkthrough: `../use-cases/03-browse-and-search.md` — the gateway routing restaurant and search calls.
- Refund flow walkthrough: `../use-cases/04-refund-flow.md` — the gateway routing the refund request.
- Shared modules (correlation-id MDC, errors): `../shared-and-kafka.md` — the `CorrelationIdFilter.HEADER` constant and `MdcKeys` referenced from `CorrelationIdWebFilter` live here.
- The repo's top-level `ARCHITECTURE.md`, `PROJECT-STATUS.md`, `RUN-THE-BIBLE.md`, and `docs/INTERVIEW-CHEATSHEET.md` are also worth a skim.
- Glossary of terms used in this doc (Spring Cloud Gateway, WebFlux, Eureka, JWT, JWKS, CORS, MDC, correlation id, `lb://`, BFF, north-south vs east-west traffic): see `../00-glossary.md` (it will be created by the doc team if it does not yet exist).
