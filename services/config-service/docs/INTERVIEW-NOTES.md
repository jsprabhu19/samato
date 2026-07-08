## Status (2026-07-08)

✅ **Verified running on this machine.** The service image is built by `docker compose build` from the local jar, the container is `Up (healthy)`, `/actuator/health` returns **HTTP 200** with `{"status":"UP"}`, and the service is registered in **Eureka** as `SAMATO-CONFIG-SERVICE`.

- **Port:** 8888
- **Image:** samato-config-service:dev (compose tags it `config-service:latest`)
- **Health:** `curl http://localhost:8888/actuator/health` → `{"status":"UP", ...}`
- **Bring-up bug fixes in this service**: none in code (yml was already fine); image is built from local jar.

---

# Config Service — Interview Notes

## What it does (1 line)
Centralized configuration server (Spring Cloud Config) so every microservice
reads its config from a single, versioned, Git-backed source.

## Why this service exists
- 12 services × 3 envs = 36 config sources if we put config in each app.
- With a config server, one repo = one source of truth, one PR reviews
  the change, one history (`git log`) shows you what happened.
- Hot reload via Spring Cloud Bus (Phase 8 adds RabbitMQ-driven refresh).

## Patterns demonstrated
| Pattern | Where | Why it matters |
|---------|-------|----------------|
| **Centralized Configuration** | `application.yml` (`spring.cloud.config.server.git`) | All 12 services hit `/actuator/env` from this server |
| **Profile-based config** | `config-repo/{service}-{profile}.yml` | Same jar, different behavior per env |
| **Graceful shutdown** | `server.shutdown: graceful` | Lets in-flight requests finish before kill |
| **Health probes** | `management.endpoint.health.probes` | K8s/Compose uses these to gate traffic |
| **Distributed tracing** | `management.tracing.sampling.probability` | Traces land in Zipkin |

## Key endpoints
- `GET /actuator/health` — liveness/readiness
- `GET /actuator/prometheus` — metrics
- `GET /{application}/{profile}` — main config fetch
- `GET /{application}/{profile}/{label}` — pin to a specific Git label/branch

## Interview Q&A

**Q: Why not just use environment variables?**
A: Env vars are good for **infrastructure-level** settings (DB host, secrets)
that differ per env. They're painful for **application-level** config
(feature flags, timeouts, retry policies) because changing them requires
a redeploy and you lose the audit trail. Config server gives you a
reviewable history and a hot-reload path. Best practice: env vars for
*where* (URLs, secrets), config server for *what* (timeouts, flags).

**Q: How do you refresh config without a restart?**
A: Services expose `POST /actuator/refresh`. Spring Cloud Bus (a Kafka/Rabbit
topic) broadcasts the refresh, so all instances pick it up. Or — better —
use `@RefreshScope` on the beans that hold config values and they reload
in place. Caveat: anything cached statically (e.g. a `static final`) won't
refresh — that's why the bible uses `@ConfigurationProperties`.

**Q: Where do secrets live?**
A: NOT here. Config server is plain text on disk (or a Git repo).
Secrets go in **HashiCorp Vault**, **AWS Secrets Manager**, or **K8s secrets**,
and services fetch them at startup or via a sidecar.

**Q: What happens if config server is down at boot?**
A: Services fail to start unless we configure a fallback. Two options:
1. `spring.cloud.config.fail-fast: false` + a local `application.yml` (risky).
2. A local **git clone** of the config repo (idiomatic, used here in dev).

## Trade-offs we made
- **Native filesystem backend** for dev (no GitHub account needed).
  Production would switch to a real `git://...` URL.
- **100% trace sampling** in dev. Production: 1–10% with tail-based sampling.
- **No `spring-cloud-bus` yet** — refresh is a manual `POST /actuator/refresh`.
  Bus comes in Phase 8 with Kafka.

## Follow-ups interviewers ask
- "How would you version config changes?" → Git tags / branches, served via the `label` segment of the URL.
- "Can you encrypt values?" → Yes — Spring Cloud Config supports `{cipher}…` placeholders with a config-server key.
- "What about dynamic config like feature flags?" → Pair with Unleash / LaunchDarkly; don't abuse config server for that.
