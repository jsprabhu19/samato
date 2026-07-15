# Samato Docs — Index

> The Samato docs are a **file-by-file mechanics guide** for the Samato microservice platform — a 9-service food-delivery app built to demonstrate real microservice patterns (Saga, CQRS, Transactional Outbox, OAuth2, etcd/Consul-style discovery, event sourcing) on a runnable Spring Boot codebase. They are written for two audiences: **developers onboarding to the repo** who want to understand the moving parts, and **interviewers** who want a fast pointer to "where in the code does this pattern live?" The recommended reading order is **glossary → architecture → auth → a use case → the per-service docs for the services in that use case**, with the use-case walkthroughs and per-service deep dives available on demand.

---

## Reading order for a beginner

If this is your first time in the repo, follow this 5-step path. Each step earns the right to read the next.

| Step | What to read | Why now |
| ---- | ------------ | ------- |
| 1 | [00-glossary.md](./00-glossary.md) | Defines every Spring Boot, Spring Cloud, Kafka, and auth term the rest of the docs assume you know. Read once, keep open. |
| 2 | [01-architecture-guide.md](./01-architecture-guide.md) | The system map — every service, every port, every pattern, every flow at a glance. After this, you know the shape of the thing. |
| 3 | [02-how-auth-works.md](./02-how-auth-works.md) | Auth is the one concern that crosses every other service, so understanding it first means every later doc makes sense. JWT, JWKS, gateway, resource servers, the four load-bearing anomalies. |
| 4 | Pick **one** use case from [use-cases/](./use-cases/) | The use case shows the same services you just mapped, but with code paths filled in. Start with [01-place-an-order.md](./use-cases/01-place-an-order.md) — it touches the most services. |
| 5 | Read the per-service docs for the services in that use case | These are the file-by-file deep dives — controllers, entities, Kafka topics, Feign clients, every endpoint, every "what calls what". |

A worked example: read the glossary, then the architecture guide, then *Place an Order*, then [`order-service.md`](./services/order-service.md), [`restaurant-service.md`](./services/restaurant-service.md), and [`payment-service.md`](./services/payment-service.md) — and you have the saga end-to-end.

---

## Reading order for an interviewer

### If you have 15 minutes

1. Skim [`01-architecture-guide.md`](./01-architecture-guide.md) — top-to-bottom, do not read closely.
2. Skim [`02-how-auth-works.md`](./02-how-auth-works.md) — the first 30% (the lifecycle) and the last "anomalies" section.
3. Skim [`use-cases/01-place-an-order.md`](./use-cases/01-place-an-order.md) — the narrative sections only.
4. If asked a specific pattern question, jump to the matching per-service doc from the [Service quick reference](#service-quick-reference) below.

### If you have 2 hours

1. The full 5-step beginner path above.
2. All four use cases in [`use-cases/`](./use-cases/) — they overlap on purpose, each one stresses a different pattern.
3. The per-service docs in [`services/`](./services/) for every service touched by those use cases.
4. The [interview cheatsheet](./INTERVIEW-CHEATSHEET.md) and the per-service `INTERVIEW-NOTES.md` files (see [Existing docs](#existing-docs-this-set-complements-do-not-replace) below) for interview-specific framing.

---

## Full table of contents

### Foundational docs (read in order)

- [00-glossary.md](./00-glossary.md) — Plain-English reference for every Spring Boot, Spring Cloud, Kafka, and auth term used in the rest of the docs.
- [01-architecture-guide.md](./01-architecture-guide.md) — The beginner-friendly system map: every service, every port, every pattern, every cross-service flow.
- [02-how-auth-works.md](./02-how-auth-works.md) — End-to-end walkthrough of Samato's auth: registration, login, RS256 JWTs, JWKS, gateway validation, header propagation, defence in depth, and the four documented anomalies.

### Per-service docs

- [services/api-gateway.md](./services/api-gateway.md) — Spring Cloud Gateway (WebFlux) at port 8080: the front door, JWT validation at the edge, route predicates, CORS, correlation id, and the `X-User-*` header re-emission.
- [services/auth-service.md](./services/auth-service.md) — Spring Authorization Server at port 9000: the only place that holds the RSA private key, mints JWTs, and publishes JWKS for every other service.
- [services/config-service.md](./services/config-service.md) — Spring Cloud Config Server at port 8888: the central, versioned source of truth for every other service's configuration, backed by the local `config-repo/` folder.
- [services/discovery-service.md](./services/discovery-service.md) — Eureka at port 8761: the phone book; every other service registers here and discovers each other through it.
- [services/order-service.md](./services/order-service.md) — Saga orchestrator at port 8083: order lifecycle, transactional outbox, idempotency-key replay, server-side pricing, polled resumability.
- [services/payment-service.md](./services/payment-service.md) — Event-sourced payment ledger at port 8084: Razorpay integration, transactional outbox, idempotency on `(command_type, idempotency_key)`, circuit breaker on the Razorpay boundary.
- [services/restaurant-service.md](./services/restaurant-service.md) — Catalog at port 8082: restaurants, menus, items; Redis cache-aside, transactional outbox to Kafka, optimistic locking, ownership-aware `@PreAuthorize`.
- [services/search-service.md](./services/search-service.md) — OpenSearch read model at port 8087: CQRS read side, Kafka-driven projection from `restaurant-service`, geo-distance queries, idempotent consumer.
- [services/user-service.md](./services/user-service.md) — User profile at port 8081: separate from auth, role-aware methods, OpenFeign client to auth-service wrapped in Resilience4j, logical-reference join keys.
- [services/shared-and-kafka.md](./services/shared-and-kafka.md) — The two library modules (not deployable services) pulled in as Maven dependencies: shared DTOs / error model / Feign base, and the Kafka event contracts.

### Use-case walkthroughs

- [use-cases/01-place-an-order.md](./use-cases/01-place-an-order.md) — The end-to-end saga: HTTPS request through the gateway, into order-service, across Feign to restaurant-service and payment-service, and out to Kafka.
- [use-cases/02-auth-flow.md](./use-cases/02-auth-flow.md) — Register, log in, call a protected API: the full identity stack from a brand-new account to a validated `GET /api/users/me`.
- [use-cases/03-browse-and-search.md](./use-cases/03-browse-and-search.md) — The CQRS read path: a write to `restaurant-service` (Postgres) becomes a Kafka event becomes an OpenSearch document a customer can search.
- [use-cases/04-refund-flow.md](./use-cases/04-refund-flow.md) — The compensation path of the saga; the textbook walkthrough of why the events table is the source of truth, not the row in `payment_view`.

### Interview aids

- [INTERVIEW-CHEATSHEET.md](./INTERVIEW-CHEATSHEET.md) — Quick-look Q&A for the most-asked microservice interview topics, each entry pointing at the code that demonstrates the answer.

### Inventory (machine-readable)

- [inventory/](./inventory/) — The JSON files an automated tool (or a future agent) can read; this is the machine-readable source of truth that the markdown docs were generated from. See [The inventory](#the-inventory) below for the full file list.

---

## Service quick reference

| Service | Port | Headline pattern | Per-service doc | Use case that exercises it most |
| ------- | ---- | ---------------- | --------------- | ------------------------------- |
| api-gateway | 8080 | API Gateway (Spring Cloud Gateway, WebFlux) + edge JWT validation | [api-gateway.md](./services/api-gateway.md) | [01-place-an-order.md](./use-cases/01-place-an-order.md) |
| auth-service | 9000 | OAuth2 / OIDC issuer (Spring Authorization Server) + RS256 JWT + JWKS | [auth-service.md](./services/auth-service.md) | [02-auth-flow.md](./use-cases/02-auth-flow.md) |
| config-service | 8888 | Centralized Configuration (Spring Cloud Config Server) | [config-service.md](./services/config-service.md) | n/a — infra-only, touched by every other service at boot |
| discovery-service | 8761 | Service Discovery (Eureka) | [discovery-service.md](./services/discovery-service.md) | n/a — infra-only, touched by every other service at boot |
| order-service | 8083 | Saga orchestrator + Transactional Outbox + Idempotency-Key replay | [order-service.md](./services/order-service.md) | [01-place-an-order.md](./use-cases/01-place-an-order.md) |
| payment-service | 8084 | Event Sourcing + CQRS + Transactional Outbox + circuit breaker | [payment-service.md](./services/payment-service.md) | [04-refund-flow.md](./use-cases/04-refund-flow.md) |
| restaurant-service | 8082 | CQRS-lite (cache-aside) + Transactional Outbox + Optimistic Locking | [restaurant-service.md](./services/restaurant-service.md) | [03-browse-and-search.md](./use-cases/03-browse-and-search.md) |
| search-service | 8087 | Event-driven projection + CQRS read model + idempotent consumer | [search-service.md](./services/search-service.md) | [03-browse-and-search.md](./use-cases/03-browse-and-search.md) |
| user-service | 8081 | OAuth2 resource server + role-based @PreAuthorize + OpenFeign with circuit breaker | [user-service.md](./services/user-service.md) | [02-auth-flow.md](./use-cases/02-auth-flow.md) |

---

## Use-case quick reference

| Use case | Primary services | Link |
| -------- | ---------------- | ---- |
| Place an order | api-gateway, order-service, restaurant-service, payment-service, Kafka | [01-place-an-order.md](./use-cases/01-place-an-order.md) |
| Register, log in, call a protected API | api-gateway, auth-service, user-service | [02-auth-flow.md](./use-cases/02-auth-flow.md) |
| Browse and search restaurants | api-gateway, restaurant-service, Kafka, search-service | [03-browse-and-search.md](./use-cases/03-browse-and-search.md) |
| Refund a captured payment | api-gateway, order-service, payment-service, Kafka | [04-refund-flow.md](./use-cases/04-refund-flow.md) |

---

## If you only have time for one doc

Read **[01-architecture-guide.md](./01-architecture-guide.md)**. It is the system map. After it, you know the shape of every service, where every pattern lives, and which per-service doc to open next.

If the conversation turns to auth, switch to **[02-how-auth-works.md](./02-how-auth-works.md)** — it is the one deep dive that earns its length, because every other service in the codebase is downstream of it.

---

## Existing docs this set complements (do not replace)

This doc set is the **"file-by-file mechanics"** — what every controller does, what every entity looks like, what every Kafka topic carries, what every Feign client calls. The Samato repo already has a parallel set of **"designer notes"** that explain *why* the code is shaped the way it is. Read both; they answer different questions.

| Doc | Purpose | Where it lives |
| --- | ------- | -------------- |
| ARCHITECTURE.md | The whiteboard view: high-level system diagram, rationale, trade-offs, the design conversation. The "why" of Samato. | [../ARCHITECTURE.md](../ARCHITECTURE.md) |
| RUN-THE-BIBLE.md | The operator's manual: how to bring the whole stack up locally and exercise the saga end-to-end. | [../RUN-THE-BIBLE.md](../RUN-THE-BIBLE.md) |
| PROJECT-STATUS.md | What's done, what's pending, what's risky — kept honest, with a last-updated date. | [../PROJECT-STATUS.md](../PROJECT-STATUS.md) |
| docs/INTERVIEW-CHEATSHEET.md | Quick-look Q&A for the most-asked microservice interview topics, each pointing at the code that demonstrates the answer. | [./INTERVIEW-CHEATSHEET.md](./INTERVIEW-CHEATSHEET.md) |
| Per-service `INTERVIEW-NOTES.md` | Designer notes for one service at a time: what the service is *for*, what to say about it in an interview, which patterns it demonstrates. | One file per service, under `services/<name>/docs/INTERVIEW-NOTES.md` — see [api-gateway](../services/api-gateway/docs/INTERVIEW-NOTES.md), [auth-service](../services/auth-service/docs/INTERVIEW-NOTES.md), [config-service](../services/config-service/docs/INTERVIEW-NOTES.md), [discovery-service](../services/discovery-service/docs/INTERVIEW-NOTES.md), [order-service](../services/order-service/docs/INTERVIEW-NOTES.md), [payment-service](../services/payment-service/docs/INTERVIEW-NOTES.md), [restaurant-service](../services/restaurant-service/docs/INTERVIEW-NOTES.md), [search-service](../services/search-service/docs/INTERVIEW-NOTES.md), [user-service](../services/user-service/docs/INTERVIEW-NOTES.md). |

The short version: the existing set is **"why this design?"**, this set is **"what does the code actually do?"**.

---

## The inventory

The [`inventory/`](./inventory/) directory contains **JSON files** that document the codebase in a machine-readable form. An automated tool (or a future agent) can read them to regenerate the markdown docs, run cross-checks, or answer questions about the system without re-parsing the Java. The markdown docs in this directory were generated from the inventory; the inventory is the source of truth.

Files:

- [inventory/shared-and-kafka.json](./inventory/shared-and-kafka.json) — Inventory of the shared-and-kafka library modules.
- [inventory/infrastructure.json](./inventory/infrastructure.json) — Infrastructure topology: ports, dependencies, infra (Postgres, Redis, OpenSearch, Kafka, Eureka, Config Server).
- [inventory/call-graph.json](./inventory/call-graph.json) — Cross-service call graph: who calls whom, over what transport (Feign, Kafka, HTTP).
- [inventory/endpoints-and-use-cases.json](./inventory/endpoints-and-use-cases.json) — Every public endpoint mapped to the use case(s) that exercise it.
- [inventory/_completeness-report.json](./inventory/_completeness-report.json) — The completeness report: which files are documented, which are partial, which are gaps.
- [inventory/per-service/](./inventory/per-service/) — One JSON file per service: api-gateway, auth-service, config-service, discovery-service, order-service, payment-service, restaurant-service, search-service, user-service.

If you change the code, regenerate the inventory first, then regenerate the markdown from the inventory. The markdown is a view.

---

## Conventions used in this doc set

- **Every cross-reference is a relative markdown link.** No `file://` URLs, no absolute paths, no broken anchors — every link is meant to work from a checkout of the repo.
- **Every Spring annotation is glossed in plain English.** If you see `@TransactionalEventListener(phase = AFTER_COMMIT)`, the doc tells you what it does in one sentence before the surrounding paragraph assumes you know. The glossary is the fallback.
- **Every "what it calls / what calls it" is backed by the source code.** Statements like "order-service calls restaurant-service over OpenFeign" point to the actual `@FeignClient` interface in the source. If a claim is not in the source, the doc says so explicitly.
- **Anomalies are documented explicitly, not hidden.** Where the code does something surprising — auth-service's JWKS quirks, payment-service's webhook-only refund flow, the four load-bearing anomalies called out in `02-how-auth-works.md` — there is a section for it, not a footnote. The reader should be able to trust that if a thing is unusual and the docs do not flag it, it is not actually unusual.
