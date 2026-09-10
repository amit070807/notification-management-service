# Notification Management Service

Accepts notification requests, selects delivery channels, delivers asynchronously over email, SMS and
push with bounded retry, claims higher-severity work first, suppresses duplicate submissions, recovers
deliveries stranded by a crash, and exposes delivery status and audit history.

Java 21 · Spring Boot · Gradle · PostgreSQL 16

> **Two requirements are knowingly unmet**, and neither is an oversight — in both cases the source
> document names an input it never supplies. One capability is **assumed rather than implemented**:
> provider-side deduplication. And one behaviour is **deliberately hazardous**: with severity ordering
> enabled, low-severity deliveries can be starved without bound, and nothing signals it. All four are
> stated in
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
    dedup-submission: false      # suppress duplicate submissions at the event boundary
    dedup-delivery: false        # send a stable idempotency key on every provider call
    delivery-reclaim: false      # recover deliveries stranded mid-attempt
    severity-claim-order: false  # claim higher-severity deliveries first
```

With all four off, the service behaves exactly as it did before these features existed — asserted by
`FlagsOffBaselineTest`, `ExistingChannelsUnchangedTest` and `ClaimOrderUnchangedTest`, not merely
intended.

The flags need a **restart** to change. Suppression is irreversible, so flipping one mid-flight would
suppress across two states of the world with no clean boundary (ADR-016).

**Before enabling `dedup-submission` for any caller, audit their event-identifier usage.** The boundary
is `(source system, correlation id)` and correctness rests on that pair being unique per event — a
contract this service cannot enforce. The query to check it, and what a rollback cannot recover, are in
[docs/migration-phase2.md](docs/migration-phase2.md).

**Before enabling `severity-claim-order`, read what it permits.** `CRITICAL` notifications are claimed
before `LOW` ones, and low-severity deliveries can then be starved **without bound** — under sustained
high-severity traffic a `LOW` delivery may never be claimed. That is a deliberate decision, not a defect,
and it cannot be capped: an aged `LOW` delivery sitting unclaimed is *correct*, and nothing in the system
distinguishes it from a stalled worker. Turning the flag off is the only mitigation, and it releases the
backlog immediately. Details and the query to inspect a starved queue are in
[docs/migration-phase2.md](docs/migration-phase2.md).

The batch size is now configurable at its existing default, because severity ordering is only observable
when the backlog exceeds one batch:

```yaml
notification:
  worker:
    batch-size: 50   # below 1 is rejected at startup
```

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
| A `LOW` then a `CRITICAL` submission with `severity-claim-order: true` and `batch-size: 1` | The **CRITICAL** one attempted first | Severity leads the claim order despite the LOW one being older (FR-201) |
| Two `HIGH` submissions, ordering on | The **older** one first | Severity refines the age order rather than replacing it (FR-203) |
| A steady stream of `CRITICAL` work beside one `LOW` delivery | The `LOW` one **never** claimed | Unbounded starvation, accepted by decision D-15. Set the flag to false to release it |
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

### Testing severity-ordered claim, step by step

**The fastest proof is the suite.** Twenty-five tests cover this feature, and unlike a manual run they
are deterministic:

```bash
./gradlew test --tests '*Severity*' --tests '*ClaimOrder*' \
               --tests '*ClaimEligibility*' --tests '*WorkerProperties*'
```

That includes the pairwise rank truth table, the four-severity claim order, the batch being *processed*
in claim order, reclaims being ordered rather than privileged, starvation asserted as intended, and the
flag-off baseline.

**To watch it happen instead**, two properties and a restart:

```yaml
# src/main/resources/application.yaml
notification:
  features:
    severity-claim-order: true
  worker:
    batch-size: 1     # one delivery per poll, so the order is observable at all
```

Batch size matters here. With the default of 50 the whole backlog is claimed in one poll and the
ordering question becomes *processing* order rather than claim order — a different requirement (FR-205),
tested separately.

Now submit a `LOW` notification and then a `CRITICAL` one, both held until the same instant. The hold is
what makes this deterministic: the worker polls every second, so without it the older `LOW` delivery is
simply gone before the `CRITICAL` one exists, and you learn nothing.

```bash
DUE=$(python3 -c "import datetime as d; print((d.datetime.now(d.timezone.utc)+d.timedelta(minutes=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))")

for S in LOW CRITICAL; do
  curl -s -X POST localhost:8080/api/v1/notifications \
    -H 'Content-Type: application/json' -d "{
    \"clientNotificationId\":\"sev-$S\",\"sourceSystem\":\"billing\",\"correlationId\":\"corr-sev-$S\",
    \"notificationType\":\"ALERT\",\"severity\":\"$S\",\"priority\":\"NORMAL\",
    \"recipients\":[\"user-1\"],\"requestedChannels\":[\"EMAIL\"],
    \"createdAt\":\"2026-09-07T10:00:00Z\",\"notBefore\":\"$DUE\",
    \"content\":{\"body\":\"hello\"}}" -o /dev/null -w "$S -> %{http_code}\n"
done
```

`LOW` goes first, so it is the **older** of the two. Wait for the two minutes to pass, then ask the
database what order the attempts actually happened in:

```bash
docker compose exec -T postgres psql -U notifications -d notifications -c "
SELECT n.severity, d.channel, a.attempt_number, a.started_at
FROM delivery_attempt a
JOIN delivery d ON d.id = a.delivery_id
JOIN notification n ON n.id = d.notification_id
WHERE n.client_notification_id LIKE 'sev-%'
ORDER BY a.started_at;"
```

Expect `CRITICAL` first despite being younger. Expect **two** `CRITICAL` rows: the policy escalates to
`SMS` at `CRITICAL`, so that submission produces an SMS delivery as well as the EMAIL one it asked for,
and both outrank `LOW`.

Claim order is not visible through the API, only in the database (G-63). That is a deliberate limitation
of a feature this size, not an oversight.

**Prove the starvation is real**, because it is the part most likely to be mistaken for a bug. Leave the
`LOW` delivery undelivered, keep `CRITICAL` work arriving, and look at what is still queued:

```bash
docker compose exec -T postgres psql -U notifications -d notifications -c "
SELECT n.severity, n.client_notification_id, d.state, d.state_changed_at
FROM delivery d
JOIN notification n ON n.id = d.notification_id
WHERE d.state IN ('QUEUED', 'RETRY_SCHEDULED')
ORDER BY d.state_changed_at;"
```

An aged `LOW` row sitting in `QUEUED` while higher-severity work flows past it is **correct behaviour**,
accepted by decision D-15. Nothing in the system distinguishes it from a stalled worker (G-64).

Note what this query deliberately does not do: order by `n.severity`. Severity is stored as `text`, so a
lexicographic sort gives `CRITICAL, HIGH, LOW, MEDIUM` — the top two right and the bottom two inverted.
That near-miss is gap G-60, and it is the single easiest way to get this feature wrong.

**Then prove the rollback**, since it is the only mitigation the design offers:

```yaml
notification:
  features:
    severity-claim-order: false
```

Restart, and the starved `LOW` delivery is claimed on the next poll. Claim order returns to oldest-first
and severity stops influencing it entirely.

## Tests

```bash
./gradlew test          # all layers except performance
./gradlew archTest      # architecture gate alone, ~4s
./gradlew privacyTest   # the Principle V merge blocker
./gradlew check         # everything plus coverage floors
./gradlew perfTest      # measurement only, asserts nothing
```

Docker must be running. **335 tests, 0 failures** from clean. See [docs/testing.md](docs/testing.md) for
the approach, the sixteen defects the tests caught, and the full limitations list.

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
| [severity-claim spec](specs/003-severity-claim-order/spec.md) | One ordering rule; decisions D-15 and D-16; the starvation G-64 accepts |
| [core ADRs](specs/001-notification-management-core/research.md) | ADR-001 … ADR-015 |
| [brownfield ADRs](specs/002-push-dedup-refactor/research.md) | ADR-016 … ADR-024 |
| [severity-claim ADRs](specs/003-severity-claim-order/research.md) | ADR-025 … ADR-030 |
| [data-model.md](specs/001-notification-management-core/data-model.md) | Tables, invariants, state machines, rollup rule |
| [openapi.yaml](specs/001-notification-management-core/contracts/openapi.yaml) | The authoritative API contract |
| [constitution.md](.specify/memory/constitution.md) | The engineering rules this codebase is held to |

## Layout

```
src/main/java/com/notification/
├── api/           controllers, DTOs, validation, error handling
├── application/   orchestration and transaction boundaries
├── domain/        model, routing, retry, dedup, severity rank, state machines, ports — no framework imports
├── persistence/   JdbcClient adapters, explicit SQL
├── worker/        outbox poller, delivery worker
├── channel/       AbstractChannelProvider + one subclass per channel
├── audit/         audit recorder, masking, sealed payload allowlist
└── config/        wiring, feature flags, configuration properties
```

The domain depends on nothing outward. `ArchitectureRulesTest` enforces that as a blocking gate.
Adding a channel requires only a subclass supplying a provider call and an error-code map —
`ChannelExtensibilityTest` fails if that stops being true.
