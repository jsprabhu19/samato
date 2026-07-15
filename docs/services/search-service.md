# search-service (port 8087)

> Plain-English purpose: search-service is the **read side of CQRS for restaurants**. It owns the OpenSearch index that holds denormalised restaurant documents, keeps that index in sync with `restaurant-service` by consuming Kafka events (`samato.restaurant.created` and `samato.restaurant.updated`), and exposes a single `GET /api/search/restaurants` endpoint that lets any authenticated user run full-text, cuisine/city-filtered, and "near me" geo-distance queries. Design patterns it demonstrates: **event-driven projection** (the service never talks to restaurant-service directly — Kafka is the only inbound data path), **CQRS read model** (different storage, different query language — Postgres for write-side integrity, OpenSearch for fuzzy search), **idempotent consumer** (events may be redelivered, but the index write is keyed by `restaurantId` so re-delivery is a no-op upsert), and **schema-on-read** (the index is created with a mapping at startup, but the projector writes a flat `Map<String,Object>` so the code does not break when new fields appear).

## 1. Where it sits in the system

```
                 restaurant-service
                 (write side, Postgres)
                        |
                        |  writes restaurant rows
                        |  outbox row -> Avro bytes
                        v
                   +------------+                 +------------------+
                   |   Kafka    |  -- events -->  |  search-service  |
                   | topics:    |                 |  (this service)  |
                   | samato.    |                 |                  |
                   | restaurant.|                 |  consumes +      |
                   | created    |                 |  projects into   |
                   | samato.    |                 |  OpenSearch      |
                   | restaurant.|                 |                  |
                   | updated    |                 |  exposes:        |
                   +------------+                 |  GET /api/search |
                                                |  /restaurants    |
                                                +------------------+
                                                       ^
                                                       |  user query
                                                       |  (via api-gateway)
                                                       |
                                                +------------------+
                                                |  Browser / app   |
                                                +------------------+

No outbound calls. search-service is a one-way consumer + one-way query
endpoint. It owns no database other than the OpenSearch index.
```

Upstream callers (one direction only): `restaurant-service` indirectly (via Kafka).
Downstream callers: nothing — search-service calls no other service.
API callers: the `api-gateway` route `samato-search-service` (see `api-gateway` gateway config) plus any browser or mobile app that ends up routed there.

## 2. Quick reference

| Property | Value |
|---|---|
| Maven module | `services/search-service` |
| Port | `8087` |
| Database(s) | none (uses OpenSearch, defined in compose as `opensearch:9200`); an empty `search_service` database is created in `init-databases.sh` for parity but is unused by the service today |
| Publishes topics | none — search-service is a pure consumer |
| Consumes topics | `samato.restaurant.created`, `samato.restaurant.updated` (consumer group `samato-search-service`, manual ack) |
| REST endpoints | `GET /api/search/restaurants` |
| Depends on | `restaurant-service` (only via Kafka events), `auth-service` (JWT public keys via JWKS), `discovery-service` (Eureka registration), `kafka` (broker), `schema-registry` (Avro deserialisation), `opensearch` (index), `shared` (cross-cutting utilities), `shared-kafka` (KafkaTemplate/Avro wiring, MDC context) |

## 3. File-by-file walkthrough

This service is small (7 Java files). Read them in this order — the annotations on each one tell a small story.

### 3.1 `SearchServiceApplication.java` (package `com.samato.searchservice`)

- **What it is.** The standard Spring Boot entry point. The `main` method delegates to `SpringApplication.run(...)` to start the embedded Tomcat, build the application context, register beans, and start the Kafka consumer thread.
- **Why it exists.** Every Spring Boot service needs a launcher, and the four class-level annotations here are how this service opts into the Samato microservice stack.
- **Spring annotations.**
  - `@SpringBootApplication` — a meta-annotation combining `@Configuration`, `@EnableAutoConfiguration`, and `@ComponentScan`. Marks this class as the configuration root.
  - `@EnableDiscoveryClient` — registers the service with the Eureka server (configured in `application.yml` at `eureka.client.service-url.defaultZone`). After startup, `discovery-service` will list it under the name `samato-search-service`.
  - `@EnableKafka` — turns on Spring's `KafkaListener` support. Without this, the `@KafkaListener(...)` on `RestaurantEventListener` is silently ignored.
  - `@ComponentScan(basePackages = {"com.samato.searchservice","com.samato.shared","com.samato.sharedkafka"})` — tells Spring to look for components in three packages: this service plus the two shared modules. The shared scan is what makes `GlobalExceptionHandler`, `CorrelationIdFilter`, `KafkaMdcProducerInterceptor`, and so on, available without per-import configuration.
- **What it calls.** Nothing directly — it just kicks off the framework.
- **What calls it.** The JVM via `java -jar` (or the Maven `spring-boot:run` plugin, or the Docker entrypoint defined by `services/search-service/Dockerfile`).
- **Configuration keys.** Reads indirectly — every key in `application.yml` becomes available through this application's `Environment`.

### 3.2 `config/OpenSearchConfig.java`

- **What it is.** A Spring `@Configuration` class that builds a single bean: the OpenSearch high-level REST client.
- **Why it exists.** The OpenSearch client is expensive to construct (it opens a connection pool) and needs explicit teardown. Wrapping it in a `@Bean(destroyMethod = "close")` lets Spring manage the lifecycle: create on context start, call `close()` on context stop. The host and port are read from `application.yml` with sensible local-dev defaults (`localhost:9200`).
- **Spring annotations.**
  - `@Configuration` — marks the class as a source of `@Bean` definitions. Spring will scan it and add the returned objects to the application context.
  - `@Bean(destroyMethod = "close")` — registers `openSearchClient()` as a bean and tells Spring to call `close()` on it when the context shuts down. `RestHighLevelClient.close()` shuts down the underlying HTTP client.
  - `@Value("${samato.opensearch.host:localhost}")` and `@Value("${samato.opensearch.port:9200}")` — inject configuration values from `application.yml`, with defaults so the bean works in dev without an external OpenSearch.
- **What it calls.** The OpenSearch SDK (`org.opensearch.client.RestHighLevelClient` and `RestClient.builder`). No other service.
- **What calls it.** Spring instantiates the bean lazily on first request (or eagerly at startup if another bean is `@Autowired`). Both `OpenSearchIndexInitializer` and `SearchController` and `RestaurantProjector` receive this client via constructor injection.
- **Configuration keys.** `samato.opensearch.host` (default `localhost`), `samato.opensearch.port` (default `9200`). In Docker compose, these are overridden by `SAMATO_OPENSEARCH_HOST=opensearch` and `SAMATO_OPENSEARCH_PORT=9200`.

### 3.3 `config/OpenSearchIndexInitializer.java`

- **What it is.** A startup hook that creates the `restaurants` OpenSearch index (with its mapping) if it does not already exist.
- **Why it exists.** OpenSearch indexes are schemaless, but a *good* mapping (full-text vs keyword vs geo_point) is not free. The initializer bakes the mapping into the deployable so a fresh dev environment is one `docker compose up` away from working. The `@EventListener(ApplicationReadyEvent.class)` runs *after* the embedded server is up — so `/actuator/health` reports healthy even if OpenSearch is briefly unreachable, and a failed index creation does not crash startup.
- **Spring annotations.**
  - `@Component` — registers this class as a Spring bean so its `@EventListener` method is discovered.
  - `@EventListener(ApplicationReadyEvent.class)` — Spring publishes `ApplicationReadyEvent` after the context is fully refreshed and the embedded server accepts connections. The annotated method (`ensureIndex`) is invoked once at that point.
- **What it calls.** `RestHighLevelClient.indices().exists(...)` and `RestHighLevelClient.indices().create(...)`. Both wrapped in `try/catch` so a flaky OpenSearch never breaks startup.
- **What calls it.** Spring's `ApplicationEventMulticaster` publishes `ApplicationReadyEvent` exactly once per context, at the end of startup.
- **Configuration keys.** None. The index name `restaurants` is a public constant (`INDEX`) so other classes (`RestaurantProjector`, `SearchController`) use the same constant.
- **Index mapping it creates.**
  - `id` — `keyword` (exact match, used as the document `_id`)
  - `name` — `text` (full-text) plus `name.raw` `keyword` sub-field (for exact match / aggregations)
  - `description` — `text` (full-text)
  - `cuisine` — `keyword` (filter)
  - `city` — `keyword` (filter)
  - `address` — `text` (full-text)
  - `location` — `geo_point` (geo-distance queries)
  - `createdAt`, `updatedAt` — `date` with `format: epoch_millis`

### 3.4 `config/SecurityConfig.java`

- **What it is.** A standard OAuth2-resource-server config: stateless, JWT-validated, with `/actuator/health/**` and `/actuator/info` whitelisted for probes.
- **Why it exists.** `search-service` is exposed through the api-gateway under `samato-search-service`, so the gateway is the front door for user-facing traffic. But Kubernetes probes and a couple of operational endpoints (`/actuator/health`, `/actuator/info`) need to bypass authentication, and Spring Security's default form login would 302 users to a login page. This config disables CSRF (no browser sessions), sets session policy to stateless, and wires the JWT decoder against the auth-service JWKS endpoint.
- **Spring annotations.**
  - `@Configuration` — bean source.
  - `@Bean` on `filterChain` — Spring Security uses the first (or only) `SecurityFilterChain` bean as the default chain. The lambda inside `authorizeHttpRequests` defines the rules.
  - `@Bean` on `jwtDecoder` — returns the JWT decoder that the resource-server config uses to verify bearer tokens.
- **What it calls.** `NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()` from `spring-security-oauth2-jose`. The JWKS endpoint is the one served by `auth-service` at `/.well-known/jwks.json`.
- **What calls it.** Spring Boot's auto-configuration for `spring-boot-starter-oauth2-resource-server` finds the `JwtDecoder` bean and wires it into the resource-server filter. Incoming requests to the controllers are passed through that filter.
- **Configuration keys.** `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (default `http://localhost:9000/.well-known/jwks.json`). In Docker compose, the env var `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://auth-service:9000/.well-known/jwks.json` overrides it.

### 3.5 `projection/RestaurantEventListener.java`

- **What it is.** The Kafka consumer that maintains the OpenSearch projection. It listens to both restaurant topics, hands the deserialised event to `RestaurantProjector`, and acks the offset only after the projection succeeds.
- **Why it exists.** The whole purpose of this service is to keep the read model fresh. Without a consumer, the OpenSearch index would be empty and `GET /api/search/restaurants` would return zero results. The single-listener-for-multiple-topics design is deliberate: both topics feed the same projection, so one consumer thread, one index, one consistent view.
- **Spring annotations.**
  - `@Component` — registers the class as a bean so Spring can find and configure its `@KafkaListener`.
  - `@KafkaListener(topics = {"samato.restaurant.created","samato.restaurant.updated"}, groupId = "samato-search-service")` — binds the `onEvent` method to those two topics under the consumer group `samato-search-service`. The group id determines partition assignment and offset tracking.
- **What it calls.**
  - `RestaurantProjector.apply(SpecificRecord)` — the actual work.
  - `MdcContext.fromKafka(record)` — opens a try-with-resources scope that copies correlation/trace ids from the Kafka record headers into SLF4J's MDC, and clears them on `close()`. See `shared-and-kafka.md` for the wiring.
  - `Acknowledgment.acknowledge()` — manually commits the offset (because `ack-mode: manual_immediate` in `application.yml`).
- **What calls it.** Spring Kafka's listener container (built by `KafkaConsumerConfig.kafkaListenerContainerFactory(...)` in `shared-kafka`) — see `shared-and-kafka.md`.
- **Configuration keys.** `spring.kafka.bootstrap-servers`, `spring.kafka.consumer.group-id` (default `samato-search-service` — also the hard-coded `groupId` on the listener), `spring.kafka.consumer.auto-offset-reset=earliest`, `spring.kafka.listener.ack-mode=manual_immediate`, `spring.kafka.properties.schema.registry.url`, `spring.kafka.properties.specific.avro.reader=true`.
- **Idempotency story.** The `restaurantId` is used as the OpenSearch `_id` in `RestaurantProjector`, so re-delivery of the same event overwrites with the same content — a no-op. The producer (`restaurant-service`) keys its sends by `restaurantId`, so events for a given restaurant always land in the same Kafka partition and arrive in order, which means the latest write always wins.

### 3.6 `projection/RestaurantProjector.java`

- **What it is.** The pure function that turns an Avro `SpecificRecord` into an OpenSearch document and writes it.
- **Why it exists.** Separation of concerns — the listener handles Kafka plumbing, this class handles the storage projection. The `Map<String, Object>`-based document is a deliberate choice: the code does not need to know the exact index mapping, and adding a new event type or a new field is a one-method change. The `if (ev instanceof RestaurantCreatedEvent c)` / `else if (ev instanceof RestaurantUpdatedEvent u)` block is the type switch — Java 21 `switch` patterns could be used but `instanceof` reads more naturally for the two-event case.
- **Spring annotations.**
  - `@Component` — registers the bean so the listener can inject it.
- **What it calls.**
  - `RestHighLevelClient.index(IndexRequest, RequestOptions)` — the actual upsert.
  - `RestaurantCreatedEvent` and `RestaurantUpdatedEvent` — Avro-generated classes from `shared-kafka`. They live in package `com.samato.events` (see `shared-and-kafka.md` for the Avro schema and the codegen plugin).
  - `ObjectMapper` (Jackson) — to serialise the `Map` to JSON for the index source.
- **What calls it.** `RestaurantEventListener.onEvent(...)` — the only caller.
- **Configuration keys.** None. The index name comes from the `INDEX` constant.
- **Note on the `getEventId` workaround.** The listener side reads `eventId` via `((GenericRecord) ev).get("eventId")` because the Avro-generated code does not implement `com.samato.sharedkafka.events.DomainEvent` (a marker interface that no Avro class implements today — see `shared-and-kafka.md` for the documented anomaly). The projector itself does not read `eventId` — the listener logs it before handing off.

### 3.7 `web/SearchController.java`

- **What it is.** The single REST endpoint. `GET /api/search/restaurants` accepts a small set of optional query parameters, builds a bool-query against the OpenSearch index, and returns a JSON `{ total, hits: [...] }` payload.
- **Why it exists.** This is the only reason the service is user-facing. Every other file is plumbing; this one is the product surface.
- **Spring annotations.**
  - `@RestController` — combines `@Controller` and `@ResponseBody`. Methods return JSON bodies (no view resolution).
  - `@RequestMapping("/api/search")` — base path. Combined with the per-method `@GetMapping("/restaurants")`, the full path is `/api/search/restaurants`.
  - `@GetMapping("/restaurants")` — HTTP GET on `/api/search/restaurants`.
  - `@PreAuthorize("isAuthenticated()")` — method-level security: the caller must have a valid JWT (which the SecurityConfig above validates). The expression `isAuthenticated()` is a Spring Security built-in.
  - `@RequestParam` — each query parameter is optional. `size` defaults to `20`.
- **What it calls.**
  - `RestHighLevelClient.search(SearchRequest, RequestOptions)` — the only external call.
  - `QueryBuilders` and `SearchSourceBuilder` (OpenSearch DSL) — to build the bool query.
  - `RestaurantProjector.INDEX` (the public constant) — the index name.
- **What calls it.** External HTTP clients (browser, app, Postman) via the `samato-search-service` route in `api-gateway`. There is no service-to-service caller.
- **Configuration keys.** None directly. The endpoint reads only query parameters.
- **Response shape.** `SearchResponse` is a Java `record` with two fields: `long total` and `List<Map<String, Object>> hits`. Each hit is a flat `Map` whose keys come from the projected document (`id`, `name`, `description`, `cuisine`, `city`, `address`, `location`, `createdAt`, `updatedAt`) plus the synthetic `_score` key.

### 3.8 `application.yml` (file by file)

Every key, what it does, and what reads it.

| Key | Value (default) | Read by | Why |
|---|---|---|---|
| `server.port` | `8087` | Spring Boot | The HTTP port. Compose `8087:8087` matches. |
| `server.shutdown` | `graceful` | Spring Boot | Lets in-flight requests finish before the JVM exits. |
| `spring.application.name` | `samato-search-service` | Eureka + logging | Service name registered with Eureka and used in log MDC. |
| `spring.cloud.config.enabled` | `false` | Config client | The config-service is off for local dev; flip to `true` to read from it. |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `http://localhost:9000/.well-known/jwks.json` | `SecurityConfig#jwtDecoder` | Where to fetch the public keys for JWT verification. In Docker, the env var `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://auth-service:9000/.well-known/jwks.json` overrides. |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Spring Kafka | The Kafka broker(s). In Docker, env var `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9094` (note: `9094` is the internal listener). |
| `spring.kafka.properties.schema.registry.url` | `http://localhost:8085` | `KafkaAvroDeserializer` | Confluent Schema Registry. In Docker, env var `SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL=http://schema-registry:8081`. |
| `spring.kafka.consumer.group-id` | `samato-search-service` | Spring Kafka | Consumer group. Also matches the hard-coded `groupId` on the `@KafkaListener`. |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | Spring Kafka | When the group has no committed offset, start from the beginning of each topic. Without this, a brand-new group would skip everything published before it joined. |
| `spring.kafka.consumer.enable-auto-commit` | `false` | Spring Kafka | Disable framework auto-commit so we can ack manually after a successful projection. |
| `spring.kafka.consumer.properties.specific.avro.reader` | `true` | `KafkaAvroDeserializer` | Tells the deserialiser to produce `SpecificRecord` instances (typed accessors) rather than generic ones. |
| `spring.kafka.consumer.properties.max.poll.records` | `50` | Kafka client | Batch size per poll. Pairs with `session-timeout` and `max-poll-interval` in shared-kafka. |
| `spring.kafka.listener.ack-mode` | `manual_immediate` | Spring Kafka | The listener must call `Acknowledgment.acknowledge()`. `immediate` means each successful record acks separately (finer-grained redelivery than `BATCH`). |
| `samato.opensearch.host` | `localhost` | `OpenSearchConfig#openSearchClient` | OpenSearch host. In Docker, `SAMATO_OPENSEARCH_HOST=opensearch`. |
| `samato.opensearch.port` | `9200` | `OpenSearchConfig#openSearchClient` | OpenSearch HTTP port. In Docker, `SAMATO_OPENSEARCH_PORT=9200`. |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | Eureka client | Where to register. In Docker, `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://discovery-service:8761/eureka/`. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Spring Boot Actuator | Which `/actuator/*` endpoints are served. Prometheus scrapes `/actuator/prometheus`. |
| `management.endpoint.health.probes.enabled` | `true` | Spring Boot Actuator | Enables `/actuator/health/liveness` and `/actuator/health/readiness` (used by K8s). |
| `management.tracing.sampling.probability` | `1.0` | Micrometer Tracing | Sample 100% of traces. Reduce in production. |
| `management.zipkin.tracing.endpoint` | `http://localhost:9411/api/v2/spans` | Zipkin reporter | Where to send spans. |
| `logging.pattern.level` | `"%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{correlationId:-}]"` | Logback | Each log line is prefixed with the service name and the current trace/correlation id from MDC. |
| `logging.level.com.samato` | `DEBUG` | Logback | Verbose logging for our own code. |
| `logging.level.org.apache.kafka` | `WARN` | Logback | Tone down Kafka client noise. |

## 4. Endpoints (controllers)

### 4.1 `GET /api/search/restaurants`

| Aspect | Value |
|---|---|
| Controller class | `com.samato.searchservice.web.SearchController` |
| Handler method | `search(...)` |
| Annotation guards | `@PreAuthorize("isAuthenticated()")` — caller must present a valid bearer JWT |
| Public route? | Reached through the `samato-search-service` route in `api-gateway` (the api-gateway adds a JWT-validating filter) |

#### Query parameters (all optional except `size` which has a default)

| Name | Type | Default | Notes |
|---|---|---|---|
| `q` | string | (absent) | Free-text query. Runs `multi_match` against `name` and `description`. |
| `cuisine` | string | (absent) | Exact-match filter on the `cuisine` keyword field. |
| `city` | string | (absent) | Exact-match filter on the `city` keyword field. |
| `lat`, `lon` | double | (absent) | Both must be present together with `radiusKm` to enable geo filtering. |
| `radiusKm` | double | (absent) | Distance radius for the geo filter. |
| `size` | int | `20` | Max number of hits returned. |

If `q` is blank or null, the `must` clause is omitted (the query becomes a pure filter query, which OpenSearch can cache). If `lat`/`lon`/`radiusKm` are incomplete, the geo clause is omitted.

#### Response shape

```json
{
  "total": 42,
  "hits": [
    {
      "id": "9b8e...-uuid",
      "name": "Spicy Noodle House",
      "description": "Hand-pulled noodles and Sichuan peppercorns",
      "cuisine": "Chinese",
      "city": "Bangalore",
      "address": "12 MG Road",
      "location": { "lat": 12.97, "lon": 77.59 },
      "createdAt": 1718000000000,
      "updatedAt": 1718050000000,
      "_score": 5.13
    }
  ]
}
```

- `total` is the exact total hit count (`hits.total.value`).
- `_score` is the OpenSearch relevance score (only meaningful when `q` is set).

#### Example curl

```bash
# Free-text + cuisine + geo
curl -sS -G "http://localhost:8087/api/search/restaurants" \
  -H "Authorization: Bearer $JWT" \
  --data-urlencode "q=spicy noodles" \
  --data-urlencode "cuisine=Chinese" \
  --data-urlencode "lat=12.97" \
  --data-urlencode "lon=77.59" \
  --data-urlencode "radiusKm=5" \
  --data-urlencode "size=10" | jq

# City-only
curl -sS "http://localhost:8087/api/search/restaurants?city=Bangalore" \
  -H "Authorization: Bearer $JWT" | jq
```

If the JWT is missing or invalid, the SecurityConfig's `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` returns 401 directly (no body).

#### Downstream side effects

- The controller calls `osClient.search(SearchRequest, RequestOptions.DEFAULT)`. If the OpenSearch index `restaurants` does not exist yet (very early startup), the OpenSearch client throws a `404 index_not_found_exception` and the controller propagates it as a 500 via Spring Boot's default error path. See §7 for the workaround.
- The endpoint is read-only. It does not publish Kafka events, does not write to any database, and does not call any other microservice.

#### Authorisation

`@PreAuthorize("isAuthenticated()")` — the JWT must be present and verifiable against the JWKS. There is no role check (any authenticated user can search). If you ever need to gate this to certain roles, change the expression to e.g. `hasAnyRole('CUSTOMER','ADMIN')` and update the gateway route's expectations.

## 5. Database schema

This service has **no relational database** and **no Flyway migrations** under `db/migration/`. The inventory confirms `migrations: []` in `search-service.json`. The only persistent state is the OpenSearch index `restaurants` (created at startup by `OpenSearchIndexInitializer` — see §3.3 for the mapping).

The compose file does pre-create an empty `search_service` Postgres database (`init-databases.sh` lists it), but no Java code in this service connects to it. If you ever add a relational cache or a click-stream table, that database is ready.

The "writes" to the `restaurants` index come from one place: `RestaurantProjector.apply(SpecificRecord)` (§3.6). The "reads" come from `SearchController.search(...)` (§3.7). Nothing else touches the index.

## 6. Kafka integration

### 6.1 Topics consumed

| Topic | Schema (Avro) | Consumer method | Consumer group | Ack mode |
|---|---|---|---|---|
| `samato.restaurant.created` | `RestaurantCreatedEvent` (see `shared-and-kafka.md`) | `RestaurantEventListener.onEvent` | `samato-search-service` | `manual_immediate` |
| `samato.restaurant.updated` | `RestaurantUpdatedEvent` (see `shared-and-kafka.md`) | same listener | same | same |

Both topics share one listener method (the listener container assigns the same method to multiple topics — see the `@KafkaListener(topics = {...})` annotation in §3.5). The deserialiser is `KafkaAvroDeserializer` (configured in `shared-kafka` `KafkaConsumerConfig`); `application.yml` sets `specific.avro.reader=true` so the values are `SpecificRecord` instances, and the projector pattern-matches on the concrete generated class.

#### Payload structure (Avro JSON, as deserialised)

```json
{
  "eventId": "evt-9c2e...-uuid",
  "occurredAt": 1718050000000,
  "restaurantId": "9b8e...-uuid",
  "ownerId": "owner-...-uuid",
  "name": "Spicy Noodle House",
  "description": "Hand-pulled noodles and Sichuan peppercorns",
  "cuisine": "Chinese",
  "address": "12 MG Road",
  "city": "Bangalore",
  "latitude": 12.97,
  "longitude": 77.59
}
```

For `RestaurantUpdatedEvent`, the `ownerId` field is absent (the Avro schema does not include it; see `shared-and-kafka.md`).

#### Idempotency / redelivery

- **Per-restaurant ordering.** The producer (`restaurant-service` outbox) keys by `restaurantId`, so events for a given restaurant always land in the same Kafka partition and are processed in publish order.
- **Upsert-by-id.** `RestaurantProjector` uses `restaurantId` as the OpenSearch document `_id`. Re-delivery of the same event is an upsert with the same content, so the index state is unchanged.
- **At-least-once semantics.** If the projection throws *after* the event was consumed but *before* `ack.acknowledge()`, the offset is not committed and Kafka redelivers the record on the next poll. Because the index write is keyed by id, redelivery is safe.

#### MDC propagation

`MdcContext.fromKafka(record)` opens a try-with-resources block that copies `correlationId`, `traceId`, and `spanId` from the Kafka record headers into SLF4J's MDC. Any log line emitted inside the block (including inside the projector) is prefixed with the same correlation id as the original restaurant write. This is what makes a single request traceable end-to-end through the consumer thread.

### 6.2 Topics published

**None.** `search-service` is a pure consumer. There is no `KafkaTemplate` injection and no `@KafkaListener` method that re-publishes.

### 6.3 Outbox table

**None.** Unlike `order-service`, `payment-service`, and `restaurant-service`, this service does not write to an outbox. It has no transactional database at all. The Avro events it consumes come from the upstream outbox in `restaurant-service`.

## 7. Common "if you change X, also update Y" notes

These are the cross-file dependencies a beginner will not see from imports alone. The inventory agent surfaced several; the most relevant for this service are below.

- **If you change the index name (`restaurants`)** in `OpenSearchIndexInitializer.INDEX`, also change `RestaurantProjector.INDEX` and `SearchController.SearchRequest(...)` to use the same constant. Both files already import the constant from `RestaurantProjector`; the initializer's constant is a *separate* `public static final String` — they are not currently shared. The safest move is to delete the initializer's local `INDEX` and import `RestaurantProjector.INDEX` instead.

- **If you add a field to the index mapping** in `OpenSearchIndexInitializer` (the Java text block with `"properties": {...}`), also update `RestaurantProjector.apply(SpecificRecord)` to write the new field. The mapping is read at index-create time only; later field additions to the mapping need a separate OpenSearch `PUT /restaurants/_mapping` call, or a new index alias. Mismatched fields produce a runtime OpenSearch `mapper_parsing_exception`.

- **If you add a new event type** (say `RestaurantDeactivatedEvent`), you must:
  1. Add the `.avsc` to `shared-kafka/src/main/avro/`, regenerate the Java class.
  2. Add a `KafkaTemplate<String,SpecificRecord>.send(...)` call (or outbox row) in `restaurant-service`.
  3. Add the topic name to the `@KafkaListener(topics = {...})` list in `RestaurantEventListener`.
  4. Add a third `else if (ev instanceof RestaurantDeactivatedEvent d)` branch in `RestaurantProjector.apply(...)`.

- **If you change the consumer group id** (`samato-search-service` in either `application.yml` or the `@KafkaListener` annotation), you will start a fresh consumer that has no committed offsets. Combined with `auto-offset-reset: earliest`, the new group will reprocess *all* historical events on the next startup. Combined with `latest`, the new group will skip all historical events. Pick deliberately.

- **If you remove `specific.avro.reader` from `application.yml`**, the deserialiser will produce `GenericRecord` instances and `RestaurantProjector`'s `instanceof` checks will not match the concrete types (or will match but the accessors `getRestaurantId()` etc. will not compile). Restore the property.

- **If you change `ack-mode` away from `manual_immediate`**, the `Acknowledgment` parameter on `onEvent` becomes `null` and `ack.acknowledge()` will NPE. Either keep manual ack, or remove the `Acknowledgment` parameter and let the framework commit.

- **JWT is verified twice.** Once at the api-gateway (in `JwtAuthFilter`) and once here (in `SecurityConfig#jwtDecoder`). Both fetch the same JWKS from auth-service. This is intentional (defence in depth), but it means two network round-trips to auth-service on first request after JWKS cache expiry. If you ever switch to asymmetric JWK caching at the gateway, you can drop the local `jwtDecoder` bean and `oauth2ResourceServer(...)` config; the `@PreAuthorize` will still see the principal the gateway forwarded.

- **OpenSearch index does not exist on a cold start.** `OpenSearchIndexInitializer` runs *after* `ApplicationReadyEvent`. If the first request to `/api/search/restaurants` arrives before the listener finishes (very tight race), the OpenSearch client returns 404 and the controller returns 500. In practice the indexer is sub-second; if you ever see this, a `try { search } catch (index_not_found_exception) { sleep; retry }` is the smallest fix.

- **No DLQ for poison messages.** The listener re-throws on any projection failure, so a single bad event will block the partition forever. The javadoc on `RestaurantEventListener` calls this out explicitly. A production fix is a `DefaultErrorHandler` configured with a `DeadLetterPublishingRecoverer`; out of scope for the bible.

- **MDC is cleared on each record.** The try-with-resources `MdcContext.fromKafka(record)` block scopes the correlation id to one record. Do not move any work outside that block that should be correlated with the originating event.

- **Anomalies from the inventory (search-service-specific).**
  - `RestaurantEventListener` is the only consumer of `com.samato.sharedkafka.observability.MdcContext` in the whole monorepo — every other service that uses it goes through the same shared class, but search-service is currently the sole importer.
  - The `DomainEvent` marker interface in `shared-kafka` is **not** implemented by any Avro-generated class. The listener side therefore uses the Avro positional `GenericRecord.get("eventId")` lookup rather than a typed accessor. See `shared-and-kafka.md` for the inventory note.
  - `RestaurantEventListener` reads `eventId` via `((GenericRecord) ev).get("eventId")` with a `(Object)` cast. The comment in the code explains the disambiguation between the int-positional `get(int)` and the field-name `get(String)` overloads.

- **Anomalies from the inventory (shared modules — relevant when you grep across).**
  - `shared/src/main/java/com/samato/shared/errors/GlobalExceptionHandler` is annotated `@RestControllerAdvice` but its `importedBy` list in the inventory is empty — meaning no service today has the package scan that would pick it up. This service *does* scan `com.samato.shared` in `SearchServiceApplication`, so the handler *is* available, but no other service currently references it. See `shared-and-kafka.md`.
  - `shared/src/main/java/com/samato/shared/observability/CorrelationIdFilter` is annotated `@Component @Order(Ordered.HIGHEST_PRECEDENCE)` and is auto-registered by the `@ComponentScan` on `SearchServiceApplication`. It is therefore active here even though search-service does not need it (it has no inbound HTTP from anything but the gateway, which already injects the correlation id).

## 8. See also

- Per-service designer note (do not duplicate — link from here): [`services/search-service/docs/INTERVIEW-NOTES.md`](../../services/search-service/docs/INTERVIEW-NOTES.md) — the design rationale, an interview-ready summary, and the deliberate "what we did not do" list.
- Glossary: [`../00-glossary.md`](../00-glossary.md) — terms like *CQRS*, *projection*, *at-least-once*, *Avro*, *SpecificRecord*, *Eureka*, *JWK*.
- Architecture guide: [`../01-architecture-guide.md`](../01-architecture-guide.md) — overall system map, where this service sits in the event flow.
- How auth works: [`../02-how-auth-works.md`](../02-how-auth-works.md) — the JWT/JWKS dance that `SecurityConfig` participates in.
- Shared modules and Kafka plumbing: [`../services/shared-and-kafka.md`](shared-and-kafka.md) — `CorrelationIdFilter`, `MdcContext`, the Avro schemas for `RestaurantCreatedEvent` and `RestaurantUpdatedEvent`, and the `DomainEvent` anomaly.
- Use case: [browse-and-search](../use-cases/03-browse-and-search.md) — the end-user story: log in, search restaurants, click into a menu. This service is the second step.
- Related services:
  - [`restaurant-service.md`](restaurant-service.md) — the producer of the events this service consumes.
  - [`api-gateway.md`](api-gateway.md) — the front door that adds the JWT filter and routes `/api/search/**` here.
  - [`auth-service.md`](auth-service.md) — the issuer of the JWTs verified by `SecurityConfig`.
- Repo-wide: [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md), [`../../PROJECT-STATUS.md`](../../PROJECT-STATUS.md), [`../../RUN-THE-BIBLE.md`](../../RUN-THE-BIBLE.md), [`../../docs/INTERVIEW-CHEATSHEET.md`](../../docs/INTERVIEW-CHEATSHEET.md).
