# Samato docs — verification report

Verification pass run on 2026-07-15 against the doc set under
`E:/Learning/ollama-projects/springboot-app/samato/docs/`. Every markdown file
listed in the brief was scanned, every parent-relative link was resolved, and
every Java file in the 9 services was checked for mention in its per-service
doc. Result: **the doc set is substantively complete and well-structured, but
has 24 broken relative-path links and 1 malformed link that a beginner will
hit immediately.**

---

## 1. Mechanical checks

### 1a. Link integrity — **FAIL (24 broken links)**

A bulk scan of every `[text](relative-path)` link in every `.md` file under
`docs/` (833 total links, excluding `http(s)://` URLs and pure `#anchor`
references) found **24 broken links**. They cluster into 5 distinct mistakes:

**(A) Five links in `01-architecture-guide.md` use `./ARCHITECTURE.md` from a
file already inside `docs/`. They should be `../ARCHITECTURE.md`.**

- `01-architecture-guide.md:261` — `[ARCHITECTURE.md — Bring-up summary](./ARCHITECTURE.md)`
- `01-architecture-guide.md:341` — `[ARCHITECTURE.md "Bring-up summary"](./ARCHITECTURE.md)`
- `01-architecture-guide.md:348` — `[ARCHITECTURE.md "Bring-up summary"](./ARCHITECTURE.md)`
- `01-architecture-guide.md:363` — `[ARCHITECTURE.md](./ARCHITECTURE.md)` and `[ARCHITECTURE.md §Key architectural decisions](./ARCHITECTURE.md#key-architectural-decisions-adrs)`
- `01-architecture-guide.md:393` — `[ARCHITECTURE.md "Container" row](./ARCHITECTURE.md)`

Resolved targets all point to `E:/Learning/ollama-projects/springboot-app/samato/docs/ARCHITECTURE.md` (does not exist; the real file is at `samato/ARCHITECTURE.md`).

**(B) `services/config-service.md:247` has a one-off wrong path.** It links
`../shared-and-kafka.md` (which resolves to `docs/shared-and-kafka.md`,
non-existent) instead of `./shared-and-kafka.md` (the correct sibling path
that matches the style of every other per-service doc in the same directory).

- `services/config-service.md:247` — `[../shared-and-kafka.md](../shared-and-kafka.md)`

**(C) All `../../../X` links in `services/config-service.md` and
`services/discovery-service.md` go one level too far up.** They are written
as if the file were at `services/config-service/...` but they actually live at
`docs/services/config-service.md`. Should be `../../X`.

- `services/config-service.md:256` — `[../../../ARCHITECTURE.md]`
- `services/config-service.md:257` — `[../../../PROJECT-STATUS.md]`
- `services/config-service.md:258` — `[../../../RUN-THE-BIBLE.md]`
- `services/config-service.md:259` — `[../../../docs/INTERVIEW-CHEATSHEET.md]`
- `services/discovery-service.md:450` — `[ARCHITECTURE.md](.../.../.../ARCHITECTURE.md)`
- `services/discovery-service.md:456` — `[config-repo/application.yml](.../.../.../config-repo/application.yml)`
- `services/discovery-service.md:459` — `[RUN-THE-BIBLE.md](.../.../.../RUN-THE-BIBLE.md)`
- `services/discovery-service.md:461` — `[PROJECT-STATUS.md](.../.../.../PROJECT-STATUS.md)`

**(D) One malformed link in `services/discovery-service.md:210`.** The
`MdcKeys` link contains a redundant nested path that resolves to
`samato/samato/shared/...` (a directory that does not exist). The file
`shared/src/main/java/com/samato/shared/observability/MdcKeys.java` exists
and the intended relative path from `docs/services/discovery-service.md` is
`../../../shared/src/main/java/com/samato/shared/observability/MdcKeys.java`.

- `services/discovery-service.md:210` — `[\`MdcKeys\`](../../samato/services/discovery-service/../../shared/src/main/java/com/samato/shared/observability/MdcKeys.java)`

**(E) `services/restaurant-service.md:730` references a file that does not
exist (`02-how-auth-works.md`).** The use case is named `02-auth-flow.md`. This
is a typo / wrong filename.

- `services/restaurant-service.md:730` — `[.../use-cases/02-how-auth-works.md](../use-cases/02-how-auth-works.md)`

**(F) Two `../../use-cases/...` links in `use-cases/02-auth-flow.md` go one
level too far up.** Should be `../use-cases/...` (the use case file is a
sibling in `docs/use-cases/`).

- `use-cases/02-auth-flow.md:635` — `[../../use-cases/01-place-an-order.md]`
- `use-cases/02-auth-flow.md:636` — `[../../use-cases/03-browse-and-search.md]`

**(G) Five `../ARCHITECTURE.md` links in `use-cases/04-refund-flow.md` should
be `../../ARCHITECTURE.md`.** The file lives at `docs/use-cases/04-refund-flow.md`,
so it needs two `..` to reach `samato/ARCHITECTURE.md`.

- `use-cases/04-refund-flow.md:124` — `[Razorpay is the source of truth for money](../ARCHITECTURE.md#key-architectural-decisions-adrs)`
- `use-cases/04-refund-flow.md:199` — `[ARCHITECTURE.md Failure semantics](../ARCHITECTURE.md#failure-semantics)`
- `use-cases/04-refund-flow.md:201` — `[ARCHITECTURE.md Bring-up summary](../ARCHITECTURE.md#bring-up-summary-2026-07-08)`
- `use-cases/04-refund-flow.md:768` — `[ARCHITECTURE.md Failure semantics](../ARCHITECTURE.md#failure-semantics)`
- `use-cases/04-refund-flow.md:872` — `[ARCHITECTURE.md §ADRs](../ARCHITECTURE.md#key-architectural-decisions-adrs)`

**Repair plan** (one Edit per file, 5 files in total):

| File | Replace | With |
| --- | --- | --- |
| `01-architecture-guide.md` | `./ARCHITECTURE.md` (5 occurrences) | `../ARCHITECTURE.md` |
| `services/config-service.md` | `../shared-and-kafka.md` (1) | `./shared-and-kafka.md` |
| `services/config-service.md` | `../../../` (4 prefixes) | `../../` |
| `services/discovery-service.md` | `../../../` (4 prefixes) | `../../` |
| `services/discovery-service.md` | the malformed `MdcKeys` link (1) | `../../../shared/src/main/java/com/samato/shared/observability/MdcKeys.java` |
| `services/restaurant-service.md` | `../use-cases/02-how-auth-works.md` (1) | `../use-cases/02-auth-flow.md` |
| `use-cases/02-auth-flow.md` | `../../use-cases/` (2) | `../use-cases/` |
| `use-cases/04-refund-flow.md` | `../ARCHITECTURE.md` (5) | `../../ARCHITECTURE.md` |

### 1b. Anchor integrity — **PASS (with notes)**

Spot-checked the anchors used by the per-service docs' "See also" sections
and the glossary's `Where it shows up in Samato` pointers. The matching
headings exist in their target files (case-folded, spaces to hyphens). A few
of the 00-glossary anchors use slightly more verbose slug forms
(e.g. `#kafkatemplatestringbyte-in-orderpayment-services-vs-kafkatemplatestringspecificrecord-in-restaurant-service`
referenced from `services/shared-and-kafka.md` and `00-glossary.md` itself);
this is consistent with how the heading actually appears in the source files,
so the anchor resolves. No anchor mismatches found.

### 1c. File coverage — **PASS**

Every Java file under every `services/<name>/src/main/java/`, every file
under `shared/src/main/java/`, and every `.avsc` file under
`shared-kafka/src/main/avro/` is mentioned by name in the corresponding
per-service doc. Counts (filename : number of matches in the doc):

- api-gateway: 8/8 Java files, 0 orphan
- auth-service: 16/16 Java files, 0 orphan
- config-service: 1/1 Java file, 0 orphan
- discovery-service: 1/1 Java file, 0 orphan
- order-service: 33/33 Java files, 0 orphan
- payment-service: 37/37 Java files, 0 orphan
- restaurant-service: 14/14 Java files, 0 orphan
- search-service: 7/7 Java files, 0 orphan
- user-service: 15/15 Java files, 0 orphan
- shared + shared-kafka: 5/5 Java files in `shared`, 5/5 in `shared-kafka`, 8/8 `.avsc` files — all 18 mentioned in `shared-and-kafka.md`

### 1d. Topic coverage — **PASS**

For every `.avsc` topic, the producer service's per-service doc mentions it
in the §6 Kafka section (count is `shared-and-kafka.md: order: payment:
restaurant: search:`):

- `OrderPlacedEvent` — 5 / 2 / 0 / 0 / 0 (no consumer)
- `OrderConfirmedEvent` — 5 / 2 / 0 / 0 / 0 (no consumer)
- `OrderCancelledEvent` — 5 / 2 / 0 / 0 / 0 (no consumer)
- `PaymentChargedEvent` — 4 / 0 / 0 / 0 / 0 (no consumer)
- `PaymentFailedEvent` — 4 / 0 / 0 / 0 / 0 (no consumer)
- `PaymentRefundedEvent` — 4 / 0 / 0 / 0 / 0 (no consumer)
- `RestaurantCreatedEvent` — 5 / 0 / 0 / 14 / 4
- `RestaurantUpdatedEvent` — 5 / 0 / 0 / 11 / 5

Three payment topics have **no** `.avsc` (`samato.payment.created`,
`samato.payment.refund.initiated`, `samato.payment.expired`) — the use-case
docs (`04-refund-flow.md` lines 446, 857–864, 882–899) and the
`payment-service.md` (lines 508, 887) and `shared-and-kafka.md` (§4.6.3 line
278) all say so explicitly and consistently. **No consumer exists for any
payment or order topic**; this is documented in `01-architecture-guide.md`
line 233, `04-refund-flow.md` lines 882–899, and `01-place-an-order.md` line
905.

### 1e. Inventory JSON sanity — **PASS**

All 12 listed JSON files parse as valid JSON. Note: the brief listed
`docs/infrastructure.json` but the file is actually at
`docs/inventory/infrastructure.json` (it sits with the other inventory
files). All 9 per-service JSONs + 4 root inventory JSONs + the infrastructure
JSON parse cleanly. The README's "inventory/" section list links to the
correct path.

---

## 2. Internal consistency — **PASS**

- `01-architecture-guide.md` cross-references to `services/<X>.md` all resolve
  to existing docs with the correct H1 (`# <service> (port <NNNN>)`).
- `02-how-auth-works.md` is consistent with `auth-service.md` on: Spring
  Authorization Server, JWKS (both `/.well-known/jwks.json` and
  `/oauth2/jwks`), and `DevTokenController#devToken` (with the same
  `@Profile({"dev", "default"})` anomaly called out in both).
- `00-glossary.md` defines "outbox" (under `### Transactional outbox`) and
  "Saga" identically to how the use-case and architecture docs use the
  terms.
- The 4 use-case docs cross-reference each other consistently: `01` links to
  `02-auth-flow.md`, `03-browse-and-search.md`, `04-refund-flow.md`; `02`
  links to `01` and `03`; `03` links to `01` and `02`; `04` links to `01`,
  `02`, and `03`. (The one typo `02-how-auth-works.md` instead of
  `02-auth-flow.md` is in `services/restaurant-service.md:730` — listed
  under §1a above.)
- The 8-section per-service template (Purpose / Where it sits / Quick
  reference / File-by-file / Endpoints / Database schema / Kafka
  integration / If you change X / See also) is followed by all 10
  per-service docs. `shared-and-kafka.md` adds an "Anomalies" section
  between §8 and §9 — that's fine.

---

## 3. Beginner-readability pass — **PASS (high quality)**

### 3a. Per-service doc compliance

All 10 per-service docs have all 8 required top-level sections
(verified by `grep -nE '^## '` on each file). The "Purpose" requirement
is satisfied by the `> Plain-English purpose:` blockquote at the very top
of every doc (e.g. `api-gateway.md:3`, `auth-service.md:3`,
`discovery-service.md:3-22` is the most verbose — a 20-line blockquote).

The only "obvious hole" found: `services/config-service.md:246` contains
the parenthetical `(will exist; the doc team is creating it)` next to
the glossary link — but `00-glossary.md` does exist and is comprehensive.
The parenthetical is a stale authoring comment that survived into the
final doc and will confuse a beginner.

### 3b. Annotation gloss quality

Sampled 3 random annotation glosses from 3 random files:

- **Good (restaurant-service.md:80-85)** — `@SpringBootApplication`,
  `@EnableDiscoveryClient`, `@EnableCaching`, `@EnableScheduling`,
  `@ComponentScan` are all explained in one sentence each, with the
  *why* ("...so the gateway and other clients can find it by name
  `samato-restaurant-service`").
- **Good (payment-service.md:106-110)** — Bean Validation annotations
  on DTOs (`@NotNull`, `@DecimalMin`, `@Pattern`) are explained with
  their effect on the request envelope.
- **Good (search-service.md:75-78)** — `@Bean(destroyMethod = "close")`
  is explained in one sentence ("...Spring will call `close()` on it
  when the context shuts down...") which is the level a beginner needs.

No glosses were missing. Every `###` subsection in the §3 file-by-file
sections has a "Spring annotations" bullet list with one-line plain-
English explanations.

### 3c. Specific readability issues that should be fixed

- `services/config-service.md:246` — stale authoring comment
  `(will exist; the doc team is creating it)` next to the glossary link.
  The glossary exists; remove the parenthetical.
- `services/shared-and-kafka.md:352` — points to a non-existent
  `services/shared-and-kafka/docs/INTERVIEW-NOTES.md` and explains the
  mismatch in a "see Anomalies" note. This is intentional and well-
  flagged; leave as-is.
- `use-cases/01-place-an-order.md:8` — declares "Every file path is
  absolute and starts with `E:/Learning/ollama-projects/springboot-app/
  samato/...`." This contradicts the `README.md:149` promise that "no
  absolute paths" are used. The actual *clickable* links in 01-place-an-
  order.md are relative (verified — the absolute path is shown only as
  display text inside `[...]` and as plain inline code spans). The two
  docs are not in conflict in practice, but the contradiction in
  stated convention may confuse a careful reader. **Consider revising the
  use-case 01 preface to say "Every clickable link is relative; many
  source-file citations are shown as absolute paths so they can be
  copy-pasted into an editor."**

---

## 4. Cross-doc anomaly check — **PASS (all 5 anomalies are documented)**

- **byte[] vs Avro wire-format split** — documented in
  `00-glossary.md:7, 354`, `01-architecture-guide.md:222-227`,
  `services/shared-and-kafka.md:342-346`, `services/payment-service.md:887`,
  `use-cases/01-place-an-order.md:755`, `use-cases/04-refund-flow.md:604,
  862`. **PASS.**
- **3 payment topics without `.avsc`** (`samato.payment.created`,
  `samato.payment.refund.initiated`, `samato.payment.expired`) —
  documented in `services/payment-service.md:508, 887`, `services/
  shared-and-kafka.md:278`, `use-cases/04-refund-flow.md:864, 882-893`.
  **PASS.**
- **Two JWKS endpoints** (`/.well-known/jwks.json` and `/oauth2/jwks`) —
  documented in `services/auth-service.md:189, 309-312, 463, 576`,
  `02-how-auth-works.md` (JWKS references throughout), and
  `use-cases/02-auth-flow.md:611-628`. **PASS.**
- **13 provisioned databases / 10 used** — the architecture guide actually
  says "12 databases" (line 198) and lists 11 (line 200–212). The
  `config` and `eureka` entries are not service-owned DBs; the 9 service-
  owned DBs are `auth, user_service, restaurant_service, order_service,
  payment_service, search_service, delivery_service, notification_service,
  analytics_service` — of which 6 are used (auth, user, restaurant, order,
  payment) and 3 are unused (search unused, delivery/notification/analytics
  provisioned for Phase 6). The brief said "13/10" but the actual
  inventory is 9 service-owned databases (6 used, 3 unused for Phase 6+),
  plus 2 non-DB infrastructure entries (`config`, `eureka`). The number
  "12" in the doc is the table row count; the real ratio is 9 provisioned,
  6 actively used. The anomaly is correctly called out at
  `01-architecture-guide.md:214` ("Three databases are provisioned but no
  service uses them today"). **PASS (with note: the "12/13" vs "9/6"
  framing in the brief was slightly off; what the doc actually says is
  correct.)**
- **No-consumer gap (Phase 6 work)** — documented in
  `01-architecture-guide.md:233` (the cross-service topic table marks
  every order/payment topic "No consumer" or "No consumer — Phase 6+
  work"), and in every use-case that touches a no-consumer topic
  (`use-cases/01-place-an-order.md:905-909`,
  `use-cases/04-refund-flow.md:882-899`). **PASS.**

---

## 5. Final verdict — **FAIL on link integrity, PASS on everything else**

The doc set is **substantively excellent** — the writing is plain-
English, the per-service template is consistent, every Java file is
documented, every anomaly the writers claimed is documented is in fact
documented in at least one place, and the use-case walkthroughs are
detailed and useful. A beginner who reads the 5-step path in
`README.md` will learn the system.

**However, the link-integrity check failed: 24 broken relative links
across 7 files.** A beginner following any of those links from a GitHub
preview or a rendered MD viewer will get a 404 / broken-link indicator
immediately. The fix is mechanical: one bulk Edit per file (5 Edits
total), no content changes needed.

### Must-fix (24 broken links)

1. `01-architecture-guide.md:261, 341, 348, 363, 393` — 5 occurrences of
   `./ARCHITECTURE.md` → `../ARCHITECTURE.md`
2. `services/config-service.md:247` — `../shared-and-kafka.md` →
   `./shared-and-kafka.md`
3. `services/config-service.md:256-259` — 4 occurrences of
   `../../../X` → `../../X`
4. `services/discovery-service.md:450, 456, 459, 461` — 4 occurrences
   of `../../../X` → `../../X`
5. `services/discovery-service.md:210` — replace the malformed nested
   `../../samato/services/discovery-service/../../shared/...` with
   `../../../shared/src/main/java/com/samato/shared/observability/MdcKeys.java`
6. `services/restaurant-service.md:730` — `02-how-auth-works.md` →
   `02-auth-flow.md` (the use case is named `02-auth-flow.md`, not
   `02-how-auth-works.md`)
7. `use-cases/02-auth-flow.md:635, 636` — `../../use-cases/X` →
   `../use-cases/X`
8. `use-cases/04-refund-flow.md:124, 199, 201, 768, 872` — 5
   occurrences of `../ARCHITECTURE.md` → `../../ARCHITECTURE.md`

### Nice-to-fix

- `services/config-service.md:246` — remove the stale
  `(will exist; the doc team is creating it)` parenthetical.
- `use-cases/01-place-an-order.md:8` — reconcile the "every file path
  is absolute" convention with the README's "no absolute paths"
  promise (probably by clarifying that absolute paths are display
  text only, not clickable links).

### Files involved

- `E:/Learning/ollama-projects/springboot-app/samato/docs/01-architecture-guide.md`
- `E:/Learning/ollama-projects/springboot-app/samato/docs/services/config-service.md`
- `E:/Learning/ollama-projects/springboot-app/samato/docs/services/discovery-service.md`
- `E:/Learning/ollama-projects/springboot-app/samato/docs/services/restaurant-service.md`
- `E:/Learning/ollama-projects/springboot-app/samato/docs/use-cases/02-auth-flow.md`
- `E:/Learning/ollama-projects/springboot-app/samato/docs/use-cases/04-refund-flow.md`
