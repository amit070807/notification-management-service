# Quickstart & Validation Guide: Notification Management Core

**Date**: 2026-09-07 | **Plan**: [plan.md](./plan.md) | **Contract**: [openapi.yaml](./contracts/openapi.yaml)

This is the run-and-validate guide (deliverable DO-003). It defines *what must be runnable and
provable*; implementation belongs to `tasks.md`. Every scenario below maps to a success criterion
in [spec.md](./spec.md).

## Prerequisites

| Requirement | Version | Why |
|-------------|---------|-----|
| JDK | 21 | ADR-001 |
| Docker | any recent | PostgreSQL via Compose and Testcontainers |
| Gradle | wrapper, committed | ADR-002 — no local Gradle install needed |

No credentials are required. All channel providers are simulated (ADR-009); the repository
contains no real secrets, only `.example` files.

## Run it

```bash
cp .env.example .env          # set POSTGRES_PASSWORD before first run
docker compose up -d          # PostgreSQL 16
./gradlew bootRun             # Flyway migrates on startup
```

`.env` is read by docker compose *and* by the application (via
`spring.config.import`), so both sides agree on the password without exporting
anything into your shell. A deployment supplying real environment variables needs
no `.env` at all — the import is declared `optional:`.

**If you see `password authentication failed`**: the PostgreSQL data volume keeps
the credentials from its *first* start, so changing `POSTGRES_PASSWORD` in `.env`
afterwards will not take effect. Reset with
`docker compose down -v && docker compose up -d`.

Service on `http://localhost:8080`, contract at `/api/v1`. Health at `/actuator/health`;
readiness reflects datastore reachability (constitution Observability).

**SC-011 gate**: these two commands, from a clean clone, with no undocumented step. If a
reviewer needs anything not written here, the deliverable has failed regardless of whether the
code works.

## Validation scenarios

Each is runnable against a live instance and is also covered by an automated test. Numbers are
the spec's success criteria.

### 1. Submit and retrieve status — SC-001, SC-002, SC-003, SC-004

Submit a notification with 2 recipients and both channels requested. Expect:

- `202` with a server-issued `id` and a `Location` header (FR-008).
- The response reports **no delivery outcome** — acceptance is not delivery (FR-029, SC-004).
- `GET /notifications/{id}/status` **immediately** returns `200`, not `404` (FR-015, SC-002).
- Status shows **4 delivery entries**, one per recipient-and-channel pair (FR-012, SC-003).

### 2. Rejection names every offending field — SC-001

Submit with an empty `recipients` array, an unknown `severity`, and no `content`. Expect one
`400` whose `errors` array names **all three** fields, not just the first (FR-004), and that no
notification, delivery or routing decision was created (FR-007).

### 3. Duplicate client identifiers — FR-008b, spec D4

Submit the same `clientNotificationId` twice. Expect both accepted, **different** `id` values,
and two independent status resources with independent deliveries.

This is deliberately **not** idempotency (constitution register item 9). If a reviewer sees the
second submission return the first one's state, that is a defect.

### 4. Routing decision is explained and stable — SC-010, SC-012

Submit requesting both channels with a policy that excludes `SMS` below `HIGH` severity. Expect
`channelOutcomes` to show `SMS` excluded with `POLICY_EXCLUDED`, `selectedChannels` to contain
only `EMAIL`, and `routingPolicyVersion` populated (FR-022, FR-026). Change the policy, restart,
re-read the same notification — the recorded decision is **unchanged** (FR-021).

### 5. Routing consults nothing recipient-specific — SC-013

Submit two notifications identical except for their recipient references. Expect **identical**
channel selections and identical `channelOutcomes` reason codes.

This guards the G-26 deviation: §4.3's preference factor is deferred, and FR-019a forbids
smuggling a proxy for it under another name. After D5 the submission carries no recipient
attribute that *could* serve as such a proxy, so the test also fails if anyone later adds one —
which is the regression worth catching.

### 6. Every failure classification behaves per the taxonomy — SC-005, SC-006

Drive the simulated provider to emit each of the six classifications and assert:

| Classification | Expected | Trace |
|----------------|----------|-------|
| `TRANSIENT_PROVIDER_FAILURE` | retried, then `DELIVERED` or `EXHAUSTED` | FR-040 |
| `TIMEOUT` | retried | FR-040 |
| `UNKNOWN` | retried, never `DELIVERED` | FR-041 |
| `PERMANENT_PROVIDER_REJECTION` | `FAILED`, no retry | FR-040 |
| `INVALID_RECIPIENT` | `FAILED`, no retry, other channels unaffected | FR-040 |
| `AUTH_ERROR` | `FAILED`, no retry, **operational signal raised** | FR-042 |

Assert across a full run that **zero** deliveries exceed the configured maximum attempts (SC-006)
and that `EXHAUSTED` is distinguishable from a first-attempt `FAILED` (FR-044).

### 7. Expiry outranks the retry budget — SC-007

Schedule a delivery into backoff, advance the clock past `expiresAt`, run the worker. Expect
state `EXPIRED` — **not** `FAILED`, **not** `EXHAUSTED` — with no further attempt, even though
budget remained (FR-033, FR-034).

### 8. Not-before is honoured — SC-014

Across all four combinations of `notBefore`/`expiresAt` present or absent: zero attempts before
`notBefore`, and the notification becomes eligible once it passes (FR-003a, FR-031).

Also: submit with `notBefore >= expiresAt` → `400` (FR-003d); with `expiresAt` already past →
`400` (FR-003e).

### 9. Audit history is complete — SC-008

One run covering a rejection, a routing decision, a failure, a retry and a terminal outcome must
produce all **10** event types (the 8 from §4.9 plus `DELIVERY_EXPIRED` and
`RETRY_BUDGET_EXHAUSTED` from FR-053), all retrievable by `correlationId` (FR-048) and all
timestamped (FR-049).

### 10. Nothing sensitive leaks — SC-009 (merge blocker)

Submit content containing a unique marker string. After a full lifecycle including a failure and
a retry, search **audit records, application logs and metric labels** for that marker. Expect
**zero** occurrences, plus zero credentials and zero unmasked recipient references in audit or
logs (FR-047, FR-051, FR-052, FR-056).

Note the asymmetry after D5: the recipient reference **may** appear in a status response, which
is addressed to the system that supplied it, but must be masked in audit and logs.

Include the case where the simulated provider echoes submitted content in its error text: the
`diagnostic` field must not carry it through.

### 11. Concurrent workers do not double-attempt — Principle II

Run two workers against one delivery. Expect exactly one `IN_PROGRESS` claim and exactly one
attempt row per attempt number.

## Test layers (constitution Principle VI)

| Layer | Location | Gate |
|-------|----------|------|
| Unit / truth tables | `src/test/java/.../unit` | routing table, retry matrix, every transition including illegal ones |
| Contract | `.../contract` | conformance to `contracts/openapi.yaml` |
| Integration | `.../integration` | Testcontainers; scenarios 1, 3, 4, 6–9, 11 |
| Architecture | `.../architecture` | ArchUnit — `domain` imports no framework |
| Privacy | `.../privacy` | scenario 10; blocking |

**Determinism rules**: no `Thread.sleep`, no `Awaitility`, no real network, no reliance on test
order. Time advances through the injected clock; the worker is invoked directly rather than
waited on. A flaky test is a failing test — fix or remove it in the same change.

```bash
./gradlew test          # all layers
./gradlew archTest      # architecture gate alone
./gradlew jacocoTestCoverageVerification   # 90% domain / 80% overall
```

## Known limitations to state in DO-004

Carried from the spec and plan; a reviewer should see these without asking.

- **G-26** — §4.3's recipient-preference routing factor is **not implemented**. Three of four
  factors are. The source supplies no preference data and defines no preference source (G-05).
  This capability must not be described as satisfying §4.3.
- **G-12** — no idempotency, deduplication or replay. Duplicates are accepted as independent
  notifications (D4).
- **G-22** — no real provider integration is proven; all providers are simulated.
- **G-32** — **delivery needs a destination the source never supplies.** A recipient is an opaque
  reference (D5); §4.1 defines no address field and no section defines a directory or resolution
  mechanism. The prototype passes the reference to a simulated provider. A real deployment needs
  a resolution step this specification does not describe.
- **G-01** — the source document skips §4.4, 4.6, 4.7 and 4.8. It is an excerpt, so unmet
  obligations may exist.
- **G-11** — no throughput, latency, volume or retention target is asserted, because the source
  states none.
- **G-15** — `priority` is captured and returned but drives no behavior.
- **G-25** — content payload retention after a terminal state is undecided.
- **U-1 … U-5** — five implementation decisions deliberately left open in
  [research.md](./research.md); each must be settled before the code depending on it is written.
  U-4 is narrowed by D5 to masking the recipient reference alone.
