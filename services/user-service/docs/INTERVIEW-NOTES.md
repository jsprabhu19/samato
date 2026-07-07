# User Service — Interview Notes

## What it does (1 line)
Stores the **profile** side of identity — display name, photo, preferences,
business info, vehicle info — for the three non-admin user types in the
system: customer, restaurant owner, driver.

## Why this service exists
Authentication is not the same as identity. The interview question to
expect is: **"Why split user-service from auth-service?"**

| Concern | Auth Service | User Service |
|---------|--------------|--------------|
| Owns | Passwords, OAuth clients, roles | Names, photos, addresses, prefs |
| Reads/writes | Login, token issuance | Profile updates, view profiles |
| Audience | The security team | The product team |
| PII level | Low (email + hash) | High (name, address) |
| Scaling | Low traffic, must be up | High read traffic, can lag |
| Privacy | Required to be tight | Can be relaxed, but should be careful |

The **join key** is the user's UUID — a logical reference, not a DB FK.
The gateway or Feign pulls both sides and presents them to the client.

## Patterns demonstrated
| Pattern | Where | Why it matters |
|---------|-------|----------------|
| **Resource server** | `SecurityConfig.jwtDecoder()` | Validates JWTs against auth-service's JWKS |
| **Role-based authZ** | `@PreAuthorize("hasRole(...)")` | Service-side authorization (gateway ≠ service) |
| **Feign client** | `AuthClient` | Service-to-service call with a typed interface |
| **Circuit breaker (placeholder)** | `resilience4j.*` config | Calls to auth-service fail fast if it's down |
| **Self-ownership check** | `@PreAuthorize("authentication.name == #id...")` | "you can only see your own data" |
| **Repository per aggregate** | 3 separate repos, 3 separate tables | Different profile shapes = different tables |
| **Flyway migrations** | `db/migration/V1__init.sql` | Versioned schema |
| **JSONB for prefs** | `preferences_json JSONB` | Postgres-native JSON for flexible data |
| **Partial index** | `WHERE on_duty = TRUE` | Fast "find available drivers" query |

## Key endpoints
| Method | Path | Role | Purpose |
|--------|------|------|---------|
| `GET`    | `/api/users/me` | any auth'd | My customer profile |
| `PUT`    | `/api/users/me` | any auth'd | Update my profile |
| `GET`    | `/api/users/{id}` | self or admin | Read a profile |
| `GET`    | `/api/users/drivers/me` | DRIVER | My driver profile |
| `PUT`    | `/api/users/drivers/me` | DRIVER | Update my driver profile |
| `PUT`    | `/api/users/drivers/me/location` | DRIVER | Push current GPS |
| `GET`    | `/api/users/drivers/on-duty` | DRIVER/ADMIN | List on-duty drivers |
| `GET`    | `/api/users/restaurant-owners/me` | RESTAURANT_OWNER | My business profile |
| `PUT`    | `/api/users/restaurant-owners/me` | RESTAURANT_OWNER | Update business profile |

## Interview Q&A

**Q: How does user-service know who the caller is?**
A: The API gateway attaches a validated JWT to the `Authorization` header.
user-service fetches auth-service's public key from `/.well-known/jwks.json`,
caches it, and validates the JWT **locally**. No call to auth-service per
request. The `sub` claim is the user's UUID — that's our join key.

**Q: How do you keep auth-service and user-service in sync?**
A: Two approaches, often both:
1. **Eventual**: when user-service creates a profile, it publishes a
   `UserCreated` event. auth-service subscribes and creates a parallel row.
   Loose coupling, eventual consistency.
2. **Federated**: user-service calls auth-service via Feign on demand to
   check the user exists. Tighter coupling, more accurate.
The bible uses approach 2 for now; the event-driven version is Phase 7+
territory.

**Q: Why role-based authZ at the service, not just the gateway?**
A: The gateway can check "is this user authenticated?" but **cannot** check
"can THIS user read THIS resource?" without a call to the service that owns
the resource. So the gateway is for **transport-level authN**, services are
for **resource-level authZ**. Confusing these is one of the most common
production security bugs.

**Q: What's the role of Feign here vs. just calling auth-service directly?**
A: Feign gives us:
- A typed interface (no string-typed URL soup)
- Service discovery (Eureka) for free
- Resilience4j annotations (Phase 7)
- Logging + metrics
The cost: an extra abstraction. For one-off calls, you might prefer
WebClient. For stable inter-service APIs, Feign is the standard choice.

**Q: Why not store everything in one big `users` table?**
A: Each role's profile has different data. A driver has `vehicleType`; a
customer has `preferences`; an owner has `taxId`. Putting them all in one
table is the classic "table that grows forever" problem — every role gets
nullable columns, and you can't enforce non-null constraints. Separate
tables for separate aggregates is the right answer.

**Q: How do you handle a profile read when the user is logged in for the first time?**
A: We `getOrCreate` — try to read, if not found, create a skeleton with
sensible defaults from the JWT (display name from email local-part, etc.).
The first read is also the first write. The user can then update the
defaults. This avoids a forced "complete your profile" wizard.

## Trade-offs we made
- **3 separate tables instead of 1 generic + JSONB** — easier to reason
  about, harder to scale to N roles.
- **Feign + Resilience4j configured but not yet wired** — Phase 2 sets up
  the config; Phase 7 actually wraps the calls.
- **Service-to-service auth via JWT propagation** — the gateway's token is
  forwarded downstream. Alternative: a separate service-to-service token
  via `client_credentials` grant. We pick propagation for simplicity; the
  bible shows both in interview notes.
- **Location updates land in Postgres** — fine for low rate, terrible for
  thousands of drivers updating per second. Phase 6 moves hot-path writes
  to Redis and only snapshots to Postgres.

## Follow-ups interviewers ask
- "How do you delete a user?" → Soft-delete (an `archived_at` column) so
  we never lose the audit trail.
- "GDPR right-to-be-forgotten?" → Anonymize PII in user-service and
  delete the auth row. Different teams may need to coordinate.
- "How do you version the profile schema?" → Flyway for columns; for
  optional fields, JSONB. For breaking changes, a V2 migration + a feature
  flag to switch.
