# discovery-service (port 8761)

> Plain-English purpose: This is the **phone book** of the Samato platform.
> Every other service shouts "I'm alive, here's my name and address" at
> this service on startup, and the API gateway and other callers shout
> back "where is order-service right now?" to find healthy instances.
> The design pattern it demonstrates is the **Service Registry** —
> a single, eventually-consistent source of truth for "who is up,
> and at what URL". In Samato, that registry is implemented with
> **Netflix Eureka** (a popular Java/OSS registry originally built
> at Netflix and now part of Spring Cloud).
>
> Why this service is small but load-bearing: in any system where
> pods come and go (autoscaling, blue/green deploys, crashes), you
> cannot hard-code URLs like `http://order-2.internal:8080` —
> `order-2` may not exist tomorrow. The registry lets callers ask
> "give me a healthy order-service instance" and get a fresh answer
> every time. Even if Eureka itself is down, callers keep using the
> last-known-good list (Eureka clients cache the registry), so the
> system is **AP** (available + partition-tolerant) rather than
> CP (consistent + partition-tolerant). That trade-off is the
> classic CAP-theorem decision worth memorising for the interview.

## 1. Where it sits in the system

`discovery-service` is **infrastructure** — it is the *only* service
that does not register with itself. Every other service is a **client**
of it, and the API gateway is its heaviest client (it uses the
registry to resolve the `lb://SERVICE-NAME` URIs in its route table).

```
                         ┌───────────────────────┐
                         │  discovery-service    │  :8761  (Eureka server)
                         │  (this service)       │
                         │  register: NO         │
                         │  fetch-registry: NO   │
                         └──────────▲────────────┘
                                    │  heartbeat + lookup
                                    │  (every 30s; cached for 15s)
       ┌──────────┬─────────┬───────┴───────┬──────────────┬───────────────┐
       │          │         │               │              │               │
   auth-svc  user-svc  restaurant-svc  search-svc     order-svc     payment-svc
   :9000     :8081     :8082            :8087          :8083         :8084
       │          │         │               │              │               │
       └──────────┴────┬────┴───────────────┴──────────────┴───────────────┘
                       │
                       ▼
                ┌──────────────┐
                │  api-gateway │  :8080  (Spring Cloud Gateway)
                │  routes by   │   - resolves `lb://SAMATO-ORDER-SERVICE`
                │  service id  │     through the Eureka client
                └──────────────┘
```

Key relationships:
- **Inbound**: every business service (auth, user, restaurant,
  search, order, payment, api-gateway, config-service) sends
  heartbeats to this server. (The infrastructure containers —
  postgres, kafka, redis, zipkin, prometheus, grafana, opensearch,
  schema-registry, kafka-ui — do not register; they are not
  Spring Boot apps.)
- **Outbound**: this service does **no** outbound calls. It does
  not publish or consume Kafka topics. It does not own a database.
- **Async**: none.

The whole thing is a single Java class with two annotations
(`@SpringBootApplication` and `@EnableEurekaServer`). All the
behaviour comes from Spring Boot starters on the classpath plus
the YAML config.

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/discovery-service` |
| Port | **8761** (set in `application.yml`; also the Eureka convention) |
| Database(s) | **None** — registry is held in memory |
| Publishes topics | **None** |
| Consumes topics | **None** |
| REST endpoints | **None of its own** — only Spring Boot Actuator health/info/prometheus + the Eureka dashboard at `GET /` and `GET /eureka/**` |
| Depends on | `shared` library (for tracing + correlation-id MDC); no other services; no infrastructure (no Postgres, no Kafka, no Redis) |
| Registered with Eureka? | **No** (it is the server, not a client) |

## 3. File-by-file walkthrough

There is **one** Java file in this service. Everything else is
infrastructure (pom.xml, application.yml, logback config, Dockerfile).

### `services/discovery-service/src/main/java/com/samato/discoveryservice/DiscoveryServiceApplication.java`

- **What it is** — the Spring Boot entry point for the registry.
  The whole class is 27 lines including comments.
- **Why it exists** — `@EnableEurekaServer` (from
  `spring-cloud-netflix-eureka-server`) flips a bit inside Spring
  Cloud that turns this application into a registry: it starts a
  servlet that accepts heartbeats at `POST /eureka/apps/**` and
  serves lookups at `GET /eureka/apps/**`. Without the annotation
  the same boot would just be a web app with no discovery.

#### Spring annotations (in the class header)

- `@SpringBootApplication` — a meta-annotation that combines
  `@Configuration` (this class can declare `@Bean` methods),
  `@EnableAutoConfiguration` (Spring Boot configures everything
  it can guess from the classpath — web server, JSON, actuator,
  etc.) and `@ComponentScan` (find `@Component` / `@RestController`
  / etc. in this package and below). Marking a class with this
  is the standard "this is a Spring Boot app" flag.
- `@EnableEurekaServer` — activates the Eureka server autoconfig
  from Spring Cloud Netflix. It registers the Eureka servlet,
  the peer-replication logic, the eviction task, and the
  dashboard endpoints. This is the only annotation that
  distinguishes this app from a plain Spring Boot web service.

#### What it calls

- `SpringApplication.run(DiscoveryServiceApplication.class, args)`
  (the standard `main` bootstrap). That call internally wires
  up the embedded Tomcat, the Eureka servlet, the actuator
  endpoints, the logback config, and every `@Component` on the
  classpath.
- The class does **not** inject anything, does **not** call any
  service, does **not** publish Kafka. There is no controller,
  no repository, no scheduled job, no listener in the entire
  module.

#### What calls it

- Every Spring Cloud **Eureka client** in the other services
  calls it on startup to register, then every
  `eureka.instance.lease-renewal-interval-in-seconds` (default
  30s) to renew. The clients in Samato are configured via
  `config-repo/application.yml` to renew every **5s** and expire
  after **15s** (so a dead instance is gone within 15s in dev).
- The **api-gateway** calls the registry through its
  `EurekaReactiveDiscoveryClient` to resolve the
  `lb://SERVICE-NAME` URIs in the route table.
- Humans (devs) call `GET /` to see the Eureka dashboard, or
  `GET /eureka/apps` to see the JSON registry.

#### Configuration keys it reads from `application.yml`

- `server.port: 8761` — the registry's HTTP port.
- `server.shutdown: graceful` — on SIGTERM, the server stops
  accepting new connections, drains in-flight requests, and
  (importantly) lets the Eureka **client** send a clean
  deregistration before exiting.
- `eureka.client.register-with-eureka: false` — without this,
  Eureka would try to register itself as an instance of itself
  (a self-loop). For the server we turn the client side off.
- `eureka.client.fetch-registry: false` — without this, Eureka
  would try to download the registry from itself on startup.
  There is no point — we *are* the registry.
- `eureka.server.enable-self-preservation: false` — disables
  Netflix's safety net that prevents mass-eviction during a
  network blip. The dev team turns it off so a crashed service
  actually disappears from the dashboard quickly. The interview
  notes flag this is a **dev-only** setting.
- `eureka.server.eviction-interval-timer-in-ms: 5000` — how
  often the eviction task runs (5s). Dead instances are scanned
  for every 5s.
- `eureka.server.wait-time-in-ms-when-sync-empty: 0` — when
  the registry is empty (e.g. right after startup), wait 0 ms
  before considering a new instance "up". Default is 5 minutes,
  which is unhelpful in dev.
- `eureka.instance.hostname: localhost` — what hostname the
  server advertises. Inside `docker compose` the Eureka
  **clients** reach it as `http://discovery-service:8761/eureka/`
  (set per-client via `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`),
  so this key is mostly cosmetic.
- `spring.application.name: samato-discovery-service` — the
  Spring Boot application id (used by the logback pattern, the
  Zipkin service name, and the Prometheus labels).
- `management.endpoints.web.exposure.include: health,info,prometheus,metrics`
  — which actuator endpoints are exposed over HTTP. The
  Eureka dashboard and `/eureka/**` are exposed separately by
  the Eureka server itself, not through actuator.
- `management.endpoint.health.probes.enabled: true` — turns on
  `/actuator/health/liveness` and `/actuator/health/readiness`
  for Kubernetes-style probes.
- `management.health.livenessstate.enabled: true` and
  `management.health.readinessstate.enabled: true` — registers
  the LivenessState and ReadinessState health contributors.
- `management.tracing.sampling.probability: 1.0` — sample
  100% of traces. In dev we want every request to land in
  Zipkin; in prod you'd lower this to avoid filling the store.
- `management.zipkin.tracing.endpoint: http://localhost:9411/api/v2/spans`
  — where to send spans. The Zipkin container is reachable
  from the host at port 9411.
- `spring.zipkin.discovery-client-enabled: false` — do not
  ask Eureka for a Zipkin URL; the endpoint above is already
  absolute.
- `logging.pattern.level` and `logging.level.*` — see the
  next subsection.

### `services/discovery-service/src/main/resources/application.yml`

This is the only configuration file in the service. Every key is
explained in the bulleted list above (read in source order).
The crucial ones to memorise for the interview are the four
`eureka.*` settings that turn off the client side and tune the
server side.

For the logback pattern, the level format
`"%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"`
prints every log line with the service name, the Micrometer
Tracing traceId, spanId, and the correlationId from MDC. If a
key is missing it prints an empty string instead of `null`, so
the logs are easy to grep. The
[`MdcKeys`](../../shared/src/main/java/com/samato/shared/observability/MdcKeys.java)
constants `CORRELATION_ID`, `TRACE_ID`, and `SPAN_ID` line up
exactly with these MDC keys. See
[shared-and-kafka](./shared-and-kafka.md) for the full
correlation-id plumbing.

### `services/discovery-service/src/main/resources/logback-spring.xml`

Standard Spring Boot logback config: imports the default Spring
console appender, overrides the pattern to include
`correlationId` and tracing ids, sets `com.samato` to DEBUG
so the Samato packages log verbosely, and quiets
`com.netflix.eureka` and `org.springframework.cloud` to INFO.
No surprises.

### `services/discovery-service/pom.xml`

Four runtime dependencies plus the Spring Boot Maven plugin:

- `spring-cloud-starter-netflix-eureka-server` — pulls in
  `spring-cloud-netflix-eureka-server` plus the Netflix Eureka
  core libraries and the Eureka server autoconfig. This single
  dependency is what `@EnableEurekaServer` activates.
- `spring-boot-starter-web` — embeds Tomcat and the Spring MVC
  dispatcher. The Eureka server exposes its endpoints as a
  normal Spring MVC app, so it needs a web stack even though
  there are no controllers of our own.
- `spring-boot-starter-actuator` — `/actuator/health`,
  `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics`.
- `com.samato:shared` (the same library every other service
  uses) — gives us the logback pattern, the
  `CorrelationIdFilter`, the `MdcKeys` constants, the
  `FeignCorrelationIdInterceptor` (unused here, but present on
  the classpath), and the `DomainException` /
  `GlobalExceptionHandler` plumbing. See
  [shared-and-kafka](./shared-and-kafka.md).

## 4. Endpoints (controllers)

**This service has no `@RestController`.** All the URLs it
exposes are framework-provided:

### Spring Boot Actuator (auto-exposed via `spring-boot-starter-actuator`)

These are gated by `management.endpoints.web.exposure.include`
in `application.yml`. They are publicly accessible (no auth) on
port 8761.

#### `GET /actuator/health`

Liveness/readiness aggregate. Returns `{"status":"UP"}` when
Eureka itself is up. Kubernetes and Docker Compose consume
this.

Example:

```bash
curl -s http://localhost:8761/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

#### `GET /actuator/health/liveness`

Am I alive? A "yes I am running" probe — kept simple on purpose
so an external watchdog never restarts the registry for trivial
downstream issues.

#### `GET /actuator/health/readiness`

Am I ready to take traffic? In Eureka's case this turns
"not ready" if the eviction task is failing, the server is
shutting down, etc.

#### `GET /actuator/info`

Build info, git commit (if the `git-commit-id-plugin` is
configured — not present here, so this returns `{}`).

#### `GET /actuator/prometheus`

Micrometer metrics in Prometheus exposition format. Prometheus
scrapes this every 15s; the scrape job is listed in
`docs/inventory/infrastructure.json` under
`spring-boot-services`.

#### `GET /actuator/metrics`

A single metric, e.g. `GET /actuator/metrics/jvm.memory.used`.
Useful for ad-hoc PromQL prototyping.

### Eureka server endpoints (provided by `@EnableEurekaServer`)

#### `GET /` — Eureka dashboard

A simple HTML page listing every registered application and its
instance count. Open in a browser at
`http://localhost:8761/` during development to confirm that
`SAMATO-AUTH-SERVICE`, `SAMATO-USER-SERVICE`, etc. are
visible. **No authentication** — never expose this publicly.

#### `GET /eureka/apps`

The full registry as JSON. Each application has a list of
instances, each with `hostName`, `port`, `status` (`UP` /
`OUT_OF_SERVICE` / `DOWN`), `healthCheckUrl`, `vipAddress`,
and a few lease metadata fields.

Example:

```bash
curl -s http://localhost:8761/eureka/apps | jq '.applications.application[] | {name, instance: (.instance[] | {hostName, status})}'
```

#### `GET /eureka/apps/{appName}`

Just one app. The `{appName}` is upper-cased and matches
`spring.application.name` of the registered service.

#### `POST /eureka/apps/{appName}` (Eureka client → server)

The Eureka client protocol: register, renew, deregister, and
heartbeat are all `POST` against this URL pattern, distinguished
by HTTP method and a `Discovery-Tenant-Id`-style header. You
should never call this by hand — the Spring Cloud client
handles it transparently.

#### `GET /eureka/status`

Eureka server status (not the application registry). Returns
plain text like `{"status":"UP"}`.

#### `GET /eureka/health`

Eureka's own health view, separate from the actuator one.
Returns `{"status":"UP"}` if the server can answer lookups.

### Who is allowed to call these?

The Eureka dashboard and the `/eureka/**` endpoints are
**unauthenticated**. There is no Spring Security filter chain
configured in this module. The interview notes flag this as a
production concern: "Basic auth, mTLS, or network policy. Don't
expose Eureka publicly." The Samato compose file publishes
port `8761` on `localhost` only, so it is not reachable from
outside the host in dev; production would lock this down.

## 5. Database schema

**There is no database.** The Phase 1 inventory
(`docs/inventory/per-service/discovery-service.json`) shows
`"migrations": []` and the service does not depend on
`spring-boot-starter-data-jpa` or `postgresql` or `flyway`.

The registry is held in an **in-memory map** keyed by
application name. This is why Eureka's
`enable-self-preservation` and `eviction-interval-timer-in-ms`
matters — when the process restarts, the map is empty until
clients re-register (within 5s given the project's heartbeat
settings).

A database called `discovery` *does* exist on the shared
Postgres instance (it is provisioned by
`infra/postgres/init-databases.sh`), but no service writes to
it. The compose file shows no `SPRING_DATASOURCE_URL` env
override for the discovery container. If you need a
persistent registry in a future phase (for example to survive
restarts without a thundering herd of re-registrations), this
is the slot for it.

## 6. Kafka integration

**None.** The discovery service does not publish or consume
Kafka topics, and the `shared-kafka` module is **not** a
dependency in `pom.xml`. There is no outbox table, no
`KafkaTemplate` bean, no `@KafkaListener`, no schema. The
in-memory registry is the source of truth.

## 7. Common "if you change X, also update Y" notes

This is a small service but it is the dependency of every
other service. The blast radius of a change is large.

1. **If you change `server.port`** in
   `services/discovery-service/src/main/resources/application.yml`,
   update **every** other service's
   `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` env var in
   `docker-compose.yml` (and the `defaultZone` value in
   `config-repo/application.yml` for local non-docker runs).
   Search the repo for `8761` — there are ~8 occurrences.
2. **If you change `spring.application.name`** from
   `samato-discovery-service` to anything else, every other
   service's `eureka.instance.appname` (if set) and any
   `lb://SAMATO-DISCOVERY-SERVICE` route will break. There
   is no such route today, but be careful.
3. **If you enable `enable-self-preservation: true`** (e.g.
   for a prod-like run), the dev convenience of "killed
   services disappear from the dashboard within seconds"
   goes away. The dev team turned it off on purpose. The
   interview notes (`services/discovery-service/docs/INTERVIEW-NOTES.md`)
   flag this trade-off explicitly.
4. **If you add `@EnableEurekaClient` to this module** (i.e.
   start registering the discovery service with itself), you
   create a self-referencing loop. The current
   `register-with-eureka: false` and `fetch-registry: false`
   exist exactly to prevent this.
5. **If you add a database dependency** (e.g. a future
   "persistent registry" feature), the existing Postgres
   `discovery` database is already provisioned by
   `init-databases.sh` and is unused. Do not forget to add
   `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/discovery`
   to the discovery-service container env in
   `docker-compose.yml`, otherwise the app will start without
   DB access and Flyway (if you add it) will fail.
6. **If you change the `shared` library** (e.g. its logback
   pattern or the `MdcKeys` constants), the patterns in this
   service's `application.yml` and `logback-spring.xml` may
   need to be kept in sync. The current ones already match.
7. **If you add a `@RestController` here**, remember there is
   no `GlobalExceptionHandler` wired in (the
   `shared/src/main/java/com/samato/shared/errors/GlobalExceptionHandler.java`
   class is *not* on the classpath of this service — it is
   not imported in any module). You will have to handle your
   own exceptions, or wire the advice explicitly.
8. **The Eureka dashboard has no auth** — if you ever change
   the compose file to publish `8761` to a public network,
   you have effectively published the topology of the entire
   fleet. Lock it down with `spring-security` before that
   happens.

## 8. See also

- The per-service designer note (the "why" behind these
  choices, with interview Q&A on Eureka vs Consul vs
  Zookeeper, AP vs CP, and self-preservation):
  [`services/discovery-service/docs/INTERVIEW-NOTES.md`](../../services/discovery-service/docs/INTERVIEW-NOTES.md)
- The architecture guide (C4 diagrams, the service-dependency
  map, and ADR-8 on "Eureka for discovery"):
  [`docs/01-architecture-guide.md`](../01-architecture-guide.md)
  (the doc team will create this file in Phase 3 — for now
  the canonical content lives in
  [`ARCHITECTURE.md`](../../ARCHITECTURE.md))
- The shared-library and Kafka glue (CorrelationIdFilter,
  MdcKeys, shared-kafka beans):
  [`docs/services/shared-and-kafka.md`](./shared-and-kafka.md)
- The shared config defaults that all clients inherit
  (heartbeat, lease renewal, Zipkin endpoint):
  [`config-repo/application.yml`](../../config-repo/application.yml)
- Bring-up notes for the whole stack (the discovery
  container is one of the 18 in the compose file):
  [`RUN-THE-BIBLE.md`](../../RUN-THE-BIBLE.md)
- Project status — what is verified vs. unverified:
  [`PROJECT-STATUS.md`](../../PROJECT-STATUS.md)
- The discovery service is **infrastructure** rather than
  domain, so it does not appear in any of the four
  end-user use cases. For context, the use cases it
  *enables* (because every other service is a Eureka
  client) are:
  - [`docs/use-cases/01-place-an-order.md`](../use-cases/01-place-an-order.md)
  - [`docs/use-cases/02-auth-flow.md`](../use-cases/02-auth-flow.md)
  - [`docs/use-cases/03-browse-and-search.md`](../use-cases/03-browse-and-search.md)
  - [`docs/use-cases/04-refund-flow.md`](../use-cases/04-refund-flow.md)
- Auth is not relevant to this service — it has no JWT
  validation, no security filter chain, no user identities.
  The closest cross-cutting doc is:
  [`docs/02-how-auth-works.md`](../02-how-auth-works.md)
  (covers how every *other* service authenticates, which
  is why the gateway can resolve them through Eureka
  before they receive any traffic).
- Glossary of terms used in this doc
  (`@SpringBootApplication`, `@EnableEurekaServer`,
  "service registry", "self-preservation"):
  [`docs/00-glossary.md`](../00-glossary.md)
