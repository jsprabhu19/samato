# Samato Microservice Bible 🍔

A complete, runnable reference microservice system designed to be your **interview preparation bible** for backend / platform engineering roles.

> **Project name:** Samato
> **Example domain:** Food delivery (like Uber Eats / DoorDash)
> **Why this domain:** Exercises nearly every microservice concern in one realistic flow — gateways, sagas, async events, distributed transactions, caching, resilience, observability, security, and more.

---

## Quick start

```bash
# 1. Bring up the full infrastructure stack
cd infra
docker compose up -d

# 2. Build & run the foundation services
cd ..
mvn -pl services/config-service,services/discovery-service,services/api-gateway -am spring-boot:run
```

Verify:
- Eureka dashboard: <http://localhost:8761>
- API Gateway:    <http://localhost:8080>
- Zipkin:         <http://localhost:9411>
- Prometheus:     <http://localhost:9090>
- Grafana:        <http://localhost:3000> (admin / admin)

---

## Architecture at a glance

```
                ┌──────────────────┐
   Client ───▶  │   API Gateway    │  :8080
                │  Spring Cloud    │
                └────────┬─────────┘
                         │
            ┌────────────┼────────────┐
            ▼            ▼            ▼
       config-svc   discovery-svc  (more services
       :8888        :8761           in later phases)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full picture.

---

## What's in Phase 1 (this commit)

- ✅ Monorepo with Maven multi-module
- ✅ Docker Compose stack: Postgres, Kafka, Redis, Zipkin, Prometheus, Grafana, Keycloak
- ✅ **config-service** — Spring Cloud Config Server (git-backed)
- ✅ **discovery-service** — Netflix Eureka
- ✅ **api-gateway** — Spring Cloud Gateway with Eureka integration, JWT validation, rate limiting, tracing
- ✅ Per-service `INTERVIEW-NOTES.md`
- ✅ Root `ARCHITECTURE.md` and `INTERVIEW-CHEATSHEET.md`

## Roadmap (next phases)

| Phase | Services | Status | Patterns |
|-------|----------|--------|----------|
| 1 | config, discovery, api-gateway | ✅ done | — |
| 2 | auth-service, user-service | ✅ done | OAuth2 / JWT, RBAC |
| 3 | restaurant-service, search-service | ✅ done | CQRS, Redis cache |
| 4 | order-service | ✅ done | Saga, Outbox, Idempotency |
| 5 | payment-service | ⏳ next | Event Sourcing, ledger |
| 6 | delivery-service | ⏳ | Driver assignment, WebSocket |
| 7 | notification, analytics | ⏳ | Pub/Sub, Kafka Streams |
| 8 | Hardening | ⏳ | Testcontainers, Pact, DLQs, ADRs |

---

## Tech stack

- **Java 21** (records, pattern matching, virtual threads)
- **Spring Boot 3.3** + **Spring Cloud 2023.0.3**
- **PostgreSQL 16**, **Apache Kafka 3.7**, **Redis 7**, **OpenSearch**
- **Resilience4j 2.2**, **Micrometer Tracing**, **Zipkin**
- **Flyway**, **Testcontainers**, **Pact**
- **Docker** (multi-stage, distroless)

See [docs/INTERVIEW-CHEATSHEET.md](docs/INTERVIEW-CHEATSHEET.md) for the Q&A by topic.
