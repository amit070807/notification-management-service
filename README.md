# Notification Management Service

Accepts notification requests, selects delivery channels, delivers asynchronously over email, SMS and
push with bounded retry, suppresses duplicate submissions, recovers deliveries stranded by a crash, and
exposes delivery status and audit history.

Java 21 · Spring Boot · Gradle · PostgreSQL 16

> **Two requirements are knowingly unmet**, and neither is an oversight — in both cases the source
> document names an input it never supplies. A third capability is **assumed rather than implemented**:
> provider-side deduplication. See
> [Requirements not met](docs/architecture.md#2-requirements-this-service-does-not-meet).
> This service **must not be described as satisfying §4.3.**

## Prerequisites

| | Version | Notes |
|---|---|---|
| JDK | 21 | The Gradle toolchain pins it |
| Docker | Engine 25+ | PostgreSQL, and Testcontainers for integration tests. The build negotiates API 1.44, which Engine 25 and later accept |
| Gradle | none needed | Use the committed wrapper (`./gradlew`) |

No credentials are required. All channel providers are simulated, push included; the repository contains
no real secrets, only `.env.example`.

## Run it

```bash
cp .env.example .env      # set POSTGRES_PASSWORD before the first run
docker compose up -d      # PostgreSQL 16
./gradlew bootRun         # Flyway migrates on startup
```

Service on `http://localhost:8080`. Health at `/actuator/health`.

`.env` is read by docker compose **and** by the application (`spring.config.import`), so both agree on
the password without exporting anything. A deployment supplying real environment variables needs no
`.env` — the import is declared `optional:`.

> **If you see `password authentication failed`**: PostgreSQL fixes its credentials on **first** start,
> so editing `POSTGRES_PASSWORD` afterwards has no effect on an existing volume. Reset with
> `docker compose down -v && docker compose up -d`.

## Feature flags — everything new is off by default

```yaml
notification:
  features:
    dedup-submission: false   # suppress duplicate submissions at the event boundary
    dedup-delivery: false     # send a stable idempotency key on every provider call
    delivery-reclaim: false   # recover deliveries stranded mid-attempt
```

With all three off, the service behaves exactly as it did before these features existed — asserted by
`FlagsOffBaselineTest` and `ExistingChannelsUnchangedTest`, not merely intended.

The flags need a **restart** to change. Suppression is irreversible, so flipping one mid-flight would
suppress across two states of the world with no clean boundary (ADR-016).

**Before enabling `dedup-submission` for any caller, audit their event-identifier usage.** The boundary
is `(source system, correlation id)` and correctness rests on that pair being unique per event — a
contract this service cannot enforce. The query to check it, and what a rollback cannot recover, are in
[docs/migration-phase2.md](docs/migration-phase2.md).

The push channel has **no flag here**. It is switched by the routing policy's existing per-channel
`enabled` setting, which also records *why* a channel was not selected — a second switch would create
two sources of truth for one question (ADR-017).

```yaml
# src/main/resources/routing-policy.yaml
channels:
  PUSH:
    enabled: true
    minimum-severity: MEDIUM
```

Push credentials are configuration, and never reach a response, a log or a metric label:

```yaml
notification:
  channel:
    credentials:
      PUSH:
        token: ${PUSH_TOKEN}
        token-ref: env:PUSH_TOKEN
```

Push also takes its own retry schedule, because a provider may differ in how patiently it backs off —
but **no channel may opt out of being bounded**. An override above `MAX_ALLOWED_ATTEMPTS` throws at
startup rather than being honoured quietly:

```yaml
notification:
  retry:
    overrides:
      PUSH: { base-delay: PT5S, ceiling: PT120S }
```

## Try it

**Submit** — returns `202` with a server-issued id:

```bash
curl -s -X POST localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' -d '{
  "clientNotificationId":"demo-1","sourceSystem":"billing","correlationId":"corr-1",
  "notificationType":"ALERT","severity":"HIGH","priority":"NORMAL",
  "recipients":["user-1","user-2"],"requestedChannels":["EMAIL","SMS","PUSH"],
  "createdAt":"2026-09-07T10:00:00Z","content":{"body":"hello"}}'
```

**Retrieve status** — the id from above, not the client identifier:

```bash
curl -s localhost:8080/api/v1/notifications/{id}/status | python3 -m json.tool
```

**A suppressed duplicate** — with `dedup-submission: true`, resubmitting the same
`(sourceSystem, correlationId)` returns **`200`, not `202`**:

```json
{
  "suppressed": true,
  "originalNotificationId": "3f9a...",
  "clientNotificationId": "demo-1",
  "suppressedAt": "2026-09-10T12:00:00Z"
}
```

The **status code** is the signal, deliberately. `202 Accepted` means accepted for processing, and a
suppressed submission creates no delivery — a caller that ignores response fields it does not recognise
would otherwise see `202` and believe its notification was on its way. There is no `Location` header
either, because there is no new resource (ADR-021).

### Things worth trying

| Try | Expect | Why |
|---|---|---|
| The same `correlationId` twice, flag **off** | **Two different ids**, both `202` | Phase-1 behaviour, preserved exactly by FR-105 |
| The same `correlationId` twice, flag **on** | `202` then **`200`** with `suppressed: true` | The event boundary, suppressed rather than rejected |
| The same `correlationId` after a **failed** original | `202` — not suppressed | Suppressing against a terminally failed original would leave the caller no path to sending it at all (FR-141c) |
| The same `clientNotificationId` twice | **Two different ids** | The client identifier is descriptive, not identifying, and is *not* the boundary (FR-008a, D9) |
| `"severity":"LOW"` with `["SMS"]` only | `202`, but **zero** deliveries | The policy gates SMS at HIGH. Check `channelOutcomes` for `POLICY_EXCLUDED` |
| `["PUSH"]` with push `enabled: false` | `202`, zero deliveries, a recorded push outcome | Disabled is recorded with a reason, not silent |
| Empty `recipients` and no `content` | One `400` naming **both** fields | Rejections report every offending field, not the first |
| `"expiresAt"` in the past | `400` | Work that provably cannot be performed is refused up front |

**See retry and failures** — edit `src/main/resources/application.yaml`, then restart:

```yaml
notification:
  channel:
    simulate:
      EMAIL: { fail-with: TRANSIENT_PROVIDER_FAILURE, fail-first-attempts: 2 }
```

Two failures then success on attempt 3. Drop `fail-first-attempts` for `EXHAUSTED` at attempt 5. Use
`PERMANENT_PROVIDER_REJECTION` for `FAILED` on attempt 1 — no budget spent on a failure that cannot
succeed. Failures are configured, never carried in a request (ADR-015).

The retry now leaves a legible trail: a `RETRY_SCHEDULED` and a `RETRY_EXECUTED` record share a
`scheduledRef`, so across several retries each execution joins to the scheduling that caused it.

**Change routing** — `src/main/resources/routing-policy.yaml`, then restart. The policy is fixed at
startup on purpose (ADR-014).

## Tests

```bash
./gradlew test          # all layers except performance
./gradlew archTest      # architecture gate alone, ~4s
./gradlew privacyTest   # the Principle V merge blocker
./gradlew check         # everything plus coverage floors
./gradlew perfTest      # measurement only, asserts nothing
```

Docker must be running. **308 tests, 0 failures.** See [docs/testing.md](docs/testing.md) for the
approach, the twelve defects the tests caught, and the full limitations list.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Components, control flow, key decisions, state model, deduplication, privacy design |
| [docs/testing.md](docs/testing.md) | Testing approach, what the tests found, limitations, trade-offs |
| [docs/migration-phase2.md](docs/migration-phase2.md) | Enabling and reversing each enhancement, and **what rollback cannot recover** |
| [docs/performance-phase2.md](docs/performance-phase2.md) | Measured cost of each enhancement. Asserts no threshold, because no source document states one |
| [docs/constitution-compliance.md](docs/constitution-compliance.md) | Review evidence against every constitutional principle |
| [core spec](specs/001-notification-management-core/spec.md) | 68 requirements, each tagged by provenance; 32-entry gap register |
| [brownfield spec](specs/002-push-dedup-refactor/spec.md) | 49 requirements, 14 baseline facts, 27 gaps, 9 recorded decisions |
| [core ADRs](specs/001-notification-management-core/research.md) | ADR-001 … ADR-015 |
| [brownfield ADRs](specs/002-push-dedup-refactor/research.md) | ADR-016 … ADR-022 |
| [data-model.md](specs/001-notification-management-core/data-model.md) | Tables, invariants, state machines, rollup rule |
| [openapi.yaml](specs/001-notification-management-core/contracts/openapi.yaml) | The authoritative API contract |
| [constitution.md](.specify/memory/constitution.md) | The engineering rules this codebase is held to |

## Layout

```
src/main/java/com/notification/
├── api/           controllers, DTOs, validation, error handling
├── application/   orchestration and transaction boundaries
├── domain/        model, routing, retry, dedup, state machines, ports — no framework imports
├── persistence/   JdbcClient adapters, explicit SQL
├── worker/        outbox poller, delivery worker
├── channel/       AbstractChannelProvider + one subclass per channel
├── audit/         audit recorder, masking, sealed payload allowlist
└── config/        wiring, feature flags, configuration properties
```

The domain depends on nothing outward. `ArchitectureRulesTest` enforces that as a blocking gate.
Adding a channel requires only a subclass supplying a provider call and an error-code map —
`ChannelExtensibilityTest` fails if that stops being true.
