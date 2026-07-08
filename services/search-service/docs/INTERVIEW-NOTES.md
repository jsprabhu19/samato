## Status (2026-07-08)

✅ **Verified running on this machine.** The service image is built by `docker compose build` from the local jar, the container is `Up (healthy)`, `/actuator/health` returns **HTTP 200** with `{"status":"UP"}`, and the service is registered in **Eureka** as `SAMATO-SEARCH-SERVICE`.

- **Port:** 8087
- **Image:** samato-search-service:dev (compose tags it `search-service:latest`)
- **Health:** `curl http://localhost:8087/actuator/health` → `{"status":"UP", ...}`
- **Bring-up bug fixes in this service**: added `spring.cloud.config.enabled: false`.

---

# Search Service — Interview Notes

## What it does (1 line)
Maintains an OpenSearch index of restaurants, fed by Kafka events from
restaurant-service. Exposes full-text + geo + filter search at `/api/search`.

## Why this service exists
The "find me a Thai place near me open now" query is **read-heavy** and
**shape-rich** (text + geo + filters + sort). Postgres can do it, but
slowly and with hand-rolled indexes. OpenSearch is purpose-built for it.

Splitting it out is the textbook example of **CQRS**:
- Write model: Postgres in restaurant-service (the source of truth).
- Read model: OpenSearch here (denormalized, indexed for queries).

## Patterns demonstrated
| Pattern | Where | Why it matters |
|---------|-------|----------------|
| **CQRS read model** | The OpenSearch index | Different storage, different query language |
| **Event-driven projection** | `RestaurantEventListener` + `RestaurantProjector` | Never calls restaurant-service — pure event consumer |
| **Idempotent consumer** | `IndexRequest.id(restaurantId)` | Re-delivery is a no-op upsert |
| **Manual offset commit** | `ack.acknowledge()` | At-least-once: only commit after projection succeeds |
| **Schemaless index** | `OpenSearchIndexInitializer` | Map fields freely; explicit mapping only for type-sensitive fields |
| **Geo queries** | `geoDistanceQuery` in `SearchController` | "Restaurants within 5 km" |
| **Filter vs must** | `boolQuery.filter()` | Filter is cacheable, doesn't affect score |
| **Per-topic consumer group** | `groupId = "samato-search-service"` | Multiple instances share the work |
| **Background index init** | `ApplicationReadyEvent` | Idempotent: no-op if index exists |
| **Resource server** | `SecurityConfig` | Validates JWTs (any auth'd user can search) |
| **SpEL authZ** | `@PreAuthorize("isAuthenticated()")` | Method-level, declarative |

## Key endpoints
| Method | Path | Role | Purpose |
|--------|------|------|---------|
| `GET` | `/api/search/restaurants?q=thai&cuisine=Thai&city=Bangalore&lat=12.97&lon=77.59&radiusKm=5` | any auth'd | Search |

### Example query parameters

| Param | Type | Effect |
|-------|------|--------|
| `q` | string | Multi-match across `name` and `description` |
| `cuisine` | string | Exact-match filter (e.g. "Italian") |
| `city` | string | Exact-match filter (e.g. "Bangalore") |
| `lat`, `lon`, `radiusKm` | numbers | Geo-distance filter (within N km of a point) |
| `size` | int (default 20) | Page size |

## Interview Q&A

**Q: Why not just use Postgres full-text search?**
A: You can, and for many products you should. The cutoff is roughly:
- Postgres FTS is great for: small catalogs, single-language text, no geo.
- OpenSearch is better for: large catalogs, multi-language, geo, faceting,
  fuzzy matching, "did you mean" suggestions, scoring.
For a food delivery app with geo + cuisine + city + free-text: OpenSearch wins.

**Q: How do you keep the OpenSearch index in sync with the source of truth?**
A: Event-driven projection:
- restaurant-service writes to Postgres + an outbox row in the same transaction.
- A poller in restaurant-service ships the outbox row to Kafka.
- search-service consumes the Kafka event and upserts the document.
- The upsert uses `restaurantId` as the OpenSearch `_id`, so re-delivery
  is a no-op.

This is **eventual consistency** — there's a small window (sub-second)
where the search index lags the database. For a search use case, that's
acceptable. For an order-summary use case, you'd query the source of
truth instead.

**Q: What if the consumer falls behind?**
A: Kafka's partitioning means events are ordered per restaurantId. The
consumer reads sequentially. If the consumer crashes, on restart it
resumes from the last committed offset. If the consumer is permanently
behind (e.g. slower than the producer), scale the consumer: more
partitions + more consumer instances. Kafka rebalances automatically.

**Q: What about deletes?**
A: We don't hard-delete restaurants in this design — restaurant-service
emits `RestaurantUpdated` with `active = false`. The projector could
either:
- Mark the doc inactive (so search filters it out).
- Delete the doc (the index never has stale data).
- Keep it (analytics can still query "ever opened in city X").
The bible uses soft-delete + filter (`active: true` in the doc);
Phase 8 might switch to hard-delete.

**Q: How do you handle schema evolution in the index?**
A: Two strategies:
1. **Mapping versioning**: keep multiple index versions, route writes
   to the current one, dual-query both during transitions. Heavy.
2. **Additive changes only**: only add new fields. Old docs don't have
   them, but the query is forward-compatible. The bible's approach.

**Q: Why is the consumer `ack.acknowledge()` inside the `try`?**
A: Manual acknowledgement: if the projection throws, we DON'T ack. The
next poll re-delivers the same event. The projection is idempotent
(upsert by `_id`), so re-delivery is safe. The cost: in the worst case,
an event is processed twice.

**Q: How do you handle poison messages?**
A: Three options:
1. **Retry in place** with a max-attempts counter. After N failures,
   send to a DLQ topic. The bible doesn't do this yet (Phase 8).
2. **Skip and log** — risky, can lose data.
3. **Halt the consumer** — operational overhead.

**Q: How do you scale search?**
A: The OpenSearch index is sharded. Add more shards (re-index required)
or more replicas (zero-downtime). The consumer scales by adding
partitions to the topic. The search API is stateless behind Eureka.

## Trade-offs we made
- **Poll-based outbox in restaurant-service vs Debezium** — simpler, 1s
  latency floor. Fine for search.
- **Soft-delete vs hard-delete in the index** — soft-delete preserves
  data for analytics. Hard-delete is simpler.
- **Legacy high-level client vs new java client** — legacy is more
  documented; new is more typed. Legacy chosen for the bible.
- **Single index "restaurants"** — fine for the bible. A multi-tenant
  app would shard by tenant: `restaurants-{tenant}`.
- **No fuzzy match / did-you-mean** — kept simple. Phase 8 can add
  OpenSearch's `suggest` API.
- **Manual ack with `manual_immediate`** — slowest but most correct.
  `manual` (batched) is faster but you can lose acks on crash.

## Follow-ups interviewers ask
- "How do you handle the index growing huge?" → ILM (Index Lifecycle
  Management) for time-based rollover; or just more shards.
- "What's the p99 latency of a search?" → It depends on shard count
  and result size. Measure with a load test.
- "How do you handle multiple languages?" → Per-locale analyzers in the
  mapping; or one index per language.
- "What about autocomplete?" → A separate "edge-ngram" or completion
  field in the mapping.
