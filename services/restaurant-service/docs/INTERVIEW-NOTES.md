## Status (2026-07-08)

✅ **Verified running on this machine.** The service image is built by `docker compose build` from the local jar, the container is `Up (healthy)`, `/actuator/health` returns **HTTP 200** with `{"status":"UP"}`, and the service is registered in **Eureka** as `SAMATO-RESTAURANT-SERVICE`.

- **Port:** 8082
- **Image:** samato-restaurant-service:dev (compose tags it `restaurant-service:latest`)
- **Health:** `curl http://localhost:8082/actuator/health` → `{"status":"UP", ...}`
- **Bring-up bug fixes in this service**: added `spring.cloud.config.enabled: false`, removed `@Lob` from `OutboxEvent` (replaced with `columnDefinition = "BYTEA"`), fixed `AvroBytes` to use `SeekableByteArrayInput` (not `ByteArrayInputStream`).

---

# Restaurant Service — Interview Notes

## What it does (1 line)
Owns the catalog: restaurants, their menus, and menu items. Serves reads
from a Redis cache, writes through the transactional outbox to Kafka,
which feeds search-service.

## Why this service exists
The catalog is the most-read part of the system. A thousand users browse
restaurants for every one who places an order. The read:write ratio is
1000:1, so the design optimizes for reads (Redis cache) while keeping
writes safe (outbox + events).

## Patterns demonstrated
| Pattern | Where | Why it matters |
|---------|-------|----------------|
| **CQRS-lite (cache-aside)** | `RestaurantService` + `CacheConfig` | Cache for reads, DB for writes, evict on every write |
| **Cache eviction** | `@CacheEvict` on `create/update/deactivate` | The cache is invalidated; next read repopulates |
| **Transactional outbox** | `Outbox` + `OutboxEvent` | Atomic DB + event; no dual-write problem |
| **Event-driven projection** | `Outbox.enqueueRestaurantCreated` | search-service consumes the event |
| **Avro + Schema Registry** | `shared-kafka` + .avsc files | Strong types across services |
| **Resource server** | `SecurityConfig` | Validates JWTs from auth-service's JWKS |
| **Method-level authZ** | `@PreAuthorize` + `RestaurantOwnership` | Owner-only writes, admin override |
| **Optimistic locking** | `@Version` on `Restaurant` | Prevents lost updates in concurrent writes |
| **Soft-delete** | `active` boolean | We never hard-delete restaurants; orders may reference them |
| **Idempotent Kafka producer** | `enable.idempotence=true` + `acks=all` | No duplicates from producer retries |
| **@Scheduled poller** | `Outbox.publishPending` | Decouples publishing from the request thread |
| **Geospatial data** | `latitude`, `longitude` columns | Phase 6 delivery-service uses for nearest-driver |
| **JSONB for preferences** | (not here, but same pattern) | Postgres-native flexible data |

## Key endpoints
| Method | Path | Role | Purpose |
|--------|------|------|---------|
| `GET`    | `/api/restaurants/{id}` | any auth'd | Get a restaurant (cached) |
| `GET`    | `/api/restaurants?city=X` | any auth'd | Browse by city |
| `GET`    | `/api/restaurants/{id}/menu` | any auth'd | Get the menu |
| `POST`   | `/api/restaurants` | RESTAURANT_OWNER | Create a restaurant |
| `PUT`    | `/api/restaurants/{id}` | owner or ADMIN | Update a restaurant |
| `DELETE` | `/api/restaurants/{id}` | owner or ADMIN | Deactivate |
| `POST`   | `/api/restaurants/{id}/menu` | owner or ADMIN | Add a menu item |

## Events published
| Topic | Event | Trigger |
|-------|-------|---------|
| `samato.restaurant.created` | `RestaurantCreatedEvent` | After `create()` |
| `samato.restaurant.updated` | `RestaurantUpdatedEvent` | After `update()` and `deactivate()` |

search-service consumes these to maintain the OpenSearch projection.

## Interview Q&A

**Q: What is the read model vs. the write model?**
A: The **write model** is the Postgres `restaurants` table — normalized,
optimized for inserts and updates. The **read model** is the Redis cache —
denormalized, optimized for `GET /restaurants/{id}`. A request first tries
Redis; on a miss, it goes to Postgres and populates the cache. On a write,
the cache entry is invalidated.

This is the **simplest form of CQRS** (Command-Query Responsibility
Segregation). The "C" and "Q" use the same store but different
representations. The full split — separate DB for reads, e.g. OpenSearch
— is what search-service does.

**Q: What is the transactional outbox pattern and why do you need it?**
A: Without it, you have a **dual-write problem**: you write to Postgres,
then call Kafka, then maybe Kafka is down. Your DB says the restaurant
exists, but no consumer was told. The system is now inconsistent.

The outbox pattern fixes this by writing a row to `outbox_events` in the
**same DB transaction** as the business write. A scheduled poller reads
unsent rows, publishes them, and marks them sent. If the poller crashes,
events are still in the DB and get retried. Atomic.

**Q: Why poll instead of using a CDC tool like Debezium?**
A: Polling is simpler — one Java class, one Spring `@Scheduled`. It
introduces a 1-second latency floor (acceptable for most products).
Debezium reads the Postgres WAL in real-time, sub-second latency, but
requires running Kafka Connect and a separate config. For the bible,
polling is the right trade-off.

**Q: How do you ensure events are delivered exactly once?**
A: You don't — Kafka only gives you at-least-once. But you can achieve
**effectively-once** with:
- Idempotent producer (`enable.idempotence=true` + `acks=all`) → no
  duplicates from the producer side.
- Idempotent consumers (dedup on `eventId`).
- The outbox + at-least-once + idempotent consumer = effectively-once.

**Q: What if the outbox poller publishes the event but crashes before
marking it sent?**
A: The next poll re-publishes it. Consumers see a duplicate. The
`eventId` is the dedup key — consumers must be idempotent.

**Q: How does the cache stay consistent with the DB?**
A: The cache is invalidated on every write (`@CacheEvict`). There's a
small window where another reader might re-populate the cache with
**stale** data if there's a concurrent read mid-write. For the bible
that's fine; for stricter consistency, use a `READ COMMITTED` lock or
write-through (write DB + write cache in the same transaction-like flow).

**Q: Why is the `findByCity` endpoint NOT cached?**
A: Lists change shape with every restaurant added in that city. A
"browse" cache is harder to invalidate (you'd have to evict it on every
write to ANY restaurant in that city). For lists, the right tool is
**OpenSearch** (Phase 3) or a denormalized read model. The Redis cache
is for **single-item** reads.

**Q: How do you scale this service?**
A: 1. Stateless → horizontal scale behind Eureka. 2. Read replicas for
Postgres if the cache miss rate is high. 3. The cache is already
distributed (Redis). 4. The outbox poller is single-threaded; if
write throughput is high, run it on a separate pod.

## Trade-offs we made
- **Cache-aside vs write-through** — cache-aside is simpler and good
  enough; write-through gives stronger consistency at the cost of
  complexity.
- **Polling outbox vs Debezium** — see above.
- **Synchronous send in the poller** — slower than `.whenComplete`, but
  we want to know it succeeded before marking sent. Phase 8 can move to
  async + idempotent re-poll on failure.
- **Outbox runs in the same service** — for very high throughput, you'd
  run a separate "outbox dispatcher" pod. For the bible, in-process is fine.
- **No event versioning yet** — the schema is a V1; backward-compatible
  changes work for free, breaking changes need a V2 schema and a
  compatibility policy.

## Follow-ups interviewers ask
- "How do you handle a restaurant being deleted?" → Soft-delete via the
  `active` flag. Hard delete would orphan orders; soft delete preserves
  history.
- "How do you rate-limit writes?" → Per-owner bucket in Redis. Out of
  scope for the bible.
- "What about menus in multiple languages?" → Add a `translations` JSONB
  column; the read model can fan out to per-locale search indexes.
- "How do you handle images?" → Object store (S3) + URLs in a column.
  Images are NOT in Postgres.
