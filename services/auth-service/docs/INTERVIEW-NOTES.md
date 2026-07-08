## Status (2026-07-08)

✅ **Verified running on this machine.** The service image is built by `docker compose build` from the local jar, the container is `Up (healthy)`, `/actuator/health` returns **HTTP 200** with `{"status":"UP"}`, and the service is registered in **Eureka** as `SAMATO-AUTH-SERVICE`.

- **Port:** 9000
- **Image:** samato-auth-service:dev (compose tags it `auth-service:latest`)
- **Health:** `curl http://localhost:9000/actuator/health` → `{"status":"UP", ...}`
- **Bring-up bug fixes in this service**: added `spring.cloud.config.enabled: false`, fixed `DevDataSeeder` to also check `existsByEmail` so re-runs don't crash on the duplicate-email constraint.

---

# Auth Service — Interview Notes

## What it does (1 line)
The OAuth2 / OIDC authorization server. Owns user passwords, signs JWTs with
RS256, and publishes a JWKS so other services can verify them locally.

## Why this service exists
Every secure system needs an "answer" to two questions:
1. **Who is this user?** (authentication)
2. **What can they do?** (authorization)

A single auth service is the **trust anchor** for the whole platform.
Once every other service trusts the same `iss` claim, they don't have to
talk to each other about identity — they just verify the signature.

## Patterns demonstrated
| Pattern | Where | Why it matters |
|---------|-------|----------------|
| **OAuth2 / OIDC** | `AuthServerConfig` + `JpaRegisteredClientRepository` | Industry-standard auth |
| **JWT (RS256)** | `JWKSource` + `RSAKey` | Asymmetric signing, services don't need the signing key |
| **JWKS endpoint** | `/.well-known/jwks.json` (auto-exposed by AS) | Verifiers fetch the public key |
| **Password grant** | `grant-types.password.enabled` | Custom login forms; the bible uses it |
| **Refresh tokens** | `refresh-token.lifetime: P7D` | Short-lived access, long-lived session |
| **BCrypt cost 12** | `BCryptPasswordEncoder(12)` | Industry standard for password hashing |
| **Custom claims** | `JwtRolesCustomizer` | `roles` and `user_id` in the JWT |
| **Stateless sessions** | `SessionCreationPolicy.STATELESS` | JWT means no server-side session |
| **Flyway migrations** | `db/migration/V*.sql` | Versioned, auditable schema changes |
| **Resource server (self)** | `spring-boot-starter-oauth2-resource-server` | The AS validates its own tokens for /me |

## Key endpoints
| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/auth/register` | public | New customer sign-up |
| `POST` | `/oauth2/token` | client + user | Password / refresh / client_credentials |
| `GET`  | `/.well-known/openid-configuration` | public | Discovery |
| `GET`  | `/.well-known/jwks.json` | public | Public signing keys |
| `GET`  | `/api/auth/me` | JWT | Who am I? |
| `GET`  | `/api/auth/debug/token` | JWT | Inspect a token (dev only) |

### Login example

```bash
# Get a token using the password grant
curl -X POST http://localhost:9000/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "api-gateway:gateway-secret-please-rotate" \
  -d "grant_type=password&username=alice@example.com&password=password123&scope=openid profile"

# Response:
# { "access_token": "eyJ...", "refresh_token": "...", "token_type": "Bearer",
#   "expires_in": 900, "scope": "openid profile" }
```

## Interview Q&A

**Q: Why JWT, not opaque tokens?**
A: With JWT, services verify the token **locally** using the public key from
the JWKS — zero network calls to auth-service per request. With opaque
tokens, every request needs a `POST /introspect` call. The trade-off:
- JWT: **fast**, **scalable**, but **can't revoke** without a denylist.
- Opaque: **slow** (a call per request), but trivially revocable.
For a food delivery app at scale, JWT wins. Use **short access tokens**
(15 min) + refresh tokens + a denylist for compromised tokens if needed.

**Q: RS256 vs HS256?**
A: RS256 is **asymmetric** — the auth-service signs with a private key,
everyone else verifies with the public key. Compromise of any non-auth
service doesn't leak the signing key. HS256 is **symmetric** — every
verifier needs the same secret, so a compromised service compromises
all of them. Use RS256 in anything beyond a single service.

**Q: How do you rotate the signing key?**
A: The JWKS endpoint can publish **multiple keys** with different `kid`s.
Tokens are signed with the latest `kid`; verifiers fetch the JWKS and
find the right key. Old keys stay published until all tokens signed with
them expire. Phase 2 keeps it simple (one key); Phase 8 will add rotation.

**Q: Where do refresh tokens live?**
A: Two schools of thought:
- **Stateless** (what we do): the refresh token is itself a signed JWT
  with a long expiry. No DB. Trade-off: can't revoke.
- **Stateful**: the refresh token is a random opaque string stored in DB.
  Revocation is a `DELETE`. The bible picks stateful in Phase 8 hardening
  (with a `revoked_tokens` table); Phase 2 uses stateless for simplicity.

**Q: How do you handle role changes?**
A: Three options, in increasing order of cost:
1. **Short access tokens** — the user gets the new role in 15 min.
   Acceptable for most products.
2. **Token version claim** — issue a new `token_version` on role change;
   verifiers reject tokens with a stale version. Requires custom JWT logic.
3. **Deny-list** — keep a "revoked JTIs" set in Redis. Most flexible,
   most infra. We add this in Phase 8 if the interview scenario demands it.

**Q: How do you prevent brute force?**
A: (a) Account lockout after N failed attempts (in-memory cache or Redis).
(b) Exponential backoff per-IP. (c) CAPTCHA after threshold.
(d) For high-security, push auth behind a WAF or Cloudflare Turnstile.
The bible covers (a) in a follow-up; for now, log failures and rely on
the strong BCrypt cost to make each attempt slow.

**Q: What goes wrong with the password grant?**
A: It is **deprecated** by OAuth 2.1 because it requires the user to type
their password into the *application*, which the application then sends
to the AS. This means the application sees the password. The replacement
is the **authorization code + PKCE** flow, which keeps the password in
the user's browser and the AS only. The bible uses password grant for
simplicity — the comment in the config calls this out explicitly.

## Trade-offs we made
- **Spring Authorization Server over Keycloak**: the bible wants to *show*
  the OAuth2 internals, not just configure a black box. In a real product
  I'd recommend Keycloak or Auth0.
- **Password grant enabled**: the bible's login form posts directly to
  the token endpoint. Production: authorization code + PKCE.
- **One RSA key generated at startup**: dev-only convenience. Prod loads
  from a KMS and rotates via the `kid` mechanism.
- **Stateless refresh tokens**: simpler, but no instant revocation.
- **Roles in the JWT**: fine for 4 roles. If your role set grows dynamic
  (e.g. per-tenant), use a separate permission service or move roles
  out of the token and fetch them per request.

## Follow-ups interviewers ask
- "How do you test this?" → @WithMockUser / `@WithJwt` (Spring Security Test).
  Phase 2 doesn't include tests yet; Phase 8 adds Testcontainers-based tests.
- "What about MFA?" → A `mfa` claim + an additional factor verification step
  before token issuance. Out of scope for the bible.
- "What about social login?" → The AS supports linking external IdPs via
  the OIDC `identity_provider` config; the bible doesn't add it.
