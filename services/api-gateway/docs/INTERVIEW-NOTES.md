# API Gateway — Interview Notes

## What it does (1 line)
The single entry point. Translates `/api/orders/...` into a real call to
the order service, after validating auth and applying cross-cutting policies.

## Why this service exists
A client (web, mobile, partner) shouldn't talk to 12 services directly.
That would mean:
- 12 base URLs to manage
- 12 auth implementations
- 12 rate-limit policies
- The internal topology leaks to clients
- A change in any service breaks every client

A gateway gives you **one contract** for clients and a **single place**
to enforce cross-cutting concerns (auth, CORS, rate limit, tracing).

## Patterns demonstrated
| Pattern | Where | Why it matters |
|---------|-------|----------------|
| **Routing by service name** | `GatewayRoutesConfig` | `lb://ORDER-SERVICE` resolves via Eureka |
| **Service discovery** | `@EnableDiscoveryClient` + Eureka client | No hard-coded URLs |
| **Centralized auth** | `SecurityConfig` + `JwtAuthFilter` | One place to validate JWTs |
| **CORS** | `CorsConfig` | Browser clients need this |
| **Health probes** | `actuator.health.probes` | K8s/Compose gates traffic on these |
| **Graceful shutdown** | `server.shutdown: graceful` | Drains in-flight requests |
| **Reactive stack** | `web-application-type: reactive` | Non-blocking, scales to high concurrency |
| **MDC in logs** | shared `CorrelationIdFilter` | traceId/spanId/correlId in every log line |
| **Distributed tracing** | `management.tracing` | Calls to downstream services are auto-spanned |

## Key endpoints
- `GET  /actuator/health` — liveness/readiness
- `GET  /actuator/gateway/routes` — list of routes
- `GET  /actuator/prometheus` — metrics
- `GET  /api/orders/**`       — routed to ORDER-SERVICE
- `GET  /api/restaurants/**`  — routed to RESTAURANT-SERVICE
- `POST /api/auth/login`      — public, routed to AUTH-SERVICE

## Interview Q&A

**Q: What's the difference between an API gateway and a service mesh?**
A: A **gateway** sits at the **edge** — between the client and your services.
It handles north-south traffic (ingress). A **service mesh** sits between
**services** — it handles east-west traffic, with sidecars that handle mTLS,
retries, traffic shifting. They complement, not replace, each other.
In K8s: gateway = Ingress, mesh = Istio/Linkerd sidecars.

**Q: How does the gateway know which service to route to?**
A: Routes are configured (here, in code). The instance URL is discovered
via Eureka (`lb://SERVICE-NAME`). On every request, the gateway asks
Eureka for a healthy instance, then proxies.

**Q: What about request aggregation (BFF)?**
A: That's a separate pattern (Backend For Frontend). Each client type
(web, mobile, partner) gets its own gateway instance tuned for its needs.
We won't add a second gateway in the bible, but interview answer should
say: "for multiple client types, a BFF per client is the right call."

**Q: How do you handle a slow downstream?**
A: Three levers:
1. **Timeouts** — `spring.cloud.gateway.httpclient.response-timeout`.
2. **Circuit breaker** — Resilience4j gateway filter (Phase 7).
3. **Rate limit / bulkhead** — protect the gateway itself from being
   overwhelmed by retries.

**Q: Where does auth happen — gateway or service?**
A: **Both**, but at different layers.
- Gateway: **authenticates** (token is valid? not expired?).
- Service: **authorizes** (this user allowed to access this resource?).
A common interview trap is to skip service-side authz — gateway-only is
fine for "any logged-in user can hit the public area" but not for
"only the order owner can see their own order". Phase 2 hardens this.

## Trade-offs we made
- **Explicit route definitions** in code over auto-discovery. More verbose,
  but each route is auditable, can have its own filters, and shows up
  in the `/actuator/gateway/routes` endpoint.
- **Stub JWT filter** instead of full JWKS. Replaced in Phase 2.
- **No BFF** — single gateway for all client types. Fine for the bible.
- **Reactive** stack (WebFlux). Different from the imperative services
  — note this when porting filters or config from elsewhere.

## Follow-ups interviewers ask
- "How do you handle WebSocket?" → Gateway supports it via `ws://` URIs in routes.
- "What about gRPC?" → Yes, but you need HTTP/2 + gRPC-aware filters.
- "How do you do canary releases?" → Route weights / Istio traffic splitting.
- "What about response caching?" → `LocalResponseCache` filter, or upstream CDN.
