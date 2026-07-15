# restaurant-service (port 8082)

> Plain-English purpose: restaurant-service owns the catalog side of the food-delivery platform — every restaurant, its menu, and the individual items customers can buy. The catalog is read about a thousand times more often than it is written, so the design optimizes for **reads** (a Redis cache sits in front of Postgres) while keeping **writes** atomic with their downstream events (the **transactional outbox** pattern writes the Kafka event to a `outbox_events` row in the same database transaction as the business write, and a scheduled poller ships it to Kafka). The events feed `search-service`, which projects them into OpenSearch. This service demonstrates CQRS-lite (cache-aside), the Transactional Outbox, Optimistic Locking, and JWT-based method security with a SpEL-callable ownership helper.

## 1. Where it sits in the system

```
                 ┌────────────────────┐
                 │  api-gateway :8080 │
                 │  (JWT filter,      │
                 │  routes /api/**)   │
                 └────────┬───────────┘
                          │ HTTP (JWT)
        ┌─────────────────┼──────────────────────────┐
        ▼                 ▼                          ▼
  order-service      search-service           [customers / owners /
  (Feign GET         (consumes Kafka)         admins via gateway]
   /api/restaurants/{id})

                  ┌─────────────────────────────┐
                  │  restaurant-service :8082   │
                  │                             │
                  │  reads:   Postgres, Redis   │
                  │  writes:  Postgres + outbox │
                  │  events:  Kafka (Avro)      │
                  └──────────────┬──────────────┘
                                 │ produces
                                 ▼
                       samato.restaurant.created
                       samato.restaurant.updated
                                 │
                                 ▼
                          search-service
                          (OpenSearch projection)
```

Calls INTO restaurant-service:
- `api-gateway` forwards browser/mobile requests on path `/api/restaurants/**` here.
- `order-service` (via OpenFeign `RestaurantClient`) calls `GET /api/restaurants/{id}` and `GET /api/restaurants/{id}/menu` during the `VALIDATE_RESTAURANT` and `VALIDATE_ITEMS` steps of the order placement saga.
- Browser/mobile users (authenticated as CUSTOMER, RESTAURANT_OWNER, or ADMIN) call all seven REST endpoints.

Calls OUT of restaurant-service (none over HTTP — outbound is only via Kafka):
- No `RestTemplate` or `WebClient` beans (verified against the inventory). All cross-service outbound traffic is event-driven (Kafka).

Events PUBLISHED:
- `samato.restaurant.created` (Avro `RestaurantCreatedEvent`) — after `RestaurantService.create(...)`.
- `samato.restaurant.updated` (Avro `RestaurantUpdatedEvent`) — after `RestaurantService.update(...)` and `RestaurantService.deactivate(...)`.

Events CONSUMED:
- None. This service is a publisher-only side of the catalog pipeline.

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/restaurant-service` |
| Port | `8082` (from `server.port` in `application.yml`) |
| Database(s) | `restaurant_service` on Postgres 16 (`samato-postgres:5432`); `init-databases.sh` provisions the DB |
| Redis | `samato-redis:6379` (cache only, key prefix `samato:cache:`, TTL 5 min) |
| Kafka | `samato-kafka:9094` (producer only) |
| Schema Registry | `samato-schema-registry:8081` (Confluent; used implicitly by the Avro serializer) |
| Publishes topics | `samato.restaurant.created`, `samato.restaurant.updated` |
| Consumes topics | (none) |
| REST endpoints | `GET /api/restaurants/{id}`, `GET /api/restaurants?city=...`, `GET /api/restaurants/{id}/menu`, `POST /api/restaurants`, `PUT /api/restaurants/{id}`, `DELETE /api/restaurants/{id}`, `POST /api/restaurants/{id}/menu` |
| Depends on (services) | `auth-service` (JWKS for JWT validation), `discovery-service` (Eureka), `config-service` (disabled in dev), `config-repo/application.yml` (global defaults), Postgres, Redis, Kafka, Schema Registry |
| Depends on (shared libs) | `com.samato:shared` (errors, observability, OpenFeign), `com.samato:shared-kafka` (Avro, KafkaTemplate, `RestaurantCreatedEvent`, `RestaurantUpdatedEvent`) |
| Build tag | `restaurant-service:latest` (from `services/restaurant-service/Dockerfile`) |
| Container name | `samato-restaurant-service` (compose) |
| Health | `curl http://localhost:8082/actuator/health` -> `{"status":"UP",...}` (verified in `docs/INTERVIEW-NOTES.md`) |
| Eureka name | `SAMATO-RESTAURANT-SERVICE` (uppercased `spring.application.name`) |

## 3. File-by-file walkthrough

The service has 14 Java files across 4 packages: `com.samato.restaurantservice` (root), `.config`, `.domain`, `.security`, `.service`, `.web`. (The inventory reports 14 — the listed 12 below plus the request DTOs that are nested records inside `RestaurantController.java`.)

### 3.1 `com.samato.restaurantservice.RestaurantServiceApplication`

- **What it is**: The Spring Boot main class — the entry point that starts the embedded Tomcat, wires the auto-configuration, and scans for components.
- **Why it exists**: Spring Boot needs a `main` method to bootstrap the application context. The class also widens the component scan to include the shared modules so the cross-cutting beans (`DomainException`, `GlobalExceptionHandler`, `CorrelationIdFilter`, `KafkaTemplate`, `RestaurantCreatedEvent`, etc.) are visible to this service.
- **Spring annotations**:
  - `@SpringBootApplication`: marks this as a Spring Boot app and triggers auto-configuration. (See [glossary](../00-glossary.md#springbootapplication) — the `@SpringBootApplication` is a meta-annotation that combines `@Configuration`, `@EnableAutoConfiguration`, and `@ComponentScan`.)
  - `@EnableDiscoveryClient`: registers the service with Eureka (`discovery-service`) at startup so the gateway and other clients can find it by name `samato-restaurant-service`.
  - `@EnableCaching`: turns on Spring's cache abstraction so the `@Cacheable`/`@CacheEvict` annotations on `RestaurantService` are honored.
  - `@EnableScheduling`: turns on the `@Scheduled` task executor so `Outbox.publishPending()` runs every second.
  - `@ComponentScan(basePackages = {"com.samato.restaurantservice","com.samato.shared","com.samato.sharedkafka"})`: tells Spring to scan not just this app's own package but also the two shared modules. Without this the Avro event classes and the `KafkaTemplate` bean from `shared-kafka` would not be injectable here.
- **What it calls**: nothing at startup beyond `SpringApplication.run`.
- **What calls it**: only the JVM (via `main(String[])`).
- **Configuration keys**: reads no application keys directly; the `SpringApplication.run` call indirectly reads everything in `application.yml`.

### 3.2 `com.samato.restaurantservice.config.CacheConfig`

- **What it is**: A Spring `@Configuration` class that builds a Redis-backed `CacheManager` bean. The `restaurants` cache (the only cache name in the service) uses this `CacheManager` automatically.
- **Why it exists**: Spring's cache abstraction is provider-agnostic. Without this bean, `@Cacheable` annotations in `RestaurantService` would fail because no `CacheManager` would be present. Picking Redis (rather than an in-memory Caffeine) is deliberate — see the class javadoc: it lets multiple instances of `restaurant-service` share the same cache.
- **Spring annotations**:
  - `@Configuration`: marks this class as a source of `@Bean` definitions that Spring should pick up at startup.
  - `@Bean` (on `cacheManager(...)`): tells Spring to call this method once and put the result into the application context; any `@Cacheable` will look it up.
- **What it calls**:
  - `RedisConnectionFactory` (injected by Spring Boot autoconfigure from `spring.data.redis.*`).
  - `GenericJackson2JsonRedisSerializer` + `JavaTimeModule` to JSON-serialize cache values.
  - `StringRedisSerializer` to JSON-serialize cache keys (so they read like `restaurants::abc-123` in `redis-cli`).
- **What calls it**: nothing directly — it is consumed by Spring's cache infrastructure when `@Cacheable(value = "restaurants", ...)` fires.
- **Configuration keys**: relies on Spring Boot's `spring.data.redis.host`, `spring.data.redis.port`, `spring.data.redis.timeout` (in `application.yml` lines 31-35). The TTL (`5 minutes`) and the cache name prefix (`samato:cache:`) are hard-coded in the bean.

### 3.3 `com.samato.restaurantservice.domain.Restaurant`

- **What it is**: A JPA `@Entity` mapping to the `restaurants` table. One row = one restaurant.
- **Why it exists**: This is the write model. The data model is intentionally narrow — identification (`id`, `ownerId`), display (`name`, `description`, `cuisine`), and location (`address`, `city`, `latitude`, `longitude`). It does NOT keep ratings, images, or menu items here (ratings are computed by `analytics-service`; menu items are in their own table; images are an object store).
- **Spring annotations**:
  - `@Entity`: marks this class as a JPA entity. Hibernate will create a managed instance and map it to a row.
  - `@Table(name = "restaurants", indexes = {...})`: pins the table name and declares two indexes (`idx_restaurants_city` on `city`, `idx_restaurants_owner` on `owner_id`) — Hibernate validates these against the schema on startup with `ddl-auto: validate`.
  - `@Id` + `@GeneratedValue` + `@Column(columnDefinition = "uuid")`: declares `id` as a UUID primary key that the database generates.
  - `@Column(name = "owner_id", nullable = false, columnDefinition = "uuid")`: stores the foreign reference to `auth-service`'s user id. Note the comment in the source: this is a UUID, NOT a database foreign key — restaurants trust the JWT subject.
  - `@Version` on `version`: enables JPA optimistic locking. If two updates race, the second one fails with `OptimisticLockException` instead of silently overwriting the first.
  - `@PrePersist` / `@PreUpdate` (lifecycle callbacks): stamps `createdAt` and `updatedAt` timestamps at insert/update time. The `updatable = false` on `createdAt` means JPA will never write to it after the initial insert.
- **What it calls**: nothing at runtime beyond JPA plumbing.
- **What calls it**:
  - `RestaurantRepository` (reads/writes).
  - `RestaurantService` (creates, patches, deactivates).
  - `RestaurantOwnership` (reads to check owner).
  - `Outbox.enqueueRestaurantCreated/Updated` (reads fields to build the Avro event).
  - `RestaurantController` (returns it as the response body of GET/PUT).
- **Configuration keys**: none directly.

### 3.4 `com.samato.restaurantservice.domain.RestaurantRepository`

- **What it is**: A Spring Data JPA `JpaRepository` for `Restaurant`. Spring Data generates the implementation at runtime.
- **Why it exists**: Provides three query methods beyond the default `findById`, `save`, `delete`:
  - `findByCityIgnoreCaseAndActiveTrue(String city)` — used by the city browse endpoint.
  - `findByOwnerId(UUID ownerId)` — used for the "my restaurants" flow (currently consumed only by the in-service `RestaurantService.findByOwner`).
- **Spring annotations**: none on the interface (Spring Data finds it via the parent `JpaRepository` and package scanning from the `@SpringBootApplication`).
- **What it calls**: `JpaRepository<Restaurant, UUID>` (in the framework).
- **What calls it**:
  - `RestaurantService` (every read and write).
  - `RestaurantOwnership.isOwner` (the SpEL-callable helper that the controller's `@PreAuthorize` invokes).
- **Configuration keys**: none.

### 3.5 `com.samato.restaurantservice.domain.MenuItem`

- **What it is**: A JPA `@Entity` mapping to the `menu_items` table. One row = one sellable product (e.g. "Pad Thai", "Coke Zero").
- **Why it exists**: Separates the per-restaurant catalog of products from the restaurant itself. The `price` column is `BigDecimal` (mapped to Postgres `NUMERIC(10,2)`) — the source javadoc explicitly calls out that floating-point for currency is a bug factory.
- **Spring annotations**:
  - `@Entity` + `@Table(name = "menu_items", indexes = {...})`: same role as on `Restaurant`. The single index (`idx_menu_items_restaurant` on `restaurant_id`) makes "give me the menu of restaurant X" an indexed lookup.
  - `@Column(precision = 10, scale = 2)` on `price`: matches the SQL `NUMERIC(10,2)` exactly so JPA validation passes.
- **What it calls**: nothing.
- **What calls it**:
  - `MenuItemRepository` (reads/writes).
  - `RestaurantService.addMenuItem` (creates).
  - `RestaurantService.getMenu` (reads).
  - `RestaurantController` (serializes in the response body of `GET /api/restaurants/{id}/menu` and `POST /api/restaurants/{id}/menu`).
- **Configuration keys**: none.

### 3.6 `com.samato.restaurantservice.domain.MenuItemRepository`

- **What it is**: A Spring Data `JpaRepository<MenuItem, UUID>`.
- **Why it exists**: Adds the single query method `findByRestaurantId(UUID)` so `getMenu(restaurantId)` is one indexed call.
- **Spring annotations**: none.
- **What it calls**: Spring Data framework.
- **What calls it**: `RestaurantService.getMenu`, `RestaurantService.addMenuItem`.
- **Configuration keys**: none.

### 3.7 `com.samato.restaurantservice.domain.OutboxEvent`

- **What it is**: A JPA `@Entity` mapping to the `outbox_events` table. One row = one Kafka event waiting to be (or already) published.
- **Why it exists**: This is the **Transactional Outbox pattern**. The idea (spelled out in the class javadoc): the service never calls `KafkaTemplate.send` directly from the request thread. Instead, the business write and the outbox insert happen in the **same JPA transaction**, so either both are committed or neither is. A separate scheduled poller (`Outbox.publishPending`) reads unsent rows, sends them, and marks them sent. This eliminates the dual-write problem (DB committed but Kafka send failed).
- **Spring annotations**:
  - `@Entity` + `@Table(name = "outbox_events", indexes = { @Index(name = "idx_outbox_unsent", columnList = "sent_at") })`: pins the table and declares an index. Note: the index in the JPA annotation names the column `sent_at`, but the actual SQL migration creates a **partial** index `(created_at) WHERE sent_at IS NULL` — the index is functionally correct because the poller's `WHERE sent_at IS NULL` matches the partial predicate, but be aware the JPA annotation does not capture the partial-WHERE clause.
  - `@Column(columnDefinition = "BYTEA")` on `payload`: stores the Avro-encoded bytes. The source comment is important: no `@Lob` here, because Hibernate 6 + Postgres would map `@Lob byte[]` to the OID/BYTEA wire format inconsistently; the explicit `BYTEA` column-definition fixes the bring-up bug noted in `docs/INTERVIEW-NOTES.md`.
  - `eventType`: stores the **fully-qualified class name** of the Avro record (e.g. `com.samato.events.RestaurantCreatedEvent`). This is the lookup key that `AvroBytes.decode` uses to pick the right reader class.
  - `sentAt`: `null` = unsent (eligible to be polled); `non-null` = already published. The poller flips this to `Instant.now()` after a successful send.
  - `@PrePersist` on `onCreate()`: stamps `createdAt` at insert time; `updatable = false` prevents JPA from ever re-writing it.
- **What it calls**: nothing.
- **What calls it**:
  - `OutboxEventRepository.findUnsent()` (reads).
  - `Outbox.enqueue(...)` (writes inside the business transaction).
  - `Outbox.publishPending` (reads, then updates `sentAt`).
- **Configuration keys**: none.

### 3.8 `com.samato.restaurantservice.domain.OutboxEventRepository`

- **What it is**: A Spring Data `JpaRepository<OutboxEvent, UUID>` with one custom query.
- **Why it exists**: Exposes the polling query the outbox needs: `SELECT e FROM OutboxEvent e WHERE e.sentAt IS NULL ORDER BY e.createdAt ASC LIMIT 100`. The `LIMIT 100` is hard-coded and bounds each poll batch.
- **Spring annotations**:
  - `@Query("SELECT e FROM OutboxEvent e WHERE e.sentAt IS NULL ORDER BY e.createdAt ASC LIMIT 100")` on `findUnsent()`: a JPQL query. Note the use of `LIMIT` in JPQL — this is **Hibernate-specific** (the JPQL standard does not have `LIMIT`). It is equivalent to `setMaxResults(100)`.
- **What it calls**: Spring Data JPA.
- **What calls it**: `Outbox.publishPending()` (every 1 second via `@Scheduled`).
- **Configuration keys**: none.

### 3.9 `com.samato.restaurantservice.security.SecurityConfig`

- **What it is**: The Spring Security configuration. Sets the security filter chain, the JWT decoder, and a custom role-mapping converter.
- **Why it exists**: Every request to a `samato-restaurant-service` endpoint (except `/actuator/health/**` and `/actuator/info`) must carry a JWT issued by `auth-service`. This class:
  1. Builds a `SecurityFilterChain` that disables CSRF (correct for a stateless API), uses `STATELESS` sessions, returns 401 for unauthenticated requests, and wires the JWT resource server.
  2. Builds a `JwtDecoder` that downloads the signing keys from `auth-service`'s `/.well-known/jwks.json` endpoint and uses them to verify token signatures.
  3. Builds a `JwtAuthenticationConverter` that extracts the user's roles from the `roles` claim and prefixes them with `ROLE_` so they work with `@PreAuthorize("hasRole(...)")`.
- **Spring annotations**:
  - `@Configuration`: same role as `CacheConfig`.
  - `@EnableMethodSecurity`: turns on `@PreAuthorize` evaluation. Without this, the annotations on `RestaurantController` would be ignored.
  - `@Bean` on `filterChain(...)`, `jwtDecoder(...)`, `jwtAuthenticationConverter(...)`: registers three Spring beans.
- **What it calls**:
  - `NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()` — the JWT decoder is built lazily; it fetches the JWKS on first use and caches the keys.
  - `JwtGrantedAuthoritiesConverter` (Spring Security utility) — the class is configured to read the `roles` claim (not the default `scope`/`scp`) and prepend the `ROLE_` prefix.
- **What calls it**: Spring Security's `WebSecurityAutoConfiguration` picks up the `SecurityFilterChain` bean and uses it to gate every request.
- **Configuration keys**:
  - `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` — injected into `jwtDecoder(...)` via `@Value`. In local dev: `http://localhost:9000/.well-known/jwks.json`. In compose: `http://auth-service:9000/.well-known/jwks.json` (overridden by the `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` env var in the container env block).

### 3.10 `com.samato.restaurantservice.security.RestaurantOwnership`

- **What it is**: A `@Component` (bean name `restaurantOwnership`) with one method `isOwner(UUID, Authentication)`.
- **Why it exists**: `@PreAuthorize` expressions are SpEL. Going to the database directly from SpEL is ugly and unhittable for tests. Spring allows SpEL to call a Spring bean via `@beanName.methodName(args)`, so this class is a thin wrapper that returns `true` iff the authenticated user's id (from the JWT subject) matches the `ownerId` column on the restaurant. The class javadoc calls out the trade-off: an extra DB read on every authorized write; for prod you'd cache owner->restaurant in Caffeine for a few seconds.
- **Spring annotations**:
  - `@Component("restaurantOwnership")`: registers it as a Spring bean. The name `restaurantOwnership` is the SpEL name the controller references.
- **What it calls**:
  - `RestaurantRepository.findById(UUID)` (inside `isOwner`).
  - `UUID.fromString` (parses the JWT subject).
- **What calls it**:
  - `RestaurantController.update` — via the SpEL expression `@restaurantOwnership.isOwner(#id, authentication)` in `@PreAuthorize`.
  - `RestaurantController.deactivate` — same.
  - `RestaurantController.addMenuItem` — same.
- **Configuration keys**: none.

### 3.11 `com.samato.restaurantservice.service.RestaurantService`

- **What it is**: The `@Service` class that holds the business logic and the transaction boundary.
- **Why it exists**: Separates the controller (HTTP shape) from the data access (repositories) and the side effects (cache + outbox). The class javadoc lists the four things to notice:
  1. Every write is `@Transactional` so the business write and the outbox row commit together.
  2. `@Cacheable` on `get(...)` (read-through) and `@CacheEvict` on every write (invalidate).
  3. Outbox enqueue is a *row insert*, not a `kafkaTemplate.send` — the actual send happens in a separate thread.
  4. `ownerId` is trusted from the JWT; not validated against `user-service` here.
- **Spring annotations**:
  - `@Service`: a specialization of `@Component` that marks this as a business-logic bean. Spring picks it up via component scan.
  - `@Cacheable(value = CACHE, key = "#id")` on `get(UUID id)`: if the `restaurants` cache has a hit for this id, return it; else call the method, cache the result, return it. The key expression is SpEL; `#id` refers to the method parameter.
  - `@CacheEvict(value = CACHE, key = "#result.id")` (on `create`) and `key = "#id"` (on `update`/`deactivate`): remove the cache entry. Note the difference: on `create` the id isn't known until the method returns, so the key references `#result.id`; on `update` and `deactivate` the id is already a parameter.
  - `@Caching(evict = {...})`: a wrapper that lets you stack multiple cache operations on a single method. (Each write here only has one evict, so `@Caching` is being used for forward-compatibility rather than necessity.)
  - `@Transactional` on every write — full transaction (read-write).
  - `@Transactional(readOnly = true)` on every read — gives Hibernate a hint that no flush is needed at the end, which is a small optimization.
- **What it calls**:
  - `RestaurantRepository.findById`, `save`, `findByCityIgnoreCaseAndActiveTrue`, `findByOwnerId`, `existsById`.
  - `MenuItemRepository.findByRestaurantId`, `save`.
  - `Outbox.enqueueRestaurantCreated`, `enqueueRestaurantUpdated`.
  - `DomainException` (from `com.samato.shared.errors` — see [shared-and-kafka](./shared-and-kafka.md#domainexception)) thrown when a restaurant id is not found, with code `RESTAURANT_NOT_FOUND`, HTTP 404.
- **What calls it**:
  - `RestaurantController` — every REST endpoint.
  - Nothing else (no other service has a Feign client back to this code; only the controller does).
- **Configuration keys**: the cache name `CACHE = "restaurants"` is a hard-coded constant in the class, but the actual cache **provider** is the Redis `CacheManager` from `CacheConfig`, so the underlying TTL and serialization come from there.

### 3.12 `com.samato.restaurantservice.service.AvroBytes`

- **What it is**: A package-private utility class with two static methods, `encode(SpecificRecord) -> byte[]` and `decode(String eventType, byte[]) -> SpecificRecord`. A `Map<String, Class<? extends SpecificRecord>>` registry maps the FQN string stored in `outbox_events.event_type` to the Avro class.
- **Why it exists**: The outbox stores **bytes** (Postgres `BYTEA`), not `SpecificRecord` objects. The bytes need to round-trip — and crucially, the bytes the poller sends to Kafka must be the same bytes the outbox stored, so that the Avro schema version is pinned at write time. The class javadoc explains: the producer's serializer would re-encode the record on the publish thread; if the schema evolved between the write time and the publish time, you could get a different payload than the one committed. Encoding at write time pins the schema.
- **Spring annotations**: none. This is a plain utility class.
- **What it calls**:
  - `DataFileWriter<SpecificRecord>` + `SpecificDatumWriter` from `org.apache.avro` — writes the record using Avro's **DataFile format** (header + blocks, not raw binary). The header is small (~few hundred bytes).
  - `DataFileReader<SpecificRecord>` + `SpecificDatumReader` + `SeekableByteArrayInput` (not `ByteArrayInputStream` — this was a bring-up bug noted in `docs/INTERVIEW-NOTES.md`) — reads the DataFile back out.
  - `CodecFactory.nullCodec()` — no compression inside the outbox row (we're going to compress on the wire at the Kafka producer level with `compression.type: lz4`).
- **What calls it**:
  - `Outbox.enqueue(...)` (via the private overload) — calls `encode` to serialize the Avro event.
  - `Outbox.publishPending()` — calls `decode` to deserialize the row before sending to Kafka.
- **Configuration keys**: none.

### 3.13 `com.samato.restaurantservice.service.Outbox`

- **What it is**: The outbox write side and the publish side in a single `@Component`. It exposes two write methods (`enqueueRestaurantCreated`, `enqueueRestaurantUpdated`) called from `RestaurantService`, and one scheduled method (`publishPending`) that polls and ships events.
- **Why it exists**: This is the implementation of the **Transactional Outbox** pattern for this service. The class javadoc spells out the two-phase nature:
  - **Write side** (`enqueue*`): called inside the same transaction as the business write. The Avro record is built, encoded via `AvroBytes.encode`, and a row is inserted into `outbox_events`.
  - **Publish side** (`@Scheduled` `publishPending`): every 1 second, reads up to 100 unsent events, decodes each via `AvroBytes.decode`, sends to Kafka via `KafkaTemplate.send(...).get(5, SECONDS)` (synchronous, on purpose), and on success sets `sentAt = Instant.now()`.
- **Spring annotations**:
  - `@Component`: registers the bean.
  - `@Scheduled(fixedDelay = 1000L)` on `publishPending()`: tells Spring's scheduler to invoke this method every 1 second (the `fixedDelay` is the time between the end of one execution and the start of the next, so a slow poll doesn't queue up back-to-back runs).
  - `@Transactional` on `publishPending()`: the poll + decode + send + update runs in one transaction. If the send fails, the catch block logs and leaves the row unsent; the next poll retries.
- **What it calls**:
  - `OutboxEventRepository.findUnsent()` (the poll).
  - `OutboxEventRepository.save(row)` (after marking `sentAt`).
  - `AvroBytes.encode(event)` (write side) and `AvroBytes.decode(eventType, payload)` (publish side).
  - `KafkaTemplate<String, SpecificRecord>.send(ProducerRecord).get(5, SECONDS)` — **synchronous** send with a 5-second timeout. The class javadoc calls out: we deliberately use `.get()` rather than `.whenComplete(...)` because we want to know whether the send succeeded before flipping `sentAt`.
  - `RestaurantCreatedEvent.newBuilder()` and `RestaurantUpdatedEvent.newBuilder()` (the Avro-generated classes from `shared-kafka`).
- **What calls it**:
  - `RestaurantService.create`, `update`, `deactivate` — call the `enqueue*` methods.
  - The Spring scheduler — calls `publishPending` every second.
- **Configuration keys**: the topics are hard-coded as constants `TOPIC_RESTAURANT_CREATED = "samato.restaurant.created"` and `TOPIC_RESTAURANT_UPDATED = "samato.restaurant.updated"`. The Avro `enable.idempotence` and `compression.type` come from `spring.kafka.producer.*` in `application.yml` (lines 42-46), but the application code does not read them directly — they are baked into the `KafkaTemplate` by `shared-kafka`'s `KafkaProducerConfig`.

### 3.14 `com.samato.restaurantservice.web.RestaurantController`

- **What it is**: The single `@RestController` for this service. It maps HTTP requests under `/api/restaurants` to `RestaurantService` calls. It also declares three request DTOs as nested `record` types.
- **Why it exists**: This is the only HTTP surface of the service. The class javadoc lays out the authZ model:
  - GETs — any authenticated user.
  - POST create — `RESTAURANT_OWNER` only.
  - PUT/DELETE/POST menu — owner (checked via the SpEL helper) or `ADMIN`.
- **Spring annotations**:
  - `@RestController`: marks the class as a web controller whose method return values are serialized to JSON and put in the response body. (See [glossary](../00-glossary.md#restcontroller).)
  - `@RequestMapping("/api/restaurants")`: the base path for every mapping below.
  - `@GetMapping("/{id}")`, `@GetMapping(params = "city")`, `@GetMapping("/{id}/menu")`, `@PostMapping`, `@PutMapping("/{id}")`, `@DeleteMapping("/{id}")`, `@PostMapping("/{id}/menu")`: HTTP verb + sub-path. (See [glossary](../00-glossary.md#xxmapping).)
  - `@PathVariable UUID id`, `@RequestParam String city`, `@Valid @RequestBody CreateRestaurantRequest req`: bind path variables, query params, and JSON bodies. `@Valid` triggers Bean Validation on the DTOs. (See [glossary](../00-glossary.md#pathvariable).)
  - `@ResponseStatus(HttpStatus.CREATED)` on the two POSTs, `@ResponseStatus(HttpStatus.NO_CONTENT)` on the DELETE: override the default 200 to 201/204.
  - `@PreAuthorize("isAuthenticated()")` on the three GETs: any logged-in user.
  - `@PreAuthorize("hasRole('RESTAURANT_OWNER')")` on `create`: only restaurant owners.
  - `@PreAuthorize("hasRole('ADMIN') or @restaurantOwnership.isOwner(#id, authentication)")` on the three writes: admin OR the restaurant's owner. The `@restaurantOwnership.isOwner(...)` is a SpEL reference to the `RestaurantOwnership` bean.
  - `@AuthenticationPrincipal Jwt jwt` on `create`: injects the parsed JWT so the controller can read the subject (user id) and use it as the new restaurant's `ownerId`.
- **What it calls**:
  - `RestaurantService` (every method).
  - `Restaurant`, `MenuItem` (returned as response bodies).
  - `DomainException` indirectly (via the service) — see [shared-and-kafka](./shared-and-kafka.md#globalexceptionhandler).
  - `CorrelationIdFilter` is a Servlet filter in `shared` that wraps every request and stamps the `X-Correlation-Id` header / MDC slot — see [shared-and-kafka](./shared-and-kafka.md#correlationidfilter).
- **What calls it**:
  - The api-gateway forwards `/api/restaurants/**` to this service.
  - `order-service` (via the OpenFeign `RestaurantClient`, declared in `services/order-service/src/main/java/com/samato/orderservice/api/RestaurantClient.java`) calls `GET /api/restaurants/{id}` and `GET /api/restaurants/{id}/menu` during the order saga's `VALIDATE_RESTAURANT` and `VALIDATE_ITEMS` steps.
  - Browser/mobile clients (CUSTOMER, RESTAURANT_OWNER, ADMIN) call the seven endpoints directly through the gateway.
- **Configuration keys**: none directly.

#### Request DTOs (nested records)

All three live in `RestaurantController` as `public record` types:

- `CreateRestaurantRequest(String name, String description, String cuisine, String address, String city, double latitude, double longitude)`. The first five string fields have `@NotBlank` and `@Size` constraints. The two doubles are required (no constraint = present).
- `UpdateRestaurantRequest(...same fields...)`. All string fields are **optional** (no `@NotBlank`); `latitude` and `longitude` are boxed `Double` (not `double`) so a missing JSON field arrives as `null` and the service can ignore it.
- `CreateMenuItemRequest(String name, String description, BigDecimal price)`. `name` is `@NotBlank @Size(max=200)`, `description` is `@Size(max=1000)`, `price` is `@NotNull @DecimalMin("0.00")`. The `@DecimalMin` rejects negative prices at the controller layer before the service is even called.

### 3.15 Application config — `application.yml`

Every key in `services/restaurant-service/src/main/resources/application.yml`, in the order they appear:

- `server.port: 8082` — the HTTP port the embedded Tomcat binds to.
- `server.shutdown: graceful` — on SIGTERM, finish in-flight requests before stopping. (Kubernetes liveness probe friendly.)
- `spring.application.name: samato-restaurant-service` — the service's name. Used as the Eureka registration name and the default Kafka client.id prefix.
- `spring.cloud.config.enabled: false` — disables the Spring Cloud Config client for local dev. The file comment says "flip on when config-service is up". In the `docker-compose.yml` env block, this is not overridden, so it remains `false` in containers as well (the service is OK running without the config server).
- `spring.datasource.url: jdbc:postgresql://localhost:5432/restaurant_service` — the JDBC URL. Compose overrides this to `jdbc:postgresql://postgres:5432/restaurant_service` via the `SPRING_DATASOURCE_URL` env var.
- `spring.datasource.username: fd` / `password: fd` — the Postgres credentials. Compose overrides via env vars.
- `spring.jpa.hibernate.ddl-auto: validate` — Hibernate checks the entity definitions against the schema at startup, but does **not** create or alter tables. Schema changes go through Flyway migrations only.
- `spring.flyway.enabled: true` — Flyway runs at startup.
- `spring.flyway.locations: classpath:db/migration` — where Flyway looks for `V<n>__<desc>.sql` files.
- `spring.flyway.baseline-on-migrate: true` — if the DB has no Flyway schema history table, baseline it instead of failing.
- `spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://localhost:9000/.well-known/jwks.json` — the URL from which `auth-service`'s public signing keys are downloaded. Compose overrides via `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` env var.
- `spring.data.redis.host: localhost` / `port: 6379` — Redis cache connection. Compose overrides via `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT`.
- `spring.data.redis.timeout: 1s` — fail fast if Redis is unreachable.
- `spring.kafka.bootstrap-servers: localhost:9092` — Kafka broker list. Compose overrides via `SPRING_KAFKA_BOOTSTRAP_SERVERS` to `kafka:9094` (the internal listener).
- `spring.kafka.properties.schema.registry.url: http://localhost:8085` — Confluent Schema Registry URL. Compose overrides to `http://schema-registry:8081`. The Avro serializer/auto-config in `shared-kafka` reads this.
- `spring.kafka.producer.acks: all` — wait for all in-sync replicas to acknowledge the write. Required for the idempotent producer guarantee.
- `spring.kafka.producer.properties.enable.idempotence: true` — Kafka guarantees no duplicates on the producer side even with retries.
- `spring.kafka.producer.properties.compression.type: lz4` — compress messages on the wire.
- `spring.kafka.consumer.group-id: samato-restaurant-service` — the consumer group id (this service has no listeners; this is configured but unused).
- `spring.kafka.consumer.auto-offset-reset: earliest` — if no committed offset, start from the beginning.
- `spring.kafka.consumer.properties.specific.avro.reader: true` — tells the Avro deserializer to use `SpecificRecord` subclasses. This service has no inbound consumer either, but the value is set as a default.
- `eureka.client.service-url.defaultZone: http://localhost:8761/eureka/` — the Eureka server URL. Compose overrides via `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` env var.
- `management.endpoints.web.exposure.include: health,info,prometheus,metrics` — Actuator endpoints exposed. `prometheus` is what Prometheus scrapes.
- `management.endpoint.health.probes.enabled: true` — adds `liveness` and `readiness` sub-endpoints for Kubernetes.
- `management.tracing.sampling.probability: 1.0` — sample 100% of traces and send them to Zipkin.
- `management.zipkin.tracing.endpoint: http://localhost:9411/api/v2/spans` — Zipkin collector URL.
- `logging.pattern.level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"` — log pattern includes service name, trace id, span id, and correlation id (the `:-` provides a default empty string if the MDC slot is unset).
- `logging.level.com.samato: DEBUG` — chatty logs for our own code; framework logs are at INFO.

## 4. Endpoints (controllers)

All endpoints require a valid JWT issued by `auth-service` except `GET /actuator/health/**` and `GET /actuator/info` which are `permitAll` (per `SecurityConfig.filterChain`).

The base path is `/api/restaurants`.

### 4.1 `GET /api/restaurants/{id}` — get a restaurant

- **Handler**: `RestaurantController.get(UUID id)` -> `RestaurantService.get(UUID id)`.
- **AuthZ**: `@PreAuthorize("isAuthenticated()")` — any logged-in user.
- **Cache**: hit Redis `restaurants::<id>` first; on miss, query Postgres and populate the cache. (`@Cacheable`.)
- **Response 200**: a `Restaurant` JSON object (id, ownerId, name, description, cuisine, address, city, latitude, longitude, active, createdAt, updatedAt, version).
- **Response 404**: `DomainException("RESTAURANT_NOT_FOUND", ...)` — converted to JSON `{"code": "RESTAURANT_NOT_FOUND", "message": "...", "httpStatus": 404}` by `GlobalExceptionHandler` in `shared`. See [shared-and-kafka](./shared-and-kafka.md#globalexceptionhandler).
- **Example curl** (assuming you got a token from `auth-service`):

  ```bash
  curl -i http://localhost:8080/api/restaurants/3fa85f64-5717-4562-b3fc-2c963f66afa6 \
       -H "Authorization: Bearer $ACCESS_TOKEN"
  ```

  (The gateway listens on 8080. To call the service directly without the gateway, use port 8082.)

### 4.2 `GET /api/restaurants?city={city}` — browse by city

- **Handler**: `RestaurantController.findByCity(String city)`.
- **AuthZ**: any authenticated user.
- **Cache**: NOT cached (see interview notes — list invalidation is hard).
- **Response 200**: a JSON array of `Restaurant` objects where `active = true` and `city` matches case-insensitively.
- **Example curl**:

  ```bash
  curl "http://localhost:8080/api/restaurants?city=Chennai" \
       -H "Authorization: Bearer $ACCESS_TOKEN"
  ```

### 4.3 `GET /api/restaurants/{id}/menu` — get the menu

- **Handler**: `RestaurantController.getMenu(UUID id)`.
- **AuthZ**: any authenticated user.
- **Cache**: not cached.
- **Response 200**: a JSON array of `MenuItem` objects (id, restaurantId, name, description, price, available, createdAt, updatedAt).
- **Example curl**:

  ```bash
  curl http://localhost:8080/api/restaurants/3fa85f64-5717-4562-b3fc-2c963f66afa6/menu \
       -H "Authorization: Bearer $ACCESS_TOKEN"
  ```

### 4.4 `POST /api/restaurants` — create a restaurant

- **Handler**: `RestaurantController.create(Jwt jwt, CreateRestaurantRequest req)`.
- **AuthZ**: `hasRole('RESTAURANT_OWNER')` only.
- **Request body** (`CreateRestaurantRequest`):

  ```json
  {
    "name": "Saravana Bhavan",
    "description": "Iconic South Indian vegetarian chain.",
    "cuisine": "South Indian",
    "address": "44, Thaiyur Main Road, Kelambakkam",
    "city": "Chennai",
    "latitude": 12.7956,
    "longitude": 80.2206
  }
  ```

  The `ownerId` is **not** in the request body — it is the JWT subject. The controller calls `service.create(UUID.fromString(jwt.getSubject()), r)`.

- **Response 201**: the new `Restaurant` with a generated `id`.
- **Side effects**:
  1. Insert a row in `restaurants`.
  2. Insert a row in `outbox_events` (in the same transaction) — `topic = "samato.restaurant.created"`, `payload` is Avro `RestaurantCreatedEvent` bytes.
  3. The cache for this id is evicted (effectively a no-op for a brand-new id).
  4. Within ~1 second, the `Outbox` poller picks the row up and publishes to Kafka.
- **Example curl**:

  ```bash
  curl -X POST http://localhost:8080/api/restaurants \
       -H "Authorization: Bearer $OWNER_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{
             "name":"Saravana Bhavan",
             "description":"Iconic South Indian vegetarian chain.",
             "cuisine":"South Indian",
             "address":"44, Thaiyur Main Road, Kelambakkam",
             "city":"Chennai",
             "latitude":12.7956,
             "longitude":80.2206
           }'
  ```

### 4.5 `PUT /api/restaurants/{id}` — update a restaurant

- **Handler**: `RestaurantController.update(UUID id, UpdateRestaurantRequest req)`.
- **AuthZ**: `hasRole('ADMIN') or @restaurantOwnership.isOwner(#id, authentication)`. That is, admin OR the user who created this restaurant.
- **Request body** (`UpdateRestaurantRequest`): all fields optional; `null`/missing means "do not change".

  ```json
  { "cuisine": "South Indian Veg", "city": "Bangalore" }
  ```

- **Response 200**: the updated `Restaurant`.
- **Side effects**:
  1. Optimistic-locking check via `@Version` — if the row was changed by another writer, Hibernate throws `OptimisticLockException` and the transaction rolls back.
  2. Insert a row in `outbox_events` with `topic = "samato.restaurant.updated"`.
  3. Evict the cache entry for this id.
- **Example curl**:

  ```bash
  curl -X PUT http://localhost:8080/api/restaurants/3fa85f64-5717-4562-b3fc-2c963f66afa6 \
       -H "Authorization: Bearer $OWNER_OR_ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{ "cuisine": "South Indian Veg", "city": "Bangalore" }'
  ```

### 4.6 `DELETE /api/restaurants/{id}` — deactivate

- **Handler**: `RestaurantController.deactivate(UUID id)` -> `RestaurantService.deactivate(UUID id)`.
- **AuthZ**: `hasRole('ADMIN') or @restaurantOwnership.isOwner(...)`.
- **Behavior**: **soft delete** — sets `active = false`. The row is **not** removed from the table. `findByCityIgnoreCaseAndActiveTrue` filters it out from browse, but orders referencing the restaurant still resolve. This is the standard pattern: orders are immutable history; restaurants should not vanish out from under them.
- **Response**: 204 No Content.
- **Side effects**:
  1. Update the row (`active = false`).
  2. Insert a row in `outbox_events` with `topic = "samato.restaurant.updated"` (note: same topic as a real update; the consumer doesn't care that this is a deactivation, it just re-projects the latest snapshot).
  3. Evict the cache entry.
- **Example curl**:

  ```bash
  curl -X DELETE http://localhost:8080/api/restaurants/3fa85f64-5717-4562-b3fc-2c963f66afa6 \
       -H "Authorization: Bearer $OWNER_OR_ADMIN_TOKEN" \
       -i
  ```

### 4.7 `POST /api/restaurants/{id}/menu` — add a menu item

- **Handler**: `RestaurantController.addMenuItem(UUID id, CreateMenuItemRequest req)`.
- **AuthZ**: `hasRole('ADMIN') or @restaurantOwnership.isOwner(...)`.
- **Request body** (`CreateMenuItemRequest`):

  ```json
  {
    "name": "Masala Dosa",
    "description": "Crispy dosa filled with spiced potato.",
    "price": 120.00
  }
  ```

  `price` must be `>= 0.00` (enforced by `@DecimalMin("0.00")`). The `id` and `restaurantId` are filled in by the service.

- **Response 201**: the new `MenuItem`.
- **Side effects**:
  1. Validates the restaurant exists (`existsById`).
  2. Insert a row in `menu_items` (`restaurant_id` is the path variable).
  3. **No outbox event** for menu-item changes in the current implementation. (This is a known gap: search-service does not get a menu change event. If you need search to reflect menu changes, you would add a `MenuItemAddedEvent` and a corresponding Avro schema.)
- **Example curl**:

  ```bash
  curl -X POST http://localhost:8080/api/restaurants/3fa85f64-5717-4562-b3fc-2c963f66afa6/menu \
       -H "Authorization: Bearer $OWNER_OR_ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{ "name":"Masala Dosa", "description":"Crispy dosa with spiced potato.", "price":120.00 }'
  ```

### 4.8 Actuator endpoints (per `SecurityConfig`)

- `GET /actuator/health`, `GET /actuator/health/**`, `GET /actuator/info` — `permitAll`.
- `GET /actuator/prometheus` — used by Prometheus scraping. Requires a valid JWT (any `authenticated` user) per the default `anyRequest().authenticated()` rule. In practice the Prometheus scraper reaches the gateway and the gateway's JWT filter would reject it — operators usually either bypass via the gateway or add the prometheus target on a private network.
- `GET /actuator/metrics`, `GET /actuator/metrics/**` — same as `prometheus`.

## 5. Database schema

There is exactly one migration file: `services/restaurant-service/src/main/resources/db/migration/V1__init.sql`. Flyway runs it at startup, before Hibernate validates.

### 5.1 `restaurants`

| Column | Type | Constraint | Notes |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | Generated by `@GeneratedValue` |
| `owner_id` | UUID | NOT NULL | Logical FK to `auth-service`; no DB FK enforced |
| `name` | VARCHAR(200) | NOT NULL | |
| `description` | VARCHAR(2000) | NULL | |
| `cuisine` | VARCHAR(50) | NOT NULL | |
| `address` | VARCHAR(500) | NOT NULL | |
| `city` | VARCHAR(100) | NOT NULL | |
| `latitude` | DOUBLE PRECISION | NOT NULL | |
| `longitude` | DOUBLE PRECISION | NOT NULL | |
| `active` | BOOLEAN | NOT NULL DEFAULT TRUE | Soft-delete flag |
| `created_at` | TIMESTAMP | NOT NULL | Set in `@PrePersist` |
| `updated_at` | TIMESTAMP | NOT NULL | Set in `@PrePersist` and `@PreUpdate` |
| `version` | BIGINT | NOT NULL DEFAULT 0 | `@Version` (JPA optimistic lock) |

Indexes:
- `idx_restaurants_city` — `CREATE INDEX ... ON restaurants(city) WHERE active = TRUE;` (partial index, only scans active rows). The JPA `@Index` declaration names the column `city` without the `WHERE` clause — Hibernate's validation is loose about partial-WHERE syntax, so this is fine.
- `idx_restaurants_owner` — `CREATE INDEX ... ON restaurants(owner_id);` (non-partial; used by `findByOwnerId`).

Reads: `RestaurantRepository.findById`, `findByCityIgnoreCaseAndActiveTrue`, `findByOwnerId`. Indirectly by `RestaurantOwnership.isOwner` via `findById`.

Writes: `RestaurantRepository.save` (insert on `create`, update on `update`/`deactivate`).

### 5.2 `menu_items`

| Column | Type | Constraint | Notes |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | |
| `restaurant_id` | UUID | NOT NULL, REFERENCES `restaurants(id) ON DELETE CASCADE` | A real DB FK with `ON DELETE CASCADE` — unlike the `owner_id` column, this FK is enforced |
| `name` | VARCHAR(200) | NOT NULL | |
| `description` | VARCHAR(1000) | NULL | |
| `price` | NUMERIC(10,2) | NOT NULL | `BigDecimal` in Java |
| `available` | BOOLEAN | NOT NULL DEFAULT TRUE | |
| `created_at` | TIMESTAMP | NOT NULL | |
| `updated_at` | TIMESTAMP | NOT NULL | |

Index:
- `idx_menu_items_restaurant` — `CREATE INDEX ... ON menu_items(restaurant_id);` — backs `MenuItemRepository.findByRestaurantId`.

Reads: `MenuItemRepository.findByRestaurantId` (used by `GET /api/restaurants/{id}/menu`).

Writes: `MenuItemRepository.save` (used by `POST /api/restaurants/{id}/menu`).

Caveat: the FK has `ON DELETE CASCADE`, but the application uses soft delete (sets `active = false` on the restaurant) rather than `DELETE FROM restaurants`. So the cascade never fires in normal operation. It is there as a safety net for direct DB deletes (e.g. test cleanup).

### 5.3 `outbox_events`

| Column | Type | Constraint | Notes |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | |
| `aggregate_type` | VARCHAR(100) | NOT NULL | Always `"Restaurant"` for this service |
| `aggregate_id` | UUID | NOT NULL | The restaurant id |
| `topic` | VARCHAR(100) | NOT NULL | `samato.restaurant.created` or `samato.restaurant.updated` |
| `event_type` | VARCHAR(200) | NOT NULL | `com.samato.events.RestaurantCreatedEvent` or `com.samato.events.RestaurantUpdatedEvent` — the lookup key for `AvroBytes.decode` |
| `payload` | BYTEA | NOT NULL | Avro DataFile bytes via `AvroBytes.encode` |
| `sent_at` | TIMESTAMP | NULL | NULL = unsent, eligible to be polled |
| `created_at` | TIMESTAMP | NOT NULL | |

Index:
- `idx_outbox_unsent` — `CREATE INDEX ... ON outbox_events(created_at) WHERE sent_at IS NULL;` (partial index). This is the index the poller's `WHERE sent_at IS NULL ORDER BY created_at ASC LIMIT 100` query uses. The JPA `@Index` declaration in `OutboxEvent.java` does not capture the partial-WHERE clause — see §7 for the gotcha.

Writes: `Outbox.enqueue(...)` (in the same transaction as the restaurant business write).

Reads: `Outbox.publishPending` (every 1 second).

Updates: `Outbox.publishPending` sets `sent_at = now()` after a successful send.

For a deeper discussion of the outbox table design and the polling-based publish loop, see the shared-and-kafka doc once it's written — that file will cover the cross-service outbox pattern.

## 6. Kafka integration

This service is a Kafka **producer** only. It has zero `@KafkaListener` methods.

### 6.1 Topics published

| Topic | Trigger | Event class | Key |
|---|---|---|---|
| `samato.restaurant.created` | `RestaurantService.create(...)` | `com.samato.events.RestaurantCreatedEvent` | `restaurantId` (UUID string) |
| `samato.restaurant.updated` | `RestaurantService.update(...)` and `RestaurantService.deactivate(...)` | `com.samato.events.RestaurantUpdatedEvent` | `restaurantId` (UUID string) |

The partition key is always the restaurant id, so all events for one restaurant land on the same partition — important for ordering guarantees in `search-service`'s projection.

#### RestaurantCreatedEvent (Avro schema)

```
record com.samato.events.RestaurantCreatedEvent:
  eventId      string
  occurredAt   long    // epoch millis
  restaurantId string
  ownerId      string
  name         string
  description  union { null, string }  default = null
  cuisine      string
  address      string
  city         string
  latitude     double
  longitude    double
```

The generated Java class lives at `shared-kafka/target/generated-sources/avro/com/samato/events/RestaurantCreatedEvent.java` (produced by the `avro-maven-plugin` from `shared-kafka/src/main/avro/RestaurantCreatedEvent.avsc`).

#### RestaurantUpdatedEvent (Avro schema)

```
record com.samato.events.RestaurantUpdatedEvent:
  eventId      string
  occurredAt   long    // epoch millis
  restaurantId string
  name         string
  description  union { null, string }  default = null
  cuisine      string
  address      string
  city         string
  latitude     double
  longitude    double
```

Note: `RestaurantUpdatedEvent` does **not** carry `ownerId` (the owner's id never changes for an existing restaurant). It also does not carry `active` — consumers that care about active state should re-fetch from the service or rely on the next `RestaurantUpdatedEvent` after a `deactivate`.

#### Producer wiring

1. `RestaurantService.create/update/deactivate` -> `Outbox.enqueueRestaurantCreated/Updated` (within the same `@Transactional`).
2. `Outbox.enqueue*` builds the Avro record via `RestaurantCreatedEvent.newBuilder()...build()` and `AvroBytes.encode(event) -> byte[]`.
3. `Outbox.enqueue` saves a row to `outbox_events` (still in the same transaction).
4. The Spring scheduler invokes `Outbox.publishPending()` every 1 second (`@Scheduled(fixedDelay = 1000L)`).
5. `publishPending` calls `outboxRepo.findUnsent()` (returns up to 100 rows ordered by `createdAt ASC`).
6. For each row: `AvroBytes.decode(eventType, payload) -> SpecificRecord`, then `kafkaTemplate.send(new ProducerRecord<>(topic, aggregateId.toString(), decoded)).get(5, SECONDS)`. The `.get(5, SECONDS)` blocks the poll thread until the broker acks (or 5 seconds elapse) — this is deliberate, per the class javadoc.
7. On success, `e.setSentAt(Instant.now()); outboxRepo.save(e);`. The whole loop runs inside a `@Transactional` method.
8. On failure, the catch block logs at WARN and leaves the row unsent. The next poll retries.

The `KafkaTemplate<String, SpecificRecord>` bean comes from `shared-kafka`'s `KafkaProducerConfig.kafkaTemplate(...)` — see [shared-and-kafka](./shared-and-kafka.md#kafkaproducerconfig). It is wired with:
- `KafkaAvroSerializer` against the Schema Registry URL from `spring.kafka.properties.schema.registry.url`.
- `KafkaMdcProducerInterceptor` (also from `shared-kafka`) which propagates the MDC `traceId`, `spanId`, `correlationId` to Kafka record headers — see [shared-and-kafka](./shared-and-kafka.md#kafkamdcproducerinterceptor).

The `acks: all` and `enable.idempotence: true` settings from `application.yml` are picked up by the same `KafkaProducerConfig` and guarantee no duplicates on the producer side.

### 6.2 Topics consumed

None. This service has no `@KafkaListener` methods. (The `spring.kafka.consumer.*` block in `application.yml` is configured but unused — leftover from scaffolding; the consumer-group-id `samato-restaurant-service` is never actually subscribed to anything.)

### 6.3 Outbox table details

Already covered in §5.3. The poll loop is:

- **Class**: `com.samato.restaurantservice.service.Outbox#publishPending`.
- **Interval**: `@Scheduled(fixedDelay = 1000L)` — 1 second between end-of-previous-run and start-of-next.
- **Batch size**: `LIMIT 100` (hard-coded in the JPQL query).
- **Send**: synchronous with a 5-second timeout.
- **Marker field**: `sent_at` — `NULL` means unsent (eligible to be polled); the partial index `idx_outbox_unsent` only covers rows where `sent_at IS NULL`.
- **Idempotency at the consumer side**: every event has a unique `eventId` (UUID generated in `Outbox.enqueueRestaurantCreated/Updated`). Consumers like `search-service` use this for dedup — see the search-service guide when it's written.

## 7. Common "if you change X, also update Y" notes

These are the non-obvious cross-file dependencies a beginner will trip over.

1. **Adding a field to `Restaurant`?** You need to update FOUR things in lockstep:
   - The `Restaurant` entity (Java field + getter + setter).
   - The `restaurants` table — add a new migration `V2__add_<column>.sql` (do NOT edit `V1__init.sql`; Flyway has already recorded it as applied).
   - The `CreateRestaurantRequest` AND `UpdateRestaurantRequest` records in `RestaurantController` if the field should be settable from the API.
   - The `RestaurantCreatedEvent` AND `RestaurantUpdatedEvent` Avro schemas if downstream consumers need to know about the new field. After updating the `.avsc` files, the `avro-maven-plugin` regenerates the Java classes — re-run `mvn -pl shared-kafka install` so the regenerated classes are available. Both `RestaurantCreatedEvent` and `RestaurantUpdatedEvent` in `Outbox.enqueue*` must be updated to set the new field.

2. **Adding a new event type?** Update five things:
   - Add a new `.avsc` file in `shared-kafka/src/main/avro/`.
   - Add the FQN -> Class mapping in `AvroBytes.REGISTRY`.
   - Add a new `TOPIC_*` constant in `Outbox`.
   - Add a new `enqueue*` method in `Outbox` (or generalize the existing one).
   - Add a new `outbox_events` row in `RestaurantService.<your new write method>`.
   - Decide whether the cache eviction needs to be added to the service method too.

3. **Renaming an Avro class or its FQN?** Update four things:
   - The `.avsc` file.
   - The `AvroBytes.REGISTRY` map.
   - The `eventType` string passed to `Outbox.enqueue(...)` (must match the FQN of the new class).
   - The `search-service` `RestaurantEventListener` (it has its own copy of the FQN strings or uses `SpecificRecord` subtypes). If you change a schema, also follow the Confluent compatibility policy (backward-compatible changes work; breaking changes need a V2 schema).

4. **Adding a menu-item field?** Currently no event is published for menu-item changes. The minimum touch points are: `MenuItem` entity + `menu_items` table (via a new migration) + the `CreateMenuItemRequest` DTO. If you want search to reflect menu changes, you also need to add a new Avro schema and a new outbox event (see #2).

5. **The `OutboxEvent` `@Index` annotation vs. the partial index in the migration.** The JPA `@Index(name = "idx_outbox_unsent", columnList = "sent_at")` does NOT capture the `WHERE sent_at IS NULL` partial predicate. Hibernate's `ddl-auto: validate` is loose enough to accept this, but if you ever switch to `ddl-auto: update` (don't), the partial predicate would be lost. The migration is the source of truth. The JPA annotation is purely advisory here.

6. **`@Cacheable(key = "#id")` works because `id` is a `UUID`.** The default key serializer for the cache is `StringRedisSerializer`, which calls `toString()`. If you change the key to be, say, a `String` directly, the cache key shape changes too. The cache key for a restaurant with id `3fa85f64-...` is `restaurants::3fa85f64-...` in Redis (the cache name is prepended by `prefixCacheNameWith("samato:cache:")`).

7. **The `version` column for optimistic locking is required.** If you add a new entity that needs concurrency control, mirror the `@Version` pattern. If you try to delete the `version` column from `restaurants` in a new migration, the entity will fail to start because Hibernate validates the column exists.

8. **The `outbox_events.payload` column must stay `BYTEA`.** Don't add `@Lob` to the `payload` field in `OutboxEvent` — Hibernate 6 + Postgres will switch to OID storage and break reads. The class javadoc and `docs/INTERVIEW-NOTES.md` both call this out as a bring-up bug that was fixed by the `columnDefinition = "BYTEA"` override.

9. **JWT subject must be a UUID.** `RestaurantController.create` does `UUID.fromString(jwt.getSubject())` and `RestaurantOwnership.isOwner` also does `UUID.fromString(auth.getName())`. If the auth-service ever changes the subject claim to a non-UUID (e.g. an email), every write to a restaurant will throw `IllegalArgumentException` and the owner check will return `false` (the catch swallows the exception). Today this works because `auth-service`'s `RegisterRequest` validates `email` but stores the user with a UUID id and the JWT subject is set to that UUID.

10. **`order-service` has a Feign client (`RestaurantClient`) that calls this service.** If you add, rename, or remove a path under `/api/restaurants`, update `RestaurantClient` in `services/order-service` and its `RestaurantClientFallback`. The fallback's signature must match the interface or `place-an-order` will break.

11. **Eureka name is upper-cased.** The service registers as `SAMATO-RESTAURANT-SERVICE` (Spring Cloud upper-cases the application name). Anywhere you reference the service by Eureka name (e.g. OpenFeign `name = "samato-restaurant-service"`), the lookup is case-insensitive, but if you query Eureka directly you'll see the upper-case form.

12. **`@EnableMethodSecurity` without `prePostEnabled` defaults.** `prePostEnabled = true` is the default in modern Spring Security, so `@PreAuthorize` works without further config. If you see `@PreAuthorize` being silently ignored, check that `SecurityConfig` is being picked up (i.e. it's in the component-scan range — it is, via the `com.samato.restaurantservice` scan in the main class).

13. **Cache eviction is on `#result.id` for `create` but on `#id` for update/deactivate.** On `create` the id is generated by the DB, so it's only known after the method returns. On update/deactivate the id is already a path variable. Don't copy-paste blindly.

14. **`@Transactional(readOnly = true)` on read methods.** This is a Hibernate optimization, not a security boundary. It tells Hibernate that no flush is needed at the end of the transaction. The read methods do NOT need to be in a transaction to query the DB (Spring Data handles that), but having the read-only transaction lets you put multiple repository calls into a single Hibernate session if you ever need to.

15. **Graceful shutdown and the outbox poller.** The poller holds the application context alive. With `server.shutdown: graceful`, in-flight HTTP requests get a chance to finish, but `@Scheduled` tasks are also shut down cleanly. The poller will not start a new batch after the shutdown signal — any rows that were already started will finish within the 5-second `kafkaTemplate.send(...).get(5, SECONDS)` timeout. If you have a very long-running outbox batch, increase the graceful-shutdown grace period (the Spring Boot default is 30 seconds).

## 8. See also

- **Per-service designer note (interview Q&A)**: [../../services/restaurant-service/docs/INTERVIEW-NOTES.md](../../services/restaurant-service/docs/INTERVIEW-NOTES.md) — the source of the "patterns demonstrated" table and the deep Q&A. **This doc does not replace it; it links to it.**
- **Shared modules, error handling, observability, Kafka producers/consumers in depth**: [./shared-and-kafka.md](./shared-and-kafka.md) — `DomainException`, `GlobalExceptionHandler`, `CorrelationIdFilter`, `MdcKeys`, `KafkaMdcProducerInterceptor`, `MdcContext`, the Avro schema generation pipeline, the `KafkaTemplate` bean, and the Avro class `RestaurantCreatedEvent` / `RestaurantUpdatedEvent` themselves.
- **Use case: place an order** (saga calls `RestaurantClient` here): [../use-cases/01-place-an-order.md](../use-cases/01-place-an-order.md).
- **Use case: how auth works** (JWT roles, JWKS): [../use-cases/02-auth-flow.md](../use-cases/02-auth-flow.md) — explains why the `hasRole('RESTAURANT_OWNER')` annotation in this controller works.
- **Use case: browse and search** (the read side of the catalog): [../use-cases/03-browse-and-search.md](../use-cases/03-browse-and-search.md) — explains how the cache here plus the Kafka events to `search-service` give the user a fast browse experience.
- **Use case: refund flow** (not directly related to this service, but for completeness): [../use-cases/04-refund-flow.md](../use-cases/04-refund-flow.md).
- **Architecture guide** (system-wide diagrams and patterns): [../01-architecture-guide.md](../01-architecture-guide.md).
- **How auth works** (JWT, JWKS, RBAC): [../02-how-auth-works.md](../02-how-auth-works.md).
- **Glossary** (Spring and microservice term definitions): [../00-glossary.md](../00-glossary.md).
- **Repo-level architecture**: [../../ARCHITECTURE.md](../../ARCHITECTURE.md).
- **Repo-level status / what's running**: [../../PROJECT-STATUS.md](../../PROJECT-STATUS.md).
- **How to run the whole stack**: [../../RUN-THE-BIBLE.md](../../RUN-THE-BIBLE.md).
- **Interview cheatsheet (cross-service)**: [../INTERVIEW-CHEATSHEET.md](../INTERVIEW-CHEATSHEET.md).

---

### Documented anomalies (for the doc-team review)

These are the small things the inventory and source review surfaced that a careful reader will want to know about:

1. **No `@KafkaListener` here, but `spring.kafka.consumer.*` is configured in `application.yml`.** The consumer group id `samato-restaurant-service` is set, the `auto-offset-reset` and `specific.avro.reader` are set, but no class subscribes to any topic. The settings are inert. If/when this service needs to consume (e.g. to react to an upstream event), the listener container factory is already provided by `shared-kafka`'s `KafkaConsumerConfig` — no extra wiring needed beyond adding the `@KafkaListener` method.

2. **`OutboxEvent` payload column annotation vs. migration.** The JPA `@Index(name = "idx_outbox_unsent", columnList = "sent_at")` does not capture the partial-WHERE predicate; the migration is the source of truth. `ddl-auto: validate` is permissive about this.

3. **`RestaurantService.create` does `@CacheEvict(key = "#result.id")` but the cached entry never existed.** A brand-new id is being evicted from a cache that doesn't have it yet. This is harmless and intentional — it makes the code pattern uniform across all three write methods.

4. **No menu-item events published.** `addMenuItem` does not enqueue an outbox event. The menu is therefore not reflected in `search-service` until a restaurant-level update is triggered. This is a known gap in the current implementation.

5. **`@PreAuthorize` on the three browse endpoints uses `isAuthenticated()`, not a specific role.** Any logged-in user (CUSTOMER, RESTAURANT_OWNER, ADMIN, DRIVER) can read all restaurant and menu data. If the product ever needs to hide drafts from non-owners, the authZ model will need to change.

6. **`RestaurantOwnership` does an extra DB read on every authorized write.** The class javadoc acknowledges this and suggests a short-TTL Caffeine cache as a follow-up.

7. **The outbox poller's `@Transactional` method has no retry-with-backoff on the catch path.** A continuously-failing event will be retried every second forever, with no exponential backoff. The class javadoc notes this as a "real system" improvement (poison-message table after N failures).

8. **`RestaurantUpdatedEvent` does not carry `ownerId` or `active`.** If a future consumer needs to react to a deactivation specifically, it will have to compare the new `active` to the previous one (or fetch the current state from the service). For now the only consumer (`search-service`) doesn't differentiate update from deactivate.

9. **The `version` column on `Restaurant` is required for Hibernate to do optimistic locking, but the `update` API does not surface an `If-Match` header or 409-on-conflict behavior.** If two concurrent updates hit, one of them gets an unhandled `OptimisticLockException` and a 500. This is a real-but-unaddressed corner case in the current implementation.

10. **Resilience4j is on the classpath but unused.** `resilience4j-spring-boot3` is in `pom.xml`, but no `@CircuitBreaker` / `@Retry` annotations exist in this service, and no `resilience4j.*` block exists in `application.yml`. The library is dead weight here. The cross-service inventory explicitly flagged this: "Library is on the classpath; policies appear not yet wired."
