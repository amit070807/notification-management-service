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

No **real** provider credentials are required: every channel provider is simulated, push included, and the
repository holds no secrets, only `.env.example`. Push is the one channel that still needs a credential
*value* to be present — any string will do, because nothing authenticates it — since a missing one is
reported as `AUTH_ERROR` by design (FR-115). See the push walkthrough below.

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
    minimumSeverity: MEDIUM   # camelCase: RoutingPolicyLoader reads raw YAML, not relaxed-bound
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

### Testing the phase-2 features, step by step

Four user stories shipped in this phase. Each has a test filter that proves it in seconds, and a recipe
for watching it happen against a running service.

**Prove all four from the suite** — deterministic, and the only route that covers the failure paths:

```bash
./gradlew test --tests '*Push*'                                    # US1 — push channel (20 tests)
./gradlew test --tests '*PerProviderRetry*' --tests '*ProviderErrorMapping*' \
               --tests '*ChannelExtensibility*'                    # US2 — provider consolidation (18)
./gradlew test --tests '*Duplicate*' --tests '*Dedup*' --tests '*Reclaim*' \
               --tests '*Stranded*' --tests '*IdempotencyKey*'     # US3 — dedup and reclaim (50)
./gradlew test --tests '*RetryAudit*' --tests '*RetryPairing*' \
               --tests '*AuditCompleteness*'                       # US4 — retry audit trail (16)
```

#### US1 — push delivers, and is inert until you enable it

Enable the channel in `src/main/resources/routing-policy.yaml` and restart. The policy `enabled` key
**is** the push feature flag; there is no separate boolean (ADR-017).

```yaml
channels:
  PUSH:
    enabled: true
```

Push also needs a credential value, or every attempt is a terminal `AUTH_ERROR` — see below. Any string
works; nothing authenticates it. In `application.yaml`:

```yaml
notification:
  channel:
    credentials:
      PUSH:
        token: ${PUSH_TOKEN:local-dev-token}
        token-ref: env:PUSH_TOKEN
```

```bash
curl -s -X POST localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' -d '{
  "clientNotificationId":"push-demo","sourceSystem":"billing","correlationId":"corr-push-demo",
  "notificationType":"ALERT","severity":"HIGH","priority":"NORMAL",
  "recipients":["device-1"],"requestedChannels":["PUSH"],
  "createdAt":"2026-09-07T10:00:00Z","content":{"body":"hello"}}'
```

Take the `id` from the response and read the status back. Expect a `PUSH` delivery reaching `DELIVERED`,
reported per recipient and channel exactly as `EMAIL` and `SMS` are.

**Remove the credential and try again** — this is the failure path worth seeing, not a misconfiguration
to avoid. Every attempt returns `AUTH_ERROR`, which is terminal: the delivery reaches `FAILED` on attempt
one with no retries, because a credential that is missing now will still be missing in thirty seconds
(FR-115). Nothing refuses this at startup, so an operator who enables push and forgets the token gets a
channel that fails 100% of the time and says why only in the audit trail.

Now set `enabled: false`, restart, and submit the same thing. Expect `202` with **no push delivery**, and
`channelOutcomes` carrying `CHANNEL_DISABLED`. The point is that a disabled channel is *explained* in the
recorded routing decision rather than silently absent — which is why no second switch was introduced.

#### US2 — a provider may differ in schedule, never opt out of the bound

Give push its own backoff, then confirm the bound is not negotiable:

```yaml
notification:
  retry:
    overrides:
      PUSH: { base-delay: PT5S, ceiling: PT120S }
```

That is honoured. Now try to exceed the maximum:

```yaml
notification:
  retry:
    overrides:
      PUSH: { max-attempts: 99 }
```

The application **refuses to start**, naming the channel and the limit. `MAX_ALLOWED_ATTEMPTS` is 10, and
an override above it is a configuration error worth failing on rather than honouring quietly — an
unbounded channel is what source 4.5 forbids, and a plausible-looking schedule is exactly how it would
go unnoticed.

#### US3a — a duplicate submission is suppressed, and says so

Set `dedup-submission: true`, restart, then submit the same `(sourceSystem, correlationId)` twice:

```bash
for i in 1 2; do
  curl -s -X POST localhost:8080/api/v1/notifications \
    -H 'Content-Type: application/json' -d '{
    "clientNotificationId":"dup-demo","sourceSystem":"billing","correlationId":"corr-dup-demo",
    "notificationType":"ALERT","severity":"HIGH","priority":"NORMAL",
    "recipients":["user-1"],"requestedChannels":["EMAIL"],
    "createdAt":"2026-09-07T10:00:00Z","content":{"body":"hello"}}' \
    -o /dev/null -w "attempt $i -> %{http_code}\n"
done
```

Expect `202` then **`200`**. Check the **status code alone** first, as that loop does: it is what a caller
that ignores unfamiliar response fields would see, and a `202` carrying a `suppressed` flag would pass a
body-reading test while still letting such a caller believe its notification was on its way (FR-144a).

#### US3b — a delivery stranded mid-attempt is reclaimed

Set `delivery-reclaim: true`, restart, and submit anything. Then stage the state a dead worker leaves
behind — an `IN_PROGRESS` delivery whose lease has expired:

```bash
docker compose exec -T postgres psql -U notifications -d notifications -c "
UPDATE delivery SET state = 'IN_PROGRESS', claimed_until = now() - interval '5 minutes'
WHERE id = (SELECT d.id FROM delivery d
            JOIN notification n ON n.id = d.notification_id
            WHERE n.client_notification_id = 'reclaim-demo' AND d.channel = 'EMAIL');"
```

Within a poll or two the worker reclaims it and drives it to a terminal state. Watch the audit trail:

```bash
docker compose exec -T postgres psql -U notifications -d notifications -c "
SELECT event_type, occurred_at, payload
FROM audit_event
WHERE notification_id = (SELECT id FROM notification WHERE client_notification_id = 'reclaim-demo')
ORDER BY sequence;"
```

Expect a `DELIVERY_RECLAIMED` record carrying the attempts already made — a reclaim does **not** refund
the retry budget, or a provider that crashes the worker every time would produce unbounded attempts
while each individual reclaim looked reasonable.

**Be honest about what this demo is.** Hand-writing the row fabricates a state rather than reaching it,
and that distinction has already cost this project once: the reclaim fixture used to write the row by
hand, and before the provider call moved out of the transaction it was describing a state the system
could not actually produce, so the tests passed while proving nothing. `StrandedDeliveryReclaimTest`
now strands a delivery by genuinely crashing the provider mid-attempt. That is the version to trust;
this one is only for watching the recovery happen.

#### US4 — scheduling and execution are distinguishable, and paired

Make a channel fail twice, then succeed:

```yaml
notification:
  channel:
    simulate:
      EMAIL: { fail-with: TRANSIENT_PROVIDER_FAILURE, fail-first-attempts: 2 }
```

Submit, let the retries run, then join the two record types on the reference they share:

```bash
docker compose exec -T postgres psql -U notifications -d notifications -c "
SELECT event_type,
       payload->>'attemptNumber' AS attempt,
       payload->>'scheduledRef'  AS scheduled_ref
FROM audit_event
WHERE notification_id = (SELECT id FROM notification WHERE client_notification_id = 'retry-demo')
  AND event_type IN ('RETRY_SCHEDULED', 'RETRY_EXECUTED')
ORDER BY sequence;"
```

Each `RETRY_EXECUTED` carries the same `scheduledRef` as the `RETRY_SCHEDULED` that caused it, so across
several retries a reader can pair them without knowing how the reference is derived. Both records existed
before this phase; nothing tied them together.

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
