# config-service (port 8888)

> Plain-English purpose: `config-service` is the **central, versioned source of truth for every other microservice's configuration**. Instead of each of the 12 Samato services carrying its own copy of timeouts, feature flags, and Eureka URLs, they all `GET` their config from this one server. It is a stock **Spring Cloud Config Server** (an `org.springframework.cloud.config.server` project), pointed at a local `config-repo/` folder that stands in for what would be a Git repository in production. It demonstrates the **Centralized Configuration** pattern and (optionally, with `spring-cloud-bus`) **Hot Reload**.

## 1. Where it sits in the system

```
                            +-----------------------+
   Developer edits          |   config-repo/        |   <- "the Git repo"
   yml files in a PR  ----> |  application.yml      |
                            |  api-gateway.yml      |
                            |  ...                  |
                            +----------+------------+
                                       |
                              (native FS read,
                               or git clone)
                                       v
   +-------------------+       +---------------------+
   | api-gateway       |       |                     |
   | auth-service      |  GET  |   config-service    |
   | user-service      | ----> |  (port 8888)        |
   | restaurant-service|       | @EnableConfigServer |
   | search-service    |       |  /{app}/{profile}   |
   | order-service     |       |  /{app}/{profile}/  |
   | payment-service   |       |     {label}         |
   | discovery-service |       +----------+----------+
   +-------------------+                  |
                                          | registers in
                                          v
                                  +-----------------+
                                  |  discovery-     |
                                  |  service        |
                                  |  (Eureka, 8761) |
                                  +-----------------+

   Publishes topics:  none
   Consumes topics:   none
   Outbound HTTP:     none (it is a pure server, never a client)
   Inbound HTTP:      every microservice on startup + on /actuator/refresh
```

Notes from the wiring:
- `config-service` is the **only** Samato service that does not itself consume a `@FeignClient`, a `KafkaTemplate`, or a `@KafkaListener`. Its job is to be a server, not a client.
- It is **not** a secret store. The interview note in `ConfigServiceApplication.java` is explicit: secrets live in Vault / AWS Secrets Manager / K8s secrets, not in this server.
- It is registered in Eureka (it has the `eureka-client` on the classpath via `shared`), but no other service discovers it through Eureka — services hit it directly via the URL in their own `application.yml` (`http://config-service:8888` inside the compose network, or `http://localhost:8888` from a host).

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/config-service` |
| Spring Boot app name | `samato-config-service` (from `application.yml` `spring.application.name`) |
| Port | **8888** (HTTP) — `server.port: 8888` |
| Backend | **Native filesystem** (`spring.profiles.active: native`, `spring.cloud.config.server.native.search-locations: file:./config-repo`) — also has a `git:` block with `uri: file:./config-repo` for documentation / future switch-over |
| Database(s) | PostgreSQL: `jdbc:postgresql://localhost:5432/config` (user `fd` / pass `fd`). **DDL is disabled** (`spring.jpa.hibernate.ddl-auto: none`); no Flyway is configured. The DB is declared in compose but is unused for app data. |
| Publishes topics | none |
| Consumes topics | none |
| REST endpoints it exposes | `/actuator/*` (health/info/prometheus/metrics), plus the standard Spring Cloud Config HTTP API (`/{application}/{profile}`, `/{application}/{profile}/{label}`, `/{label}/{application}-{profile}.yml`, etc.) |
| REST endpoints it calls | none |
| Depends on | `shared` (com.samato:shared) — pulls in `spring-boot-starter-web` transitively plus Eureka client + tracing + logging; `spring-cloud-config-server` (the actual config-server starter); `spring-boot-starter-actuator` (health endpoints + Prometheus + Zipkin tracing) |
| Container name | `samato-config-service` |
| Host port mapping | `8888:8888` |
| Default config repo | `./config-repo/` (sibling of the dockerfile build context) |

## 3. File-by-file walkthrough

This service is intentionally tiny. There is **exactly one Java file** in the whole module.

### `ConfigServiceApplication.java` (the *only* class)

- **What it is:** the Spring Boot entry point. A class with a `public static void main(String[] args)` that delegates to `SpringApplication.run(...)`.
- **Why it exists:** every Spring Boot application needs a bootstrap class annotated with `@SpringBootApplication` to enable auto-configuration and component scanning. On top of that, `@EnableConfigServer` turns this app into a Spring Cloud Config Server — without that one annotation, the `/application.yml` HTTP endpoints wouldn't exist.
- **Spring annotations on the class header:**
  - `@SpringBootApplication` — a meta-annotation that combines `@Configuration` (this class can declare `@Bean` methods), `@EnableAutoConfiguration` (turn on Spring Boot's opinionated defaults), and `@ComponentScan` (find `@Component`/`@Service`/`@Repository`/`@Controller` classes in sub-packages). One annotation, three jobs.
  - `@EnableConfigServer` — switch on the Spring Cloud Config Server. Registers the HTTP endpoints (`/{application}/{profile}`, `/{application}/{profile}/{label}`, `/{application}-{profile}.yml`, `/{label}/{application}-{profile}.yml`) and wires the `EnvironmentRepository` that knows how to read from a Git repo, the local filesystem, Vault, etc., based on the `spring.cloud.config.server.*` properties.
- **What it calls:** nothing. No `@Autowired` fields, no constructor injection, no method calls. It is a pure bootstrap class.
- **What calls it:**
  - Every other Samato microservice on startup. They each have `spring-cloud-starter-config` (or the equivalent in their `pom.xml`) and a `spring.config.import: optional:configserver:http://...:8888` line (or its equivalent) so the config client fetches their config from this server before the rest of the bean wiring runs.
  - Developers / `curl` / load balancers hitting the actuator endpoints (`/actuator/health`, `/actuator/prometheus`) and the config-fetch endpoints.
  - Optionally, Spring Cloud Bus (Kafka/Rabbit) for hot refresh of `@RefreshScope` beans — **not yet wired in this codebase** (Phase 8 per the interview notes).
- **Configuration keys it reads from `application.yml`:** every key in the file (see next subsection). The class itself does not read any keys programmatically — the framework's `ConfigServerAutoConfiguration` does.

### Application config (`src/main/resources/application.yml`)

Every key, with a one-sentence plain-English gloss. This file is the actual contract between the dev and Spring Cloud Config Server.

| Key | Value | What it means |
|---|---|---|
| `server.port` | `8888` | The HTTP port the config server listens on. Matches the `8888:8888` in `docker-compose.yml`. |
| `server.shutdown` | `graceful` | When the JVM is asked to stop (e.g. `docker stop`), Spring Boot lets in-flight HTTP requests finish before killing the process, instead of dropping them mid-response. |
| `spring.application.name` | `samato-config-service` | The service's own name — used in logs, in the `spring.application.name` placeholder, and (because the `eureka-client` is on the classpath via `shared`) as the **service ID** under which it registers in Eureka. |
| `spring.profiles.active` | `native` | Activate the `native` profile, which switches Spring Cloud Config Server from its default Git backend to the local filesystem backend (`spring.cloud.config.server.native.search-locations`). |
| `spring.cloud.config.server.native.search-locations` | `file:./config-repo` | The folder (relative to the working directory) the server reads config files from. This is where the developer drops `application.yml`, `api-gateway.yml`, etc. |
| `spring.cloud.config.server.git.uri` | `file:./config-repo` | Git backend URI. In dev, also points at the local folder; in production, this would be a real `https://github.com/myorg/config.git`. Listed for documentation / future switch-over — the `native` profile is currently active, so this block is **not** read at runtime. |
| `spring.cloud.config.server.git.default-label` | `main` | The Git branch/tag to read from when a request doesn't specify a `{label}`. |
| `spring.cloud.config.server.git.force-pull` | `true` | Pull on every request instead of caching — fine in dev, expensive in prod. Again, not active under the `native` profile. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/config` | JDBC URL for the Postgres instance. The DB name is `config`, created by `postgres/init-databases.sh`. Inside the compose network, the host is `postgres`, not `localhost` — the `SPRING_DATASOURCE_URL` env var in `docker-compose.yml` overrides this. |
| `spring.datasource.username` | `fd` | DB user. |
| `spring.datasource.password` | `fd` | DB password. (In production, this would be injected via env var / Vault / K8s secret.) |
| `spring.jpa.hibernate.ddl-auto` | `none` | Tell Hibernate **not** to create or modify any tables. Config server stores no data; the datasource is wired only because `shared` brings in `spring-boot-starter-web` (and through it JPA auto-configuration may be triggered if a JPA starter were also present — it isn't, see "anomalies" below). |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Which `/actuator/*` endpoints to expose over HTTP. `health` and `info` are ops essentials; `prometheus` is the scrape endpoint for the Prometheus job `spring-boot-services` (see `prometheus/prometheus.yml`); `metrics` is a generic Micrometer endpoint. |
| `management.endpoint.health.probes.enabled` | `true` | Enable Kubernetes-style liveness and readiness probes at `/actuator/health/liveness` and `/actuator/health/readiness`. |
| `management.endpoint.health.show-details` | `when_authorized` | Hide detailed health breakdown (DB, disk, ping) from anonymous callers; only show to authenticated users (e.g. a logged-in admin or an in-cluster probe with the right role). |
| `management.health.livenessstate.enabled` | `true` | The liveness probe — "is this process alive enough to keep running?" — only fails on irrecoverable states (e.g. out of memory). |
| `management.health.readinessstate.enabled` | `true` | The readiness probe — "is this process ready to serve traffic?" — fails if dependencies aren't ready. |
| `management.tracing.sampling.probability` | `1.0` | Sample **100%** of traces in dev. In prod, you'd drop this to 0.1 (10%) or use tail-based sampling, because tracing every request is expensive. |
| `management.zipkin.tracing.endpoint` | `http://localhost:9411/api/v2/spans` | Where to ship spans. In compose, this would be `http://zipkin:9411/api/v2/spans` (the override is not in this file's `docker-compose.yml` entry — see "gotchas"). |
| `spring.zipkin.discovery-client-enabled` | `false` | Don't ask Eureka for the Zipkin host; use the URL above directly. |
| `logging.pattern.level` | `"%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"` | Log line prefix includes the service name, the current W3C trace id, the span id, and the correlation id from MDC. The `:-` default means "empty if absent" so dev logs still look right. |
| `logging.level.org.springframework.cloud.config` | `INFO` | Tone down the config server's own internal chatter. |
| `logging.level.com.samato` | `DEBUG` | Verbose logging for our own code. |

## 4. Endpoints (controllers)

This service has **no `@RestController`** of its own. It is a Spring Cloud Config Server + an Actuator, nothing else. The HTTP surface is therefore:

### Spring Cloud Config Server HTTP API (provided by `@EnableConfigServer`)

These endpoints are framework-defined. They are not in a class you can read in this module; they are wired by `spring-cloud-config-server`. The base path is `http://<host>:8888`.

| Method | Path | Purpose | Example curl |
|---|---|---|---|
| GET | `/{application}/{profile}` | Fetch config for `application` (e.g. `api-gateway`) under `profile` (e.g. `default`, `dev`, `prod`). Resolves to a file named `{application}-{profile}.yml` plus `{application}.yml` for shared defaults. | `curl http://localhost:8888/api-gateway/default` |
| GET | `/{application}/{profile}/{label}` | Same as above, but pinned to a specific Git branch/tag/commit. | `curl http://localhost:8888/api-gateway/default/main` |
| GET | `/{application}-{profile}.yml` | Plain-property form (YAML), used by the config client when it doesn't want a JSON envelope. | `curl http://localhost:8888/api-gateway-default.yml` |
| GET | `/{label}/{application}-{profile}.yml` | Same with label. | `curl http://localhost:8888/main/api-gateway-default.yml` |
| GET | `/{application}-{profile}.properties` | Same idea, `.properties` format. | `curl http://localhost:8888/api-gateway-default.properties` |
| GET | `/{application}/{profile}[/{label}]` (JSON form) | JSON envelope with `name`, `profiles`, `label`, `version`, `propertySources[]` — the form the config client consumes. | `curl -H "Accept: application/json" http://localhost:8888/api-gateway/default` |

**Response shape (JSON form):**

```json
{
  "name": "api-gateway",
  "profiles": ["default"],
  "label": "main",
  "version": "...",
  "state": null,
  "propertySources": [
    { "name": "file:./config-repo/api-gateway.yml", "source": { "server.port": 8080, "spring.application.name": "api-gateway" } },
    { "name": "file:./config-repo/application.yml",  "source": { "eureka.client.service-url.defaultZone": "..." } }
  ]
}
```

`propertySources` is **ordered most-specific-first**: `api-gateway.yml` overrides `application.yml`, which is the global defaults file.

**Who is allowed to call it:** in dev, **anything on the network**. There is no Spring Security configured in this module (no `spring-boot-starter-security` on the classpath), so all endpoints are open. In production, you'd put a basic-auth filter in front of the config server (Spring Cloud Config supports this with `spring.security.user.*`) or restrict it at the network layer.

**Example curl from a developer machine:**

```bash
# See what config api-gateway would get under the "default" profile, as YAML
curl http://localhost:8888/api-gateway-default.yml

# See the JSON envelope (used by the config client at startup)
curl -H "Accept: application/json" http://localhost:8888/api-gateway/default | jq .

# Hit a non-existent app
curl -i http://localhost:8888/no-such-app/default
# HTTP/1.1 404 Not Found
```

### Actuator endpoints (provided by `spring-boot-starter-actuator`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/actuator` | Index of all exposed actuator endpoints. |
| GET | `/actuator/health` | Aggregated health. Returns `{"status":"UP"}` when all components are healthy. |
| GET | `/actuator/health/liveness` | Kubernetes liveness probe. |
| GET | `/actuator/health/readiness` | Kubernetes readiness probe. |
| GET | `/actuator/info` | Build / git / env info (empty unless you configure it). |
| GET | `/actuator/prometheus` | Prometheus scrape endpoint. |
| GET | `/actuator/metrics` | List of registered Micrometer meters. |
| GET | `/actuator/metrics/{name}` | Drill into a single meter. |

**Example curl:**

```bash
curl http://localhost:8888/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}

curl http://localhost:8888/actuator/prometheus | head -5
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
# jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 1.234e+07
```

`/actuator/env` and `/actuator/refresh` are **not exposed** in this service (`management.endpoints.web.exposure.include` lists only `health,info,prometheus,metrics`).

## 5. Database schema

There are **no Flyway migrations** under `services/config-service/src/main/resources/db/migration/` (the directory does not exist). The `spring.datasource.*` properties in `application.yml` point at a `config` database (created by `postgres/init-databases.sh` in compose), but:

- `spring.jpa.hibernate.ddl-auto: none` means Hibernate creates **no** tables.
- No JPA entities exist in this module.
- No JDBC code exists in this module.

The datasource is wired (and the `config` database exists) mainly for the sake of being able to verify the dependency on Postgres is healthy. In a hardening pass, the `config-service` deployment would either drop the datasource entirely or use a different mechanism to confirm DB health.

**Tables (none in app code):** none. The schema inventory lists `migrations: []`.

## 6. Kafka integration

- **Topics published:** none.
- **Topics consumed:** none.
- **Outbox table:** none.
- **Producer / consumer code:** none — there is no `KafkaTemplate`, no `KafkaProducerConfig`, no `@KafkaListener` in this module.
- **`shared-kafka` dependency:** not present. `config-service` is one of the few services whose `pom.xml` does **not** depend on `com.samato:shared-kafka`. It is purely an HTTP / file-server.

This is the right design: config server is a piece of infrastructure you want to keep up even if Kafka is down. Coupling it to Kafka would be a chicken-and-egg hazard.

## 7. Common "if you change X, also update Y" notes

These are the gotchas that cost a beginner hours if they don't know about them up front.

1. **The "backend" is the `native` profile, not the `git` block.** `application.yml` lists both `spring.cloud.config.server.native.search-locations: file:./config-repo` **and** `spring.cloud.config.server.git.uri: file:./config-repo`. Both point at the same folder. Only the `native` one is read at runtime, because `spring.profiles.active: native`. The `git:` block is documentation / future-use. If you want to switch to a real Git URL, either remove `native` from `spring.profiles.active` or change the active profile to `git` and update `uri` to a real `https://github.com/.../config.git`.

2. **The `config-repo/` folder is relative to the JVM's working directory, not to the project root.** When you run the app via `mvn spring-boot:run` from `services/config-service/`, `./config-repo` resolves to `services/config-service/config-repo/` (which doesn't exist — only the top-level `config-repo/` does). You need to either:
   - Run from the **monorepo root** with `mvn -pl services/config-service spring-boot:run`, or
   - Set the working directory to the monorepo root, or
   - Change `spring.cloud.config.server.native.search-locations` to an absolute path or to `file:../../config-repo` (relative from `services/config-service/`).
   In `docker-compose.yml` the working directory inside the container ends up where the relative path resolves correctly (the build context is `..`, i.e. the monorepo root, and the container's working directory is `/workspace` or wherever the image sets it). On bare-metal dev runs, this is the #1 reason "I get 404 on every request."

3. **DB host inside compose is `postgres`, not `localhost`.** `application.yml` says `jdbc:postgresql://localhost:5432/config`. The `docker-compose.yml` entry for `config-service` overrides this with the env var `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/config`. If you change the compose env var, change it everywhere (the same env var is set for every other service too).

4. **No secrets, ever.** Do not put `RAZORPAY_KEY_SECRET` or database passwords into a `config-repo/*.yml` file. The `INTERVIEW-NOTES.md` and the Javadoc on `ConfigServiceApplication` are emphatic about this: config server is plain text on disk (or a Git repo). Use Vault / AWS Secrets Manager / K8s secrets, fetched at startup.

5. **The `force-pull: true` setting is dev-only.** It's listed under the `git:` block (not active) but if you ever flip to the Git backend, remember to turn that off — it forces a `git fetch` on every single request, which is fine in dev, catastrophic in prod.

6. **`/actuator/refresh` is not exposed.** Hot-reload via Spring Cloud Bus requires `spring-boot-starter-actuator` (which we have) **and** `/actuator/refresh` to be in the `management.endpoints.web.exposure.include` list (it isn't here — only `health,info,prometheus,metrics`). This service itself doesn't refresh — but if you ever add `@RefreshScope` beans here and want to trigger them, you'll need to add `refresh` to that list.

7. **Eureka registration is on by default.** `shared` brings in `spring-cloud-starter-netflix-eureka-client` transitively (via `spring-cloud-starter-config`? — verify by checking the dependency tree). If you ever decide config-service should **not** register in Eureka (e.g. to keep it on a private network), set `eureka.client.enabled: false` in this `application.yml`. The container will still come up; you just won't see it in the Eureka dashboard at `http://localhost:8761`.

8. **There is no Spring Security on this service.** The `pom.xml` does **not** include `spring-boot-starter-security`. All endpoints are wide open. If you need auth in front of the config-fetch endpoints (typical in production so random engineers can't read DB URLs), add `spring-boot-starter-security` and configure HTTP basic with `spring.security.user.name` / `spring.security.user.password` (or wire it to your IDP).

9. **`api-gateway` has `spring.cloud.config.enabled: false`.** Look at `services/api-gateway/src/main/resources/application.yml` — it deliberately opts out of fetching config from this server, so changes to `config-repo/api-gateway.yml` will *not* be picked up by the api-gateway at startup. It is on the list for symmetry (so you can see how a service *would* be configured to fetch) but is not wired. **Do not** assume every service reads from this server. Check the consumer's `spring.cloud.config.import` (or `spring.cloud.config.enabled`).

10. **The `application.yml` and `api-gateway.yml` in `config-repo/` are just samples.** They are not the source of truth for any service today. The real config lives in each service's own `src/main/resources/application.yml`. If you want config-service to actually drive another service, you need to (a) copy the real values into `config-repo/<service-name>.yml`, (b) ensure the consumer's pom has `spring-cloud-starter-config`, and (c) add `spring.config.import: configserver:http://config-service:8888` (or equivalent) to the consumer's `application.yml`.

11. **The compose health check is on the service itself, not the `/actuator/health` endpoint.** `docker-compose.yml` does not define a `healthcheck` block for `config-service` (only for `postgres`, `kafka`, `redis`). If you want compose to wait for config-service to be UP before starting its dependents, you'd need to add one — but currently nothing in compose `depends_on` config-service at all (config-service has no dependents in the startup graph). Be aware if you add one.

## 8. See also

- **Per-service designer note (interview Q&A, patterns, status):** [services/config-service/docs/INTERVIEW-NOTES.md](../../services/config-service/docs/INTERVIEW-NOTES.md) — read this for the "why" of centralized config and the trade-offs.
- **Cross-service glossary of Spring / microservice terms:** [../00-glossary.md](../00-glossary.md) — alphabetical reference for every Spring, Spring Cloud, microservice, Kafka, and auth term used in this doc set.
- **Shared libraries and Kafka wiring:** [./shared-and-kafka.md](./shared-and-kafka.md) — covers `DomainException`, `GlobalExceptionHandler`, `CorrelationIdFilter`, `MdcKeys`, `FeignCorrelationIdInterceptor` (none of which this service uses, but the ones you see in other services are documented there), plus the Avro schemas and `KafkaProducerConfig` / `KafkaConsumerConfig`.
- **Architecture guide:** [../01-architecture-guide.md](../01-architecture-guide.md) — high-level system diagram, the role of the config server in the bootstrap order (config-service starts, then discovery-service registers, then everything else registers with Eureka and pulls config from this server).
- **How auth works:** [../02-how-auth-works.md](../02-how-auth-works.md) — not directly relevant (config-service has no auth), but referenced for the JWK URI conventions used by other services.
- **Use-case walkthroughs (config-service does not appear in any of them — it is an infrastructure service):**
  - [../use-cases/01-place-an-order.md](../use-cases/01-place-an-order.md)
  - [../use-cases/02-auth-flow.md](../use-cases/02-auth-flow.md)
  - [../use-cases/03-browse-and-search.md](../use-cases/03-browse-and-search.md)
  - [../use-cases/04-refund-flow.md](../use-cases/04-refund-flow.md)
- **Top-level repo docs (for orientation):**
  - [../../ARCHITECTURE.md](../../ARCHITECTURE.md) — the system architecture and module list.
  - [../../PROJECT-STATUS.md](../../PROJECT-STATUS.md) — what's done, what's not.
  - [../../RUN-THE-BIBLE.md](../../RUN-THE-BIBLE.md) — how to bring the whole monorepo up.
  - [../../docs/INTERVIEW-CHEATSHEET.md](../../docs/INTERVIEW-CHEATSHEET.md) — interview answers across the whole project.
