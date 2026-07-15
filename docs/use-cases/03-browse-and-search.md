# Use case: Browse and search restaurants

> Bob owns "Spicy Noodle House" in Bangalore. He signs up as a `RESTAURANT_OWNER`, fills in his restaurant profile, and adds a menu. A few seconds later, Alice — a `CUSTOMER` — opens the app, types "spicy noodles" and gets Bob's restaurant in the results, with the menu one click away. This is the **CQRS read path** in Samato: a write to `restaurant-service` (Postgres) becomes an event on Kafka, and `search-service` projects that event into an OpenSearch index that customers actually query.

## 1. The story

This use case is the simplest end-to-end CQRS flow in the monorepo. Two people, two roles, two services on the write path, one on the read path:

| Actor | Role | What they do | Which service handles it |
|---|---|---|---|
| Bob | `RESTAURANT_OWNER` | Register, complete profile, create restaurant, add menu | `auth-service` (register) → `user-service` (profile) → `restaurant-service` (catalog) |
| Alice | `CUSTOMER` | Search "spicy noodles" near her, open a result | `api-gateway` (route) → `search-service` (read model in OpenSearch) |

The architecture in between is a textbook **CQRS** split:

- **Write side** — `restaurant-service` owns the system of record (Postgres) and emits Avro events to Kafka. The transactional outbox pattern guarantees that a `Restaurant` row and its event row commit together. The outbox poller is a separate thread that ships the event to Kafka at-least-once.
- **Read side** — `search-service` owns the denormalized search index (OpenSearch). It consumes Kafka, projects events into documents, and serves `GET /api/search/restaurants` for any authenticated user.

The reason for the split is the standard CQRS argument: the access pattern of the read side (full-text + filter + geo-distance) is **different** from the access pattern of the write side (single-row CRUD by id with strong consistency). Forcing them into one store makes both worse. Postgres is great at transactions; OpenSearch is great at fuzzy search across a million documents. Samato uses both.

This is documented in the [glossary under CQRS](../00-glossary.md#cqrs) and in [restaurant-service.md](../services/restaurant-service.md) and [search-service.md](../services/search-service.md). The per-service docs go file-by-file; this use case pulls them together into one trace.

> **A note on consistency.** The write side commits to Postgres within milliseconds. The event reaches Kafka within ~1 second (the outbox poller interval). The projector in `search-service` updates OpenSearch within another few hundred ms. **End-to-end visibility is typically 1–2 seconds.** This is what [eventual consistency](../00-glossary.md#idempotent-consumer) means in practice: the read model is correct *eventually*, not immediately.

---

## 2. The cast of characters

ASCII diagram of every service, store, and data movement in this use case:

```
                    Bob (owner)                                       Alice (customer)
                        |                                                  |
                        |  HTTPS + JWT                                     |  HTTPS + JWT
                        v                                                  v
                  +---------------------------------------------------+
                  |            api-gateway (port 8080)                |
                  |  - CorrelationIdWebFilter (stamps X-Correlation-Id)|
                  |  - JwtAuthFilter (validates RS256, adds X-User-Id) |
                  |  - Spring Cloud Gateway routes /api/restaurants/**  |
                  |      to SAMATO-RESTAURANT-SERVICE                   |
                  |  - routes /api/search/** to SAMATO-SEARCH-SERVICE   |
                  +---------+--------------------+----------------------+
                            |                    |
                  POST /api/                   GET /api/
                  restaurants                  search/restaurants
                            |                    |
                            v                    v
            +--------------------------+    +---------------------------+
            | restaurant-service :8082 |    | search-service :8087      |
            |  - write side, CQRS write |    |  - read side, CQRS read   |
            |  - Postgres + Redis cache |    |  - OpenSearch index       |
            |  - Outbox + Kafka producer |    |  - @KafkaListener         |
            +-----+----------------+----+    +-------------+-------------+
                  |                |                      |
            SELECT/         INSERT (txn)              search()
            UPSERT          restaurants +             OpenSearch
            Redis cache     outbox_events             "restaurants"
            "restaurants"   (same txn)                  index
                  |                |                      ^
                  v                v                      |
            +-----------+   +-------------+   Avro     |
            | Postgres  |   | outbox_     |   event    |
            | restaurant|   | events      +---------> Kafka
            | _service  |   | table       |     (samato.restaurant.
            | DB        |   +-------------+      created/updated)
            +-----------+         |                Confluent Avro
                                | | 1s @Scheduled  wire format
                                v v
                          +-------------+
                          |   Outbox    |  KafkaTemplate<String,SpecificRecord>
                          |  poller     |  shared-kafka
                          +------+------+
                                 |
                                 v
                          +------------------+
                          |  Kafka (KRaft)   |
                          |  samato.         |
                          |  restaurant.*    |
                          +--------+---------+
                                   |
                                   | Confluent Avro
                                   | (magic byte + schema id + binary)
                                   v
                          +------------------+
                          | Schema Registry  |
                          | :8081            |
                          +------------------+

                                 -- read side: search-service consumes --

                          +------------------+
                          | search-service   |
                          | RestaurantEvent- |   @KafkaListener
                          | Listener         |   groupId=samato-search-service
                          +--------+---------+
                                   |
                                   v
                          RestaurantProjector.apply()
                          upsert by restaurantId (= _id)
                                   |
                                   v
                          +------------------+
                          | OpenSearch :9200 |
                          | index:           |
                          | "restaurants"    |
                          | - text fields    |
                          | - geo_point      |
                          | - keyword filters|
                          +------------------+
```

The boxes you'll see referenced by name in this doc:

- **api-gateway** ([api-gateway.md](../services/api-gateway.md)) — single front door, route table at [`GatewayRoutesConfig.java`](../../services/api-gateway/src/main/java/com/samato/apigateway/config/GatewayRoutesConfig.java).
- **auth-service** (port 9000) — issues the JWT that Bob and Alice present. See [auth-service.md](../services/auth-service.md). Not traversed in this use case beyond the token-issuance step already covered by [02-auth-flow.md](./02-auth-flow.md).
- **user-service** (port 8081) — stores Bob's `restaurant_owner_profiles` row. See [user-service.md](../services/user-service.md).
- **restaurant-service** (port 8082) — the write side. Postgres + Redis cache + outbox + Kafka producer. See [restaurant-service.md](../services/restaurant-service.md).
- **search-service** (port 8087) — the read side. OpenSearch + Kafka consumer. See [search-service.md](../services/search-service.md).
- **Postgres** — the system-of-record for `restaurant-service`. One database per service (`restaurant_service`), provisioned by `init-databases.sh`.
- **Redis** — cache for `Restaurant` lookups by id. Key shape `restaurants::<id>`, TTL 5 min. Not on the critical path for this use case (it speeds up `GET /api/restaurants/{id}` for Alice's "click into" step).
- **Kafka** — the event backbone. The only consumer of restaurant topics in the entire monorepo is `search-service` (see the [call-graph inventory](../inventory/call-graph.json#kafkalisteners) — `kafkaListeners[]` has exactly one entry).
- **Schema Registry** — Confluent. Used by the Avro wire format that `restaurant-service` and `search-service` share. See [shared-and-kafka.md § Schema Registry](../services/shared-and-kafka.md#schema-registry).
- **OpenSearch** — the denormalized read model. Index `restaurants`, with a `geo_point` field for "near me" queries.

---

## 3. Step 1: Bob registers and creates a profile

Before Bob can add a restaurant, the system has to know who he is. This step is the prerequisite; it's covered in full in [02-auth-flow.md](./02-auth-flow.md). A short version here, with the parts that matter for the browse-and-search flow:

1. **Register.** `POST http://localhost:8080/api/auth/register` with `{ "email": "bob@example.com", "password": "hunter2", "roles": ["RESTAURANT_OWNER"] }`. The request lands at the gateway, the path is matched by `GatewayRoutesConfig.routes` to `lb://SAMATO-AUTH-SERVICE`, and `auth-service` (a Spring Authorization Server) hashes the password with BCrypt and writes a row to the `users` table in the `auth` database. The roles array `["RESTAURANT_OWNER"]` is stored in `user_roles`.
2. **Get a token.** `POST http://localhost:8080/api/auth/login` (or the standard `oauth2/token` endpoint) returns an RS256 access token (15-min TTL) and a refresh token. The token's `sub` claim is Bob's UUID; the `roles` claim is `["RESTAURANT_OWNER"]`.
3. **Complete the restaurant-owner profile.** `GET http://localhost:8080/api/users/restaurant-owners/me` (with `Authorization: Bearer ...`). On the first call, the controller's `getOrCreate` helper writes a skeleton row to `restaurant_owner_profiles` (in user-service's `user_service` Postgres database). Bob can then `PUT` to fill in the real `businessName`, `contactEmail`, etc.

The relevant files:

- `services/auth-service/src/main/java/.../RegistrationController.java` — the `POST /api/auth/register` handler (see [auth-service.md](../services/auth-service.md) for the file-by-file tour).
- `services/user-service/src/main/java/com/samato/userservice/web/RestaurantOwnerProfileController.java` — the `GET /api/users/restaurant-owners/me` and `PUT` handlers, with `@PreAuthorize("hasRole('RESTAURANT_OWNER')")` guarding both.
- `services/user-service/src/main/resources/db/migration/V1__init.sql` — the migration that creates `restaurant_owner_profiles`. The `user_id` column is the join key to `auth-service`'s `users` table. There is **no database foreign key** — see the comment in the migration's preamble ("services don't share databases") and the [glossary entry on Database-per-service](../00-glossary.md#database-per-service).

> **Why is the profile step not strictly needed for "create restaurant"?** It's not. `RestaurantController.create` reads the JWT subject to get Bob's UUID and stores it in `restaurants.owner_id`. The user-service profile is for display and future analytics (e.g. dashboard for owners). If you skip it, the restaurant still gets created; only the owner-facing dashboard is empty.

---

## 4. Step 2: Bob adds his restaurant

Bob calls the create endpoint. The full request:

```http
POST /api/restaurants HTTP/1.1
Host: localhost:8080
Authorization: Bearer <OWNER_JWT>
Content-Type: application/json
X-Correlation-Id: 9b2e4f7a-...    (optional; the gateway will mint one if missing)

{
  "name": "Spicy Noodle House",
  "description": "Hand-pulled noodles and Sichuan peppercorns",
  "cuisine": "Chinese",
  "address": "12 MG Road, Indiranagar",
  "city": "Bangalore",
  "latitude": 12.9716,
  "longitude": 77.5946
}
```

What happens, step by step:

### 4.1 The gateway routes it

`api-gateway`'s `GatewayRoutesConfig.routes` matches `/api/restaurants/**` and forwards to `lb://SAMATO-RESTAURANT-SERVICE`. The `JwtAuthFilter` has already validated the RS256 signature against `auth-service`'s JWKS and added `X-User-Id` and `X-User-Roles` headers; the `CorrelationIdWebFilter` has stamped `X-Correlation-Id` (or generated one). See [api-gateway.md § 3.6](../services/api-gateway.md#36-securityjwtauthfilterjava) for the JWT filter and [§ 3.2](../services/api-gateway.md#32-configcorrelationidwebfilterjava) for the correlation filter.

### 4.2 `restaurant-service` re-validates the JWT (defence in depth)

The `CorrelationIdFilter` (a servlet filter from the `shared` module) picks up the same `X-Correlation-Id` and stuffs it into the SLF4J [MDC](../00-glossary.md#mdc) so every log line in the service is tagged with it. Then `SecurityConfig.filterChain` runs `NimbusJwtDecoder.withJwkSetUri(...)` again, validating the token against the same JWKS. The service trusts nothing from the gateway.

### 4.3 `RestaurantController.create` runs

In [`services/restaurant-service/src/main/java/com/samato/restaurantservice/web/RestaurantController.java`](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/web/RestaurantController.java):

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public Restaurant create(@AuthenticationPrincipal Jwt jwt,
                         @Valid @RequestBody CreateRestaurantRequest req) {
    Restaurant r = new Restaurant();
    r.setName(req.name());
    // ... set all fields from the DTO ...
    return service.create(UUID.fromString(jwt.getSubject()), r);
}
```

Three things to notice:

- **`@PreAuthorize("hasRole('RESTAURANT_OWNER')")`** — if Bob's token doesn't carry this role, Spring Security returns 403 before any business code runs. `@EnableMethodSecurity` is on `SecurityConfig` so the annotation is honored.
- **`@AuthenticationPrincipal Jwt jwt`** — Spring injects the parsed JWT. The `sub` claim is Bob's UUID. The controller calls `UUID.fromString(jwt.getSubject())` and passes it to the service as `ownerId`. **The owner id is trusted from the JWT; the service does not call `user-service` to verify it exists.** This is the standard "JWT is the trust anchor" pattern. See [user-service.md § 7.2](../services/user-service.md#72-jwt-subject-is-the-userid--every-controller-relies-on-this) for the symmetric observation from the other side.
- **`@Valid`** on the DTO triggers Bean Validation. `name`, `cuisine`, `address`, `city` are `@NotBlank @Size(max=...)`; the latitude/longitude are `double` (no constraint). Bad input is rejected with 400 by `GlobalExceptionHandler` (from `shared`).

### 4.4 `RestaurantService.create` runs in a transaction

In [`RestaurantService.java`](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/service/RestaurantService.java):

```java
@Caching(evict = {
        @CacheEvict(value = CACHE, key = "#result.id")
})
@Transactional
public Restaurant create(UUID ownerId, Restaurant draft) {
    draft.setOwnerId(ownerId);
    if (draft.getId() == null) draft.setId(UUID.randomUUID());
    Restaurant saved = restaurants.save(draft);
    outbox.enqueueRestaurantCreated(saved);   // <-- same transaction
    return saved;
}
```

`@Transactional` opens a single Postgres transaction. Inside that transaction, two things happen:

1. **The `Restaurant` is saved** to the `restaurants` table. The `id` was generated client-side (a random UUID); Postgres writes a row with `id`, `owner_id` (the JWT subject), `name`, `description`, `cuisine`, `address`, `city`, `latitude`, `longitude`, `active=true`, `created_at=now()`, `updated_at=now()`, `version=0`.
2. **An outbox row is inserted** to `outbox_events`. This is the second and equally important side of the [transactional outbox](../00-glossary.md#transactional-outbox) pattern — the business write and the "intent to publish" commit **together**. If Postgres commits, the outbox row is there; if it rolls back, no event will ever be sent.

The cache eviction (`@CacheEvict(key = "#result.id")`) is a no-op for a brand-new id (the cache doesn't have it yet) but it makes the code pattern uniform across all three write methods.

> **The outbox table is in the SAME database as the business data.** This is the crucial property of the outbox pattern. The schema in [`V1__init.sql`](../../services/restaurant-service/src/main/resources/db/migration/V1__init.sql) shows the three tables (`restaurants`, `menu_items`, `outbox_events`) living in the same `restaurant_service` database. The outbox poller queries `outbox_events` (not Kafka); Kafka is downstream.

### 4.5 The Flyway migration is the source of truth for the schema

[`services/restaurant-service/src/main/resources/db/migration/V1__init.sql`](../../services/restaurant-service/src/main/resources/db/migration/V1__init.sql) creates `restaurants` (with the partial index `idx_restaurants_city ... WHERE active = TRUE`), `menu_items` (with the real DB FK `restaurant_id REFERENCES restaurants(id) ON DELETE CASCADE`), and `outbox_events` (with the partial index `idx_outbox_unsent ON outbox_events(created_at) WHERE sent_at IS NULL`). The `payload` column is `BYTEA` (not `@Lob`) — see [restaurant-service.md § 3.7](../services/restaurant-service.md#37-domainoutboxevent) for the bring-up bug this avoids.

> **Cross-link.** The 14-file walkthrough of restaurant-service in [restaurant-service.md § 3](../services/restaurant-service.md#3-file-by-file-walkthrough) is the deep reference. This section is the use-case trace; that file is the "what does every class do" tour.

---

## 5. Step 3: The outbox poller publishes to Kafka

A second or so after the transaction commits, Spring's scheduler fires `Outbox.publishPending()`. In [`Outbox.java`](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/service/Outbox.java):

```java
@Scheduled(fixedDelay = 1000L)
@Transactional
public void publishPending() {
    List<OutboxEvent> pending = outboxRepo.findUnsent();
    for (OutboxEvent e : pending) {
        try {
            SpecificRecord decoded = AvroBytes.decode(e.getEventType(), e.getPayload());
            ProducerRecord<String, SpecificRecord> record =
                    new ProducerRecord<>(e.getTopic(), e.getAggregateId().toString(), decoded);
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);  // <-- synchronous
            e.setSentAt(Instant.now());
            outboxRepo.save(e);
        } catch (Exception ex) {
            // log and let the next poll retry
        }
    }
}
```

Five things to know about this code:

1. **The schedule is `@Scheduled(fixedDelay = 1000L)`** — fires every 1 second between the end of one run and the start of the next. This is **hard-coded** in this service, unlike `order-service` and `payment-service` which use `fixedDelayString = "${samato.outbox.poll-ms:500}"`. The other two poll every 500 ms; restaurant-service polls every 1 s. (See the [outbox poller note in the glossary](../00-glossary.md#outbox-poller) and [01-architecture-guide.md § 4](../01-architecture-guide.md#the-outbox-pattern-3-pollers).)
2. **`findUnsent()` returns up to 100 rows** in `created_at ASC` order. The JPQL uses a Hibernate-specific `LIMIT 100` clause. The query hits the partial index `idx_outbox_unsent ON outbox_events(created_at) WHERE sent_at IS NULL` — see the migration SQL above and [`OutboxEventRepository`](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/domain/OutboxEventRepository.java).
3. **`AvroBytes.decode(eventType, payload)`** deserializes the bytes back to a `SpecificRecord`. The `eventType` column stores the FQN of the Avro class (`com.samato.events.RestaurantCreatedEvent`); `AvroBytes.REGISTRY` (a private `Map<String, Class<? extends SpecificRecord>>`) maps that string to the generated Java class. See [`AvroBytes.java`](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/service/AvroBytes.java).
4. **`kafkaTemplate.send(record).get(5, SECONDS)`** is the **synchronous** send pattern. The `.get()` blocks the poller thread until the broker acks (or 5 seconds elapse). The Outbox class javadoc explains the trade-off: `.whenComplete(...)` would be more idiomatic, but the poller wants to know whether the send succeeded **before** marking the row as sent, so `.get()` is correct here.
5. **On success, `sent_at = now()`** is written in the same transaction as the rest of the poller's work (`@Transactional` on `publishPending`). On failure, the catch block logs at WARN and **leaves the row unsent** — the next poll retries. This means a continuously-failing event will be retried every second forever; see the [anomaly in § 9](#9-what-can-go-wrong) for the production hardening (poison-message table after N failures).

### 5.1 The wire format: this is the **real Avro** path

The `kafkaTemplate` field is `KafkaTemplate<String, org.apache.avro.specific.SpecificRecord>` — the bean from `shared-kafka`'s `KafkaProducerConfig.kafkaTemplate(...)` (see [shared-and-kafka.md § 4.1](../services/shared-and-kafka.md#41-sharedkafkasrcmainjavacomsamtosharedkafkaconfigkafkaproducerconfigjava)). That bean is wired with:

- `KafkaAvroSerializer` as the value serializer.
- Confluent Schema Registry at `spring.kafka.properties.schema.registry.url` (default `http://localhost:8085`; in compose, `http://schema-registry:8081`).
- `KafkaMdcProducerInterceptor` to propagate `traceId`, `spanId`, `correlationId` as Kafka record headers.
- Producer config `acks=all` and `enable.idempotence=true` from `application.yml` — no producer-side duplicates.

The wire format is **Confluent Avro**: `0x00 + 4-byte schema id + Avro binary payload`. This is the only place in the monorepo where this format is used end-to-end (producer to consumer). See [§ 11 The "Avro vs byte[]" anomaly](#11-the-avro-vs-byte-anomaly-callout).

The `ProducerRecord` carries:

- **Topic** = `samato.restaurant.created` (or `samato.restaurant.updated` for updates/deactivations). Constants in `Outbox`: `TOPIC_RESTAURANT_CREATED`, `TOPIC_RESTAURANT_UPDATED`.
- **Key** = the `aggregateId.toString()` — i.e. the restaurant UUID. **This is critical for ordering**: two events for the same restaurant land on the same partition and are processed in order by the consumer. See the [Kafka key glossary entry](../00-glossary.md#key-kafka).
- **Value** = the deserialized `SpecificRecord` (e.g. a `RestaurantCreatedEvent`).

### 5.2 The event payloads

[`RestaurantCreatedEvent.avsc`](../../shared-kafka/src/main/avro/RestaurantCreatedEvent.avsc):

```json
{
  "namespace": "com.samato.events",
  "type": "record",
  "name": "RestaurantCreatedEvent",
  "fields": [
    {"name": "eventId",     "type": "string"},
    {"name": "occurredAt",  "type": "long",   "doc": "Epoch millis"},
    {"name": "restaurantId","type": "string"},
    {"name": "ownerId",     "type": "string"},
    {"name": "name",        "type": "string"},
    {"name": "description", "type": ["null", "string"], "default": null},
    {"name": "cuisine",     "type": "string"},
    {"name": "address",     "type": "string"},
    {"name": "city",        "type": "string"},
    {"name": "latitude",    "type": "double"},
    {"name": "longitude",   "type": "double"}
  ]
}
```

[`RestaurantUpdatedEvent.avsc`](../../shared-kafka/src/main/avro/RestaurantUpdatedEvent.avsc) is identical **except** it does **not** carry `ownerId` and does **not** carry an `active` field. The `Outbox.enqueueRestaurantUpdated` builder confirms this — it sets `restaurantId`, `name`, `description`, `cuisine`, `address`, `city`, `lat`, `lon` and that's it. See the [anomaly in § 9](#9-what-can-go-wrong) for the implication.

The Java classes (`com.samato.events.RestaurantCreatedEvent`, `com.samato.events.RestaurantUpdatedEvent`) are generated by the `avro-maven-plugin` in `shared-kafka/pom.xml` at build time. They live in `shared-kafka/target/generated-sources/avro/com/samato/events/` after `mvn -pl shared-kafka install`.

> **Why the package is `com.samato.events`, not `com.samato.sharedkafka.events`.** The Avro schema's `namespace` directive controls the generated class's package. The `shared-kafka` team chose to namespace the events under `com.samato.events` for "the events are domain events, not infrastructure." The side effect (documented in the [glossary under Avro](../00-glossary.md#avro) and [search-service.md § 7](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes)) is that the `DomainEvent` marker interface in `com.samato.sharedkafka.events` is **not implemented** by any Avro-generated class — see the [shared-and-kafka.md anomalies](../services/shared-and-kafka.md#anomalies-and-how-to-interpret-them).

---

## 6. Step 4: search-service consumes the event

`search-service` has **the only `@KafkaListener` in the entire monorepo** (see [`call-graph.json` → `kafkaListeners[]`](../inventory/call-graph.json#kafkalisteners) — one entry, for `search-service`). It consumes both restaurant topics and projects each event into OpenSearch.

### 6.1 The listener

In [`RestaurantEventListener.java`](../../services/search-service/src/main/java/com/samato/searchservice/projection/RestaurantEventListener.java):

```java
@KafkaListener(
        topics = {
                "samato.restaurant.created",
                "samato.restaurant.updated"
        },
        groupId = "samato-search-service"
)
public void onEvent(ConsumerRecord<String, SpecificRecord> record, Acknowledgment ack) {
    try (var ignored = MdcContext.fromKafka(record)) {
        SpecificRecord ev = record.value();
        Object eventIdObj = ((GenericRecord) ev).get("eventId");
        String eventId = eventIdObj == null ? "?" : eventIdObj.toString();
        log.info("Projecting event id={} type={} key={}", eventId, record.topic(), record.key());
        try {
            projector.apply(ev);
            ack.acknowledge();   // <-- only ack after success
        } catch (Exception ex) {
            log.error("Projection failed for event {}: {}", eventId, ex.getMessage());
            throw ex;             // <-- re-throw so the consumer redelivers
        }
    }
}
```

Four things to know about this code:

1. **One listener, two topics.** The `@KafkaListener(topics = {...})` accepts a string array. Spring Kafka's container factory assigns the same `onEvent` method to both topics. The listener can't tell them apart except via `record.topic()`.
2. **`groupId = "samato-search-service"`** — the consumer group id. `application.yml` has the same string as `spring.kafka.consumer.group-id`; the `@KafkaListener` annotation overrides it if they differ, but the team keeps them in sync. The `kafkaTopicCrossReference` in [call-graph.json](../inventory/call-graph.json) and [01-architecture-guide.md § 4](../01-architecture-guide.md#4-the-message-story) both confirm: there is exactly **one** consumer in the entire monorepo.
3. **`MdcContext.fromKafka(record)`** opens a try-with-resources scope that copies `correlationId`, `traceId`, `spanId` from the Kafka record headers into SLF4J's [MDC](../00-glossary.md#mdc). Every log line emitted by the projector (or by any nested code) carries the same correlation id as Bob's original write. This is what makes a single request traceable through the consumer thread. `MdcContext` is from the `shared-kafka` module — see [shared-and-kafka.md](../services/shared-and-kafka.md#mcccontext).
4. **`ack.acknowledge()` only after success.** The container is configured with `ack-mode: manual_immediate` (in `application.yml`); the framework only commits the offset when we call this method. If the projector throws, we re-throw, the container does **not** commit, and the record is redelivered on the next poll. This is the at-least-once delivery pattern — see [glossary](../00-glossary.md#at-least-once-delivery) and [search-service.md § 6.1](../services/search-service.md#61-topics-consumed).

> **The `((GenericRecord) ev).get("eventId")` cast.** This is the workaround for the `DomainEvent` interface anomaly. The Avro-generated class has a real `getEventId()` accessor, but the listener works against `SpecificRecord` (the superclass) and uses the `GenericRecord.get(String)` overload to read by field name. The class javadoc explains: "Avro-generated records expose `getEventId()` because every event schema defines an `eventId` field. We read the value via the schema, not a marker interface, because the generated code doesn't implement ours." This is the same pattern as the `MdcContext` class — see the [shared-and-kafka.md anomalies](../services/shared-and-kafka.md#anomalies-and-how-to-interpret-them).

### 6.2 The deserializer

The `KafkaConsumerConfig.kafkaListenerContainerFactory(...)` bean from `shared-kafka` (see [shared-and-kafka.md § 4.2](../services/shared-and-kafka.md#42-sharedkafkasrcmainjavacomsamtosharedkafkaconfigkafkaconsumerconfigjava)) is wired with:

- `KafkaAvroDeserializer` for values.
- `specific.avro.reader=true` (set in `search-service`'s `application.yml`) — this tells the deserializer to produce `SpecificRecord` instances (with typed accessors) rather than `GenericRecord`. Without this, `ev instanceof RestaurantCreatedEvent` would not match.
- `enable-auto-commit=false` — the listener handles all acks.
- `auto-offset-reset=earliest` — a brand-new group with no committed offsets starts from the beginning of the topic.
- `max-poll-records=50` — how many records a single poll() call may return.
- `listener.ack-mode=manual_immediate` — see above.

### 6.3 The projector writes to OpenSearch

In [`RestaurantProjector.java`](../../services/search-service/src/main/java/com/samato/searchservice/projection/RestaurantProjector.java):

```java
public void apply(SpecificRecord ev) {
    Map<String, Object> doc = new HashMap<>();
    if (ev instanceof RestaurantCreatedEvent c) {
        doc.put("id", c.getRestaurantId().toString());
        doc.put("name", c.getName());
        doc.put("description", c.getDescription());
        doc.put("cuisine", c.getCuisine());
        doc.put("city", c.getCity());
        doc.put("address", c.getAddress());
        doc.put("location", Map.of("lat", c.getLatitude(), "lon", c.getLongitude()));
        doc.put("createdAt", c.getOccurredAt());
        doc.put("updatedAt", c.getOccurredAt());
    } else if (ev instanceof RestaurantUpdatedEvent u) {
        // ... same shape, only updatedAt is set ...
    } else {
        log.warn("Unknown event type, skipping: {}", ev.getClass().getName());
        return;
    }

    // Upsert: _id = restaurantId. Re-delivery is a no-op.
    IndexRequest req = new IndexRequest(INDEX)
            .id(doc.get("id").toString())
            .source(mapper.writeValueAsString(doc), XContentType.JSON);
    IndexResponse resp = osClient.index(req, RequestOptions.DEFAULT);
}
```

The `INDEX` constant is `public static final String INDEX = "restaurants"` and is **duplicated** in `OpenSearchIndexInitializer` — see [anomaly in § 9](#9-what-can-go-wrong). The `id` field of the document is set to the restaurant's UUID string, and is also used as the OpenSearch `_id`. This is the **idempotency key**: re-delivery of the same event re-uses the same `_id`, so OpenSearch's index operation is an upsert with identical content. No state change. See the [idempotent consumer glossary entry](../00-glossary.md#idempotent-consumer).

The `location` field is a `Map.of("lat", ..., "lon", ...)` — OpenSearch's wire format for a `geo_point`. The index mapping (created at startup, see below) declares the field type as `geo_point`, and the projection writes the matching JSON shape. The geo-distance query in [§ 7](#7-step-5-alice-searches) uses this.

The document is JSON-serialized via Jackson and sent to OpenSearch as a `String` source with `XContentType.JSON`. The `osClient.index(...)` call returns an `IndexResponse`; the version is logged at DEBUG.

### 6.4 The OpenSearch index mapping

In [`OpenSearchIndexInitializer.java`](../../services/search-service/src/main/java/com/samato/searchservice/config/OpenSearchIndexInitializer.java), the `ensureIndex()` method runs on `ApplicationReadyEvent` and creates the index if it doesn't exist:

```json
{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "id":          { "type": "keyword" },
      "name":        { "type": "text", "fields": { "raw": { "type": "keyword" } } },
      "description": { "type": "text" },
      "cuisine":     { "type": "keyword" },
      "city":        { "type": "keyword" },
      "address":     { "type": "text" },
      "location":    { "type": "geo_point" },
      "createdAt":   { "type": "date", "format": "epoch_millis" },
      "updatedAt":   { "type": "date", "format": "epoch_millis" }
    }
  }
}
```

Why this matters:

- **`name` is `text` with a `.raw` keyword sub-field** — full-text search across `name` is exact-ish; the `.raw` sub-field enables exact-match aggregations or sorts.
- **`cuisine` and `city` are `keyword`** — no analysis, exact match. Perfect for filter queries (`term: { cuisine: "Chinese" }`).
- **`location` is `geo_point`** — required for the `geo_distance` query. See the [geo_point glossary entry](../00-glossary.md#opensearch) and [OpenSearch glossary](../00-glossary.md#opensearch).
- **`createdAt` / `updatedAt` are `epoch_millis`** — Java's `System.currentTimeMillis()` output.

The `INDEX` constant `"restaurants"` is **duplicated** between this initializer (`public static final String INDEX = "restaurants"`) and `RestaurantProjector` (`public static final String INDEX = "restaurants"`). The search-service doc flags this in [§ 7 gotchas](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes) and [the doc-team anomaly list](#anomalies-called-out).

> **Idempotent index creation.** The initializer checks `client.indices().exists(...)` first and returns if the index is already there. It is safe to run on every startup. The whole `ensureIndex` body is wrapped in `try/catch` so a flaky OpenSearch doesn't crash the service; the index will be created (or re-tried) on the next startup.

### 6.5 The MDC propagation in 10 seconds

When Bob's `POST /api/restaurants` lands at the gateway, the `CorrelationIdWebFilter` writes `X-Correlation-Id` to the MDC. That id is in the request headers, and the `KafkaMdcProducerInterceptor` (in `shared-kafka`) writes it as a Kafka record header. When `search-service`'s listener thread polls the record, `MdcContext.fromKafka(record)` reads the header and re-stuffs it into the consumer's MDC. The result: Alice can `grep X-Correlation-Id=9b2e4f7a` in any log file and see Bob's write, the Kafka publish, and the OpenSearch projection — one id, three services, end to end. This is the "correlation id" pattern that ties the system together. See the [correlation id glossary entry](../00-glossary.md#correlation-id).

---

## 7. Step 5: Alice searches

Bob's restaurant is now in the OpenSearch index. Alice opens the app, types "spicy noodles near MG Road", and gets Bob's restaurant in the results. The request:

```http
GET /api/search/restaurants?q=spicy%20noodles&lat=12.97&lon=77.59&radiusKm=5&size=20 HTTP/1.1
Host: localhost:8080
Authorization: Bearer <CUSTOMER_JWT>
X-Correlation-Id: ...
```

### 7.1 The gateway routes to search-service

`GatewayRoutesConfig.routes` matches `/api/search/**` and forwards to `lb://SAMATO-SEARCH-SERVICE`. The `JwtAuthFilter` has validated Alice's token; the `CorrelationIdWebFilter` has stamped the id.

### 7.2 `SearchController.search` builds the OpenSearch query

In [`SearchController.java`](../../services/search-service/src/main/java/com/samato/searchservice/web/SearchController.java):

```java
@GetMapping("/restaurants")
@PreAuthorize("isAuthenticated()")
public SearchResponse search(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String cuisine,
        @RequestParam(required = false) String city,
        @RequestParam(required = false) Double lat,
        @RequestParam(required = false) Double lon,
        @RequestParam(required = false) Double radiusKm,
        @RequestParam(defaultValue = "20") int size
) throws Exception {
    BoolQueryBuilder bool = QueryBuilders.boolQuery();
    if (q != null && !q.isBlank()) {
        bool.must(QueryBuilders.multiMatchQuery(q, "name", "description"));
    }
    if (cuisine != null && !cuisine.isBlank()) {
        bool.filter(QueryBuilders.termQuery("cuisine", cuisine));
    }
    if (city != null && !city.isBlank()) {
        bool.filter(QueryBuilders.termQuery("city", city));
    }
    if (lat != null && lon != null && radiusKm != null) {
        bool.filter(QueryBuilders.geoDistanceQuery("location")
                .point(lat, lon)
                .distance(radiusKm + "km"));
    }

    SearchSourceBuilder source = new SearchSourceBuilder()
            .query(bool)
            .size(size);

    SearchRequest req = new SearchRequest(RestaurantProjector.INDEX).source(source);
    SearchResponse osResp = osClient.search(req, RequestOptions.DEFAULT);

    List<Map<String, Object>> hits = new ArrayList<>();
    for (SearchHit h : osResp.getHits().getHits()) {
        Map<String, Object> doc = new HashMap<>(h.getSourceAsMap());
        doc.put("_score", h.getScore());
        hits.add(doc);
    }
    return new SearchResponse(osResp.getHits().getTotalHits().value, hits);
}
```

The four clauses in plain English:

- **`q` ("spicy noodles")** — a `multi_match` against `name` and `description`. These are `text` fields, so the analyzer tokenizes and stems. A fuzzy match for "noodle" would hit "noodles".
- **`cuisine` (e.g. "Chinese")** — a `term` filter on the keyword field. Exact match only.
- **`city` (e.g. "Bangalore")** — same pattern, exact match.
- **`lat`/`lon`/`radiusKm`** — a `geo_distance` filter on the `location` geo_point. "Within 5 km of (12.97, 77.59)."

The `must` (text) and `filter` (facets) split is intentional: filters are cacheable in OpenSearch (no scoring, just yes/no) and faster than `must` for facets. See the [search-service.md § 4.1](../services/search-service.md#41-get-apisearchrestaurants) for the full endpoint spec.

The controller returns a `SearchResponse` record:

```java
public record SearchResponse(long total, List<Map<String, Object>> hits) {}
```

The `hits` list contains one `Map` per result, with keys from the projected document plus a synthetic `_score` for relevance. Total is `hits.total.value` from OpenSearch.

### 7.3 No DB transaction

Notice that there is no `@Transactional` on this method. The endpoint is read-only, idempotent, and doesn't need a database transaction. The only "store" it touches is OpenSearch, which doesn't have ACID transactions in the relational sense — every search is just a query. This is a deliberate CQRS design: the read model is **simpler** than the write model because the consistency requirements are looser.

> **Idempotent read.** Two customers searching at the exact same instant get the same result. There is no DB transaction, no isolation level to think about, and the OpenSearch query is side-effect-free.

### 7.4 Example response

```json
{
  "total": 1,
  "hits": [
    {
      "id": "9b8e2f3a-4c1d-4e9f-a7c2-1b3d5f7e9a1c",
      "name": "Spicy Noodle House",
      "description": "Hand-pulled noodles and Sichuan peppercorns",
      "cuisine": "Chinese",
      "city": "Bangalore",
      "address": "12 MG Road, Indiranagar",
      "location": { "lat": 12.9716, "lon": 77.5946 },
      "createdAt": 1718000000000,
      "updatedAt": 1718000000000,
      "_score": 5.13
    }
  ]
}
```

The `_score` is OpenSearch's relevance score for the `must` clause (it only matters when `q` is set). The other fields are the projected document verbatim.

> **What the customer does with this.** The mobile app renders a list of cards (name + cuisine + distance, derived from the location field). Tapping a card calls the next step: [§ 8 Step 6: Alice clicks on Bob's restaurant](#8-step-6-alice-clicks-on-bob-s-restaurant).

---

## 8. Step 6: Alice clicks on Bob's restaurant

Alice taps Bob's card. The app calls:

```http
GET /api/restaurants/9b8e2f3a-4c1d-4e9f-a7c2-1b3d5f7e9a1c HTTP/1.1
Host: localhost:8080
Authorization: Bearer <CUSTOMER_JWT>
```

This goes to **`restaurant-service`**, not `search-service`. The reason is the **separation of concerns** in CQRS:

- **`search-service`** owns the **discovery** index — "what restaurants match this query?". It's a denormalized, eventually consistent view. Optimized for finding things, not for showing their current state.
- **`restaurant-service`** owns the **source of truth** — the actual `Restaurant` row, the menu, the soft-delete flag. When Alice clicks through, she wants the latest state, not a projection that might be a few seconds stale.

The `RestaurantController.get(UUID id)` handler:

```java
@GetMapping("/{id}")
@PreAuthorize("isAuthenticated()")
public Restaurant get(@PathVariable UUID id) {
    return service.get(id);
}
```

Which calls `RestaurantService.get`, which is annotated `@Cacheable(value = "restaurants", key = "#id")` and `@Transactional(readOnly = true)`:

```java
@Cacheable(value = CACHE, key = "#id")
@Transactional(readOnly = true)
public Restaurant get(UUID id) {
    return restaurants.findById(id)
            .orElseThrow(() -> new DomainException("RESTAURANT_NOT_FOUND", "Restaurant not found", 404));
}
```

This is the [cache-aside pattern](../00-glossary.md#cache-aside):

1. **Check Redis** for the key `restaurants::<id>`. If hit, return the cached `Restaurant`.
2. **On miss**, query Postgres. Build the entity, cache it (TTL 5 min, configured in `CacheConfig#cacheManager`), return it.

The cache key shape is `restaurants::<id>` because `prefixCacheNameWith("samato:cache:")` is set in `CacheConfig` and `StringRedisSerializer` is used for keys. See [`CacheConfig.java`](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/config/CacheConfig.java) for the bean definition.

> **Why not serve from OpenSearch?** Because the OpenSearch projection might be a few seconds stale (the outbox poller runs every 1 s + Kafka latency + projection time). For the "show me the details" view, the customer wants the live state. The cache, on the other hand, is invalidated by the same write that updates Postgres — so it's at most milliseconds behind. See the [eventual-consistency note in § 9](#9-what-can-go-wrong).

### 8.1 Then the menu

Once Alice has the restaurant, the app likely calls `GET /api/restaurants/{id}/menu` to get the list of `MenuItem`s. This is also a `restaurant-service` endpoint (see [`RestaurantController.getMenu`](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/web/RestaurantController.java)). Menu items are **not** in the OpenSearch index — they're served from Postgres directly (the menu lookup is a small, indexed query against `menu_items WHERE restaurant_id = ?`). See the [anomaly in § 9](#9-what-can-go-wrong) for the implication: menu changes don't propagate to search.

---

## 9. What can go wrong

The "happy path" above is the story you tell in an interview. The "what can go wrong" list is the story you tell when something breaks at 2 AM. These are the failure modes the source code and the per-service docs surface:

### 9.1 Bob's restaurant is created but the outbox poller hasn't run yet (eventual consistency)

**Symptom.** Alice searches immediately after Bob creates the restaurant. She doesn't see it.

**Why.** The Postgres transaction commits instantly. The outbox poller fires every 1 second (`@Scheduled(fixedDelay = 1000L)`). Between commit and poll, the row is unsent. Then the poller decodes, sends via Kafka (synchronous `.get(5, SECONDS)`), and the consumer in `search-service` projects into OpenSearch.

**End-to-end delay budget.** ~1 s (poller interval) + ~tens of ms (Kafka) + ~tens of ms (projector + OpenSearch index). Realistically 1–2 seconds. Worst case under load: a few seconds.

**Workaround (not implemented today).** A "I've just created this" UI state on the client side. The eventual-consistency contract is a [documented design choice](../00-glossary.md#idempotent-consumer), not a bug.

### 9.2 Kafka is down — outbox row stays unsent

**Symptom.** Bob's restaurant is in Postgres but the outbox poller logs `Outbox publish failed` repeatedly. OpenSearch doesn't get updated.

**Why.** The `.get(5, SECONDS)` in `Outbox.publishPending` throws on send failure (or timeout). The catch block logs and **leaves the row unsent**. The next poll retries. The row in `outbox_events` is the durable "intent to publish" — Postgres is up, so the row is safe.

**When Kafka comes back up**, the next poll finds the unsent row, sends it, and marks it sent. Zero data loss. This is the **point** of the outbox pattern: the write side doesn't depend on Kafka being available.

**Anomaly (no exponential backoff).** The retry interval is fixed at 1 s. A continuously-failing event will be retried every second forever, hammering Kafka when it recovers. The Outbox class javadoc calls this out as a real-system improvement (a poison-message table after N failures, with exponential backoff). See [restaurant-service.md § 3.13](../services/restaurant-service.md#313-comsamatorestaurantserviceserviceoutbox) and the [Outbox class source](../../services/restaurant-service/src/main/java/com/samato/restaurantservice/service/Outbox.java).

### 9.3 search-service is down — search returns 500, but reads still work

**Symptom.** Alice's `GET /api/search/restaurants` returns 500 or hangs.

**Why.** The Spring Cloud Gateway route forwards to `SAMATO-SEARCH-SERVICE`. If the service is down, the LB picks nothing, the gateway times out, returns 502/504 to the client.

**What still works.** Alice can still `GET /api/restaurants/{id}` and `GET /api/restaurants/{id}/menu` — those go to `restaurant-service`, which is independent. The discovery index is down, but the source-of-truth reads still serve. **The two services are isolated by design.**

**Conversely:** if `restaurant-service` is down but `search-service` is up, Alice can still search (she sees the existing index, which might be a bit stale). The catch is that the "click into" step (which calls `restaurant-service`) will fail.

### 9.4 OpenSearch is down — search-service returns 500, but the write side still works

**Symptom.** Alice's search returns 500. Bob can still create restaurants (they go to Postgres + outbox in `restaurant-service`).

**Why.** `OpenSearchConfig#openSearchClient` is a `RestHighLevelClient` bean. If OpenSearch is unreachable, the call throws `ConnectException` or similar. `SearchController.search` propagates the exception; Spring's default error handling returns 500.

**What happens to events in flight.** The `RestaurantEventListener` in `search-service` re-throws on projection failure. The container does not commit the offset. On the next poll, the same record is delivered again. The poller waits for OpenSearch.

**This is a stack-rank failure**: when OpenSearch comes back, the `OpenSearchIndexInitializer` will (try to) create the index, and the listener will catch up on the backlog. The downside is that without a DLQ or backoff, a single bad event can block the partition. The listener code's comment flags this: "For poison messages, we'd send to a DLQ topic after N retries. Out of scope for the bible." See [search-service.md § 7](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes).

### 9.5 Bob updates the restaurant's address

**Symptom.** Bob calls `PUT /api/restaurants/{id}`. The new address shows up in `restaurant-service`'s response. A few seconds later, search results reflect the new address.

**What happens.** `RestaurantService.update` is `@Transactional`. It does an `UPDATE restaurants SET ...` and inserts an outbox row with `topic = samato.restaurant.updated` and `eventType = com.samato.events.RestaurantUpdatedEvent`. The outbox poller publishes within 1 s. The consumer in `search-service` projects the same OpenSearch `_id` (the restaurant's UUID) with the new fields.

**Important.** `RestaurantProjector.apply` treats `RestaurantUpdatedEvent` and `RestaurantCreatedEvent` symmetrically — the document `_id` is the same, the document is overwritten. The update is idempotent: re-delivery of the same event re-applies the same content.

**Side effect.** The Redis cache for this `id` is evicted (`@CacheEvict(key = "#id")` on `update`). The next `GET /api/restaurants/{id}` repopulates from Postgres.

### 9.6 Bob DELETES the restaurant — soft delete, and the event is `samato.restaurant.updated`, not `.deleted`

**Symptom.** Bob calls `DELETE /api/restaurants/{id}`. The endpoint returns 204 No Content. Alice no longer sees the restaurant in search results (eventually).

**What happens.** `RestaurantService.deactivate` sets `active = false` in Postgres (soft delete — the row is not removed). It inserts an outbox row with **`topic = samato.restaurant.updated`** (the **same** topic as a real update) and `eventType = RestaurantUpdatedEvent`. The consumer projects a new document with the updated `name`, `description`, etc. — but **`active` is not in the event**, so the projection doesn't know the restaurant was deactivated.

**The implication.** The OpenSearch document still has all the fields populated; the soft-delete is invisible to `search-service`. The browse endpoint in `restaurant-service` filters by `active = true` (`findByCityIgnoreCaseAndActiveTrue`), so it doesn't show deactivated restaurants. But `search-service`'s search will still return the restaurant.

**Inventory note.** The inventory search found this gap; the [restaurant-service.md § 7 anomalies list](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes) item 8 calls it out: "If a future consumer needs to react to a deactivation specifically, it will have to compare the new `active` to the previous one (or fetch the current state from the service). For now the only consumer (`search-service`) doesn't differentiate update from deactivate." A proper fix is a new `RestaurantDeactivatedEvent` topic or adding an `active` field to `RestaurantUpdatedEvent` (and re-projection logic that filters deactivated restaurants from search results).

### 9.7 Two customers search at the exact same instant

**Symptom.** Both customers get the same result.

**Why this is not a problem.** The search endpoint is read-only. There is no DB transaction, no isolation level, no race condition. The OpenSearch query is side-effect-free. Both requests read the same index state, get the same hits, return the same JSON. Idempotent by construction.

### 9.8 The geo query returns an empty result

**Symptom.** Alice searches with `lat=12.97&lon=77.59&radiusKm=0.001` (1 meter) — too tight to match anything.

**Response.** HTTP 200 with `{ "total": 0, "hits": [] }`. **Not 404.** 404 means "the resource doesn't exist"; the search resource exists, the result set is just empty. The OpenSearch client returns 200 with an empty `hits` array; the controller propagates this. The GlobalExceptionHandler is not involved (no exception is thrown).

### 9.9 OpenSearch query takes too long

**Symptom.** Alice's request hangs for 30 s and then times out with a 500.

**Why.** The OpenSearch client has no timeout configured (the `application.yml` only sets `samato.opensearch.host` and `port`, no `connection-timeout` or `socket-timeout`). A slow OpenSearch (under load, large result set, complex query) will block the thread.

**Anomaly.** Resilience4j is on the classpath (`resilience4j-spring-boot3` in `restaurant-service`'s `pom.xml`), but **no `@CircuitBreaker` / `@Retry` / `@TimeLimiter` annotations are on the search call sites**. The [`call-graph.json` → `resilience4j[]`](../inventory/call-graph.json#resilience4j) for `restaurant-service` says: "Library is on the classpath; policies appear not yet wired." Same for `search-service` — there's no Resilience4j block in its `application.yml`.

**A real fix would be** a `@TimeLimiter` on `osClient.search(...)` with a 3 s timeout, and a `@CircuitBreaker` that opens after N failures. The pattern is in `user-service`'s `application.yml` (see [user-service.md § 3.16](../services/user-service.md#316-applicationyml-application-configuration-walkthrough) for the Resilience4j block), but the annotations are intentionally absent today (see the [01-architecture-guide § 7 anomaly note](../01-architecture-guide.md#7-cross-cutting-concerns) for the reasoning).

### 9.10 Two concurrent PUTs to the same restaurant

**Symptom.** Two `PUT /api/restaurants/{id}` requests fire within the same millisecond. The first succeeds (200, updated `Restaurant`). The second fails with 500.

**Why.** `Restaurant` has `@Version private long version`. Hibernate bumps the version on every update. If the in-memory copy of the second request is stale, the second `UPDATE` fails with `OptimisticLockException`. The transaction rolls back. The exception is not caught by `GlobalExceptionHandler` — Spring's default error path returns 500.

**The bigger gap.** There is no `If-Match` header support. The HTTP spec's optimistic concurrency mechanism (RFC 7232) would let the client say "I have version 3, only update if the server is still at 3" and the server would return 409 Conflict (or 412 Precondition Failed) on a mismatch. This service does not implement that. The [restaurant-service.md § 7 anomalies item 9](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes) flags it: "If two concurrent updates hit, one of them gets an unhandled `OptimisticLockException` and a 500. This is a real-but-unaddressed corner case in the current implementation."

### 9.11 `addMenuItem` does not publish an event

**Symptom.** Bob adds a "Kung Pao Chicken" menu item. It's visible in `GET /api/restaurants/{id}/menu` (served from `restaurant-service`). It's **not** visible in any search result, ever.

**Why.** `RestaurantService.addMenuItem` is `@Transactional` but it does **not** call `outbox.enqueueRestaurant*`. No event is published. The [restaurant-service.md § 7 anomalies item 4](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes) flags this as a known gap: "The menu is therefore not reflected in `search-service` until a restaurant-level update is triggered. If you need search to reflect menu changes, you would add a `MenuItemAddedEvent` and a corresponding Avro schema." A future change would add a new `.avsc`, a new `Outbox.enqueueMenuItemAdded`, and a third `else if` branch in `RestaurantProjector`.

### 9.12 The INDEX constant is duplicated

**Symptom.** A developer changes `RestaurantProjector.INDEX` to `"restaurants_v2"` to do a re-index. The projector writes to `restaurants_v2` but the initializer created `restaurants` and the `SearchController` reads from `restaurants`. Search returns empty.

**Why.** `OpenSearchIndexInitializer.INDEX` and `RestaurantProjector.INDEX` are both `public static final String INDEX = "restaurants"` but are **separate** constants. The `SearchController` references `RestaurantProjector.INDEX` (so a rename in the projector is half the fix), but the initializer's constant is independent. The [search-service.md § 7 gotcha list](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes) calls this out: "The safest move is to delete the initializer's local `INDEX` and import `RestaurantProjector.INDEX` instead." This is a doc-team anomaly.

### 9.13 The `spring.kafka.consumer.*` block in restaurant-service's `application.yml` is inert

**Symptom.** A new developer sees `spring.kafka.consumer.group-id: samato-restaurant-service` in `restaurant-service`'s YAML and assumes there's a consumer here. There isn't.

**Why.** The block is configured but unused. `restaurant-service` is a publisher only; there are no `@KafkaListener` methods in this service. The block is "scaffolding for a future consumer." Same for the `spring.kafka.consumer.properties.specific.avro.reader: true` key — useful if a consumer is ever added, but no class subscribes today. The [restaurant-service.md § 7 anomalies item 1](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes) flags this and the [01-architecture-guide § 7 anomaly note](../01-architecture-guide.md#7-cross-cutting-concerns) explains it more broadly.

### 9.14 `RestaurantUpdatedEvent` lacks `ownerId` and `active`

**Symptom.** A future consumer wants to filter search results by owner or by deactivation status. It can't.

**Why.** The Avro schema in [`RestaurantUpdatedEvent.avsc`](../../shared-kafka/src/main/avro/RestaurantUpdatedEvent.avsc) has 10 fields, none of them `ownerId` or `active`. The class javadoc on the schema says it carries "name, description, address, etc" — but it doesn't carry the two fields that distinguish an update from a deactivation.

**Inventory note.** The [restaurant-service.md § 7 anomalies item 8](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes) flags it: "If a future consumer needs to react to a deactivation specifically, it will have to compare the new `active` to the previous one (or fetch the current state from the service). For now the only consumer (`search-service`) doesn't differentiate update from deactivate." The fix is a schema evolution: add `active: ["null", "boolean"] = null` to the schema, regenerate the class, update the producer builder, and add a re-projection step (or default `active = true` for legacy events).

---

## 10. The data trail

A summary of what is written to which store at each step. This is the single table to point at when explaining CQRS to a beginner.

| Step | What is written | Postgres (`restaurant_service` DB) | Kafka topic | OpenSearch index | Redis cache |
|---|---|---|---|---|---|
| 3 — Bob registers | `users` row (BCrypt hash), `user_roles` row | (auth DB, not shown) | — | — | — |
| 3 — Profile | `restaurant_owner_profiles` row | (user_service DB, not shown) | — | — | — |
| 4 — Create restaurant | `restaurants` row + `outbox_events` row, **same transaction** | `restaurants` INSERT, `outbox_events` INSERT (topic=`samato.restaurant.created`, payload=Avro bytes) | — | — | — |
| 4 — Add menu item | `menu_items` row, **no event** | `menu_items` INSERT | — | — | — |
| 5 — Poller fires (1 s) | `outbox_events.sent_at` updated | `UPDATE outbox_events SET sent_at=now()` | `samato.restaurant.created` (key=restaurantId) | — | — |
| 6 — search-service consumes | OpenSearch document | — | — | `restaurants` index, `_id=restaurantId`, source=JSON projection | — |
| 7 — Alice searches | (none) | — | — | (read-only) | — |
| 8 — Alice clicks | (cache miss path) `restaurants` row read | (read) | — | — | `restaurants::<id>` populated (TTL 5 min) |
| 9.5 — Bob updates | `restaurants` row update + `outbox_events` row | `restaurants` UPDATE, `outbox_events` INSERT (topic=`samato.restaurant.updated`) | — | — | `restaurants::<id>` evicted |
| 9.5 — Update projects | OpenSearch document overwritten (same `_id`) | — | `samato.restaurant.updated` (key=restaurantId) | `restaurants` upsert | — |
| 9.6 — Bob deactivates | `restaurants` row update (active=false) + `outbox_events` row | `restaurants` UPDATE, `outbox_events` INSERT (topic=`samato.restaurant.updated`) | — | — | `restaurants::<id>` evicted |
| 9.6 — Deactivate projects | OpenSearch document overwritten (same content shape) | — | `samato.restaurant.updated` (key=restaurantId) | `restaurants` upsert (no `active` field — see anomaly) | — |

> **Two things to notice.** First, the Postgres writes and the Kafka publish are **not** atomic — they are decoupled by the outbox. The atomicity is between the business write and the outbox row; the Kafka publish is eventually-consistent. Second, the Redis cache is invalidated on every write (cache-aside). It's not in the critical path of the write side, but it is on the critical path of the read side for the "click into" step.

---

## 11. The "Avro vs byte[]" anomaly (callout)

This use case is the **cleanest example in the entire monorepo** of the proper Avro wire format. The other two publishers (order-service, payment-service) bypass the `shared-kafka` factory and send `byte[]` JSON instead. The reasons are documented in [shared-and-kafka.md](../services/shared-and-kafka.md) and the [glossary entry on the anomaly](../00-glossary.md#avro); the call-graph inventory is the source of truth for the producer/consumer wiring.

### 11.1 The three producers, side by side

| Publisher | Kafka template | Wire format | Schema Registry used? | Consumer |
|---|---|---|---|---|
| **restaurant-service** (this use case) | `KafkaTemplate<String, SpecificRecord>` from `shared-kafka.KafkaProducerConfig#kafkaTemplate` | **Confluent Avro** (magic byte `0x00` + 4-byte schema id + Avro binary) | **Yes** — `spring.kafka.properties.schema.registry.url` | search-service (only `@KafkaListener` in the monorepo) |
| order-service | `KafkaTemplate<String, byte[]>` from local `KafkaByteArrayConfig#byteArrayKafkaTemplate` | `Avro.toString(event).getBytes(UTF_8)` — **JSON text, not Avro wire format** | No | (no consumer today) |
| payment-service | `KafkaTemplate<String, byte[]>` from local `KafkaByteArrayConfig#byteArrayKafkaTemplate` | Same JSON text | No | (no consumer today) |

Source: [`docs/inventory/call-graph.json` → `kafkaProducers[]`](../inventory/call-graph.json#kafkaproducers). The `kafkaTemplate` field is explicit: `"KafkaTemplate<String, SpecificRecord> (default bean from shared-kafka KafkaProducerConfig)"` for `restaurant-service`, and `"KafkaTemplate<String, byte[]> (bean: byteArrayKafkaTemplate in com.samato.<service>.config.KafkaByteArrayConfig)"` for the other two.

### 11.2 Why this matters for this use case

Because `restaurant-service` and `search-service` are the only two services that share a true Avro contract:

- The Schema Registry stores the schema. Producers register a version; consumers fetch by id. The on-the-wire bytes are self-describing.
- The consumer uses `KafkaAvroDeserializer` (from `shared-kafka.KafkaConsumerConfig#kafkaListenerContainerFactory`) with `specific.avro.reader=true` (set in `search-service`'s `application.yml`). The result is a typed `RestaurantCreatedEvent` instance, with `getName()`, `getCuisine()`, etc.
- Schema evolution is governed by Confluent compatibility rules (backward-compat by default). Adding a new field with a default is non-breaking; renaming a field is breaking.

The other two publishers (order, payment) serialize to JSON bytes that happen to match the shape of the Avro-generated class. The Avro classes exist for **build-time type safety** on the producer side, but on the wire they are JSON. A future consumer that wants to read `samato.order.placed` would need a `ByteArrayDeserializer` + Jackson — *not* the Avro deserializer. The [glossary entry on the anomaly](../00-glossary.md#bytearraydeserializer--bytearrayserializer) calls this out: "The bytes are `Avro.toString(event).getBytes(UTF_8)` — **JSON, not Avro wire format**."

### 11.3 The `RestaurantCreatedEvent` and `RestaurantUpdatedEvent` schemas

Both Avro files live in [`shared-kafka/src/main/avro/`](../../shared-kafka/src/main/avro/) and are registered in the Schema Registry when the producer first sends an event of that type. The `avro-maven-plugin` in `shared-kafka/pom.xml` generates the Java classes (`com.samato.events.RestaurantCreatedEvent`, `com.samato.events.RestaurantUpdatedEvent`) at build time. The producer code (`Outbox.enqueueRestaurantCreated/Updated`) uses the typed builder API: `RestaurantCreatedEvent.newBuilder().setName(...).setCuisine(...)...build()`. The consumer code (`RestaurantProjector.apply`) uses `instanceof RestaurantCreatedEvent c` and reads the typed accessors.

This is the **end-to-end Avro flow** the bible's ARCHITECTURE.md ADR-12 ("JSONB inside, Avro on the wire") envisioned. The reality check in [01-architecture-guide § 3](../01-architecture-guide.md#3-the-data-story) is honest about it: "today only `restaurant-service` actually uses the Confluent Avro wire format end-to-end. `order-service` and `payment-service` use the `byte[]` (= `Avro.toString` JSON) path."

### 11.4 Idempotency at the consumer (only because of Avro)

The Avro deserializer hands `RestaurantProjector` a typed `SpecificRecord`. The projector pattern-matches on the concrete class and reads the typed accessors. Re-delivery is safe because the OpenSearch document `_id` is the restaurant's UUID, and the document is fully overwritten on each event — there is no incremental update that could double-count. This is the [idempotent consumer pattern](../00-glossary.md#idempotent-consumer) in action. With the byte[] JSON path, the consumer would also be idempotent (same payload = same deserialization), but the typed Avro path makes the contract explicit and evolvable.

---

## 12. Anomalies called out

The inventory and per-service docs surface several non-obvious gaps. Surfacing them here so a beginner doesn't waste hours on them:

1. **`spring.kafka.consumer.*` in restaurant-service's `application.yml` is inert.** No `@KafkaListener` exists in `restaurant-service`; the only consumer in the monorepo is `search-service`. The consumer settings are leftover scaffolding. See [restaurant-service.md § 7 anomalies item 1](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes).
2. **`addMenuItem` does not publish an event.** Menu changes don't propagate to search. A future change would add `MenuItemAddedEvent.avsc` and an `Outbox.enqueueMenuItemAdded` method. See [restaurant-service.md § 7 anomalies item 4](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes) and [§ 9.11 above](#911-addmenuitem-does-not-publish-an-event).
3. **`RestaurantUpdatedEvent` lacks `ownerId` and `active`.** Consumers cannot distinguish a deactivation from a regular update. The fix is a schema evolution. See [restaurant-service.md § 7 anomalies item 8](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes) and [§ 9.14 above](#914-restaurantupdatedevent-lacks-ownerid-and-active).
4. **Concurrent PUTs can produce a 500 from an unhandled `OptimisticLockException`.** There is no `If-Match` header support and no 409 wiring. See [§ 9.10 above](#910-two-concurrent-puts-to-the-same-restaurant).
5. **Resilience4j is on the classpath but not annotated.** `restaurant-service` has `resilience4j-spring-boot3` in its `pom.xml`; `search-service` has no Resilience4j block. No `@CircuitBreaker` / `@Retry` / `@TimeLimiter` annotations exist on the call sites. The [`call-graph.json` → `resilience4j[]`](../inventory/call-graph.json#resilience4j) says for `restaurant-service`: "Library is on the classpath; policies appear not yet wired." See [§ 9.9 above](#99-opensearch-query-takes-too-long).
6. **`INDEX` constant is duplicated in `search-service`'s initializer and projector.** `OpenSearchIndexInitializer.INDEX` and `RestaurantProjector.INDEX` are separate `public static final String` declarations. The `SearchController` references `RestaurantProjector.INDEX` (so a rename in the projector is half the fix), but the initializer's constant is independent. The search-service doc flags it in [§ 7 gotchas](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes). See [§ 9.12 above](#912-the-index-constant-is-duplicated).
7. **`DomainEvent` marker interface in `shared-kafka` is unused.** The Avro-generated event classes do not implement `com.samato.sharedkafka.events.DomainEvent`. The consumer uses `((GenericRecord) ev).get("eventId")` (positional via field name) rather than a typed accessor. See [search-service.md § 7 anomalies](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes).
8. **The outbox poller has no exponential backoff and no poison-message handling.** A continuously-failing event retries every second forever. The Outbox class javadoc calls this out as a "real system" improvement. See [restaurant-service.md § 3.13](../services/restaurant-service.md#313-comsamatorestaurantserviceserviceoutbox) and [§ 9.2 above](#92-kafka-is-down--outbox-row-stays-unsent).
9. **OpenSearch index does not exist on a cold start before the initializer finishes.** The `@EventListener(ApplicationReadyEvent.class)` runs after the embedded server is up, but the first request to `/api/search/restaurants` could (in theory) arrive before the listener completes. A `mapper_parsing_exception` / `index_not_found_exception` 404 from OpenSearch becomes a 500. The search-service doc flags this in [§ 7 gotchas](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes).
10. **No DLQ for poison messages.** `RestaurantEventListener` re-throws on any projection failure. A single bad event will block the partition forever. A production fix is a `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer`; out of scope for the bible. See [search-service.md § 7 gotchas](../services/search-service.md#7-common-if-you-change-x-also-update-y-notes).
11. **The 5-second `kafkaTemplate.send(...).get(5, SECONDS)` timeout is hard-coded.** A single slow Kafka broker could cause the poller to time out, leaving rows unsent. The catch block retries, but if the broker is consistently slow, the outbox backlog grows. There is no adaptive timeout. The `Outbox.java` source code calls this out via the `.get(5, SECONDS)` magic number — there is no `@ConfigurationProperties` for it.
12. **`RestaurantOwnership` does an extra DB read on every authorized write.** The class javadoc acknowledges this and suggests a short-TTL Caffeine cache as a follow-up. See [restaurant-service.md § 3.10](../services/restaurant-service.md#310-comsamatorestaurantservicesecurityrestaurantownership).
13. **The JWT subject must be a UUID.** `RestaurantController.create` does `UUID.fromString(jwt.getSubject())` and `RestaurantOwnership.isOwner` does the same. If `auth-service` ever changes the `sub` claim to be an email or a username, every write throws `IllegalArgumentException`. See [restaurant-service.md § 7 anomalies item 9](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes).
14. **The browse endpoints are visible to all authenticated users.** `@PreAuthorize("isAuthenticated()")` on the three GETs means any logged-in user (CUSTOMER, RESTAURANT_OWNER, DRIVER, ADMIN) can read all restaurant and menu data. If the product ever needs to hide drafts from non-owners, the authZ model will need to change. See [restaurant-service.md § 7 anomalies item 5](../services/restaurant-service.md#7-common-if-you-change-x-also-update-y-notes).

---

## 13. See also

- **[`../services/restaurant-service.md`](../services/restaurant-service.md)** — the file-by-file walkthrough of the write side (14 Java files, 4 packages). Section 3.13 covers `Outbox`; § 3.14 covers `RestaurantController`; § 3.11 covers `RestaurantService`; § 3.7 covers the outbox entity.
- **[`../services/search-service.md`](../services/search-service.md)** — the file-by-file walkthrough of the read side (7 Java files). Section 3.5 covers `RestaurantEventListener`; § 3.6 covers `RestaurantProjector`; § 3.3 covers the OpenSearch index initializer; § 3.7 covers `SearchController`.
- **[`../services/shared-and-kafka.md`](../services/shared-and-kafka.md)** — the `shared-kafka` module: `KafkaProducerConfig` (the Avro template), `KafkaConsumerConfig` (the Avro listener container factory), `KafkaMdcProducerInterceptor` (MDC propagation on the producer side), `MdcContext` (MDC scoping on the consumer side), and the Avro `.avsc` files.
- **[`../01-architecture-guide.md`](../01-architecture-guide.md)** — the system map. Section 4 ("The message story") has the cross-service topic table. Section 7 ("Cross-cutting concerns") explains the Resilience4j and metrics anomalies.
- **[`../00-glossary.md`](../00-glossary.md)** — the terms used in this doc: [CQRS](../00-glossary.md#cqrs), [transactional outbox](../00-glossary.md#transactional-outbox), [outbox poller](../00-glossary.md#outbox-poller), [idempotent consumer](../00-glossary.md#idempotent-consumer), [cache-aside](../00-glossary.md#cache-aside), [MDC](../00-glossary.md#mdc), [correlation id](../00-glossary.md#correlation-id), [geo_point](../00-glossary.md#opensearch), [Avro](../00-glossary.md#avro), [SpecificRecord](../00-glossary.md#specificrecord), [@KafkaListener](../00-glossary.md#kafkalistener).
- **[`../ARCHITECTURE.md`](../../ARCHITECTURE.md)** — the repo-level architecture. ADR-4 (CQRS), ADR-6 (Outbox), ADR-12 (JSONB inside, Avro on the wire) are the three most relevant.
- **[`./01-place-an-order.md`](./01-place-an-order.md)** — Alice is the same customer. This use case shows the read path; the place-an-order use case shows her write path. They share the same `RestaurantClient` Feign interface.
- **[`./02-auth-flow.md`](./02-auth-flow.md)** — the JWT registration and login step that produces the tokens Bob and Alice carry.
- **[`../docs/inventory/call-graph.json`](../inventory/call-graph.json)** — the source of truth for every "X lives in Y" claim. `kafkaListeners[]` has exactly one entry (search-service). `kafkaProducers[]` lists the two restaurant events with `kafkaTemplate: KafkaTemplate<String, SpecificRecord>`. `outboxTables[]` lists the three outbox tables. `resilience4j[]` confirms no annotations are present.
- **[`../docs/inventory/shared-and-kafka.json`](../inventory/shared-and-kafka.json)** — the cross-service Kafka and shared-module inventory. The "Anomalies" block at the top flags the `byte[]` JSON vs Avro wire format divergence.
- **[`../../services/search-service/docs/INTERVIEW-NOTES.md`](../../services/search-service/docs/INTERVIEW-NOTES.md)** — the per-service designer note for the read side. Q&A on idempotency, ordering, schema-on-read.
- **[`../../services/restaurant-service/docs/INTERVIEW-NOTES.md`](../../services/restaurant-service/docs/INTERVIEW-NOTES.md)** — the per-service designer note for the write side. Q&A on the outbox pattern, `@Version`, cache invalidation.

---

> **What to take away.** This use case is the simplest end-to-end CQRS flow in the monorepo. Two services (`restaurant-service` write, `search-service` read), one event topic family (`samato.restaurant.*`), one consumer in the entire monorepo, and a clean separation between "the system of record" (Postgres + Redis) and "the discovery index" (OpenSearch). The outbox pattern is the load-bearing piece: it makes the dual-write problem disappear by moving the Kafka publish out of the request thread and into a scheduled poller that reads durable rows. The Avro wire format is the bonus: `restaurant-service` and `search-service` share a true Schema-Registry-backed contract, while the other two publishers settle for `byte[]` JSON. The cost is eventual consistency: 1–2 seconds between a write and a search hit. The benefit is that the read side can be a different store with a different query language, optimized for the read pattern instead of the write pattern.
