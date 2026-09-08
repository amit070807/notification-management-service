# Notification Management Service

Accepts notification requests, selects delivery channels, delivers asynchronously with bounded
retry, and exposes delivery status and audit history.

Java 21 · Spring Boot · Gradle · PostgreSQL 16

> **Two requirements are knowingly unmet**, and neither is an oversight — in both cases the source
> document names an input it never supplies. See
> [Requirements not met](docs/architecture.md#2-requirements-this-iteration-does-not-meet).
> This service **must not be described as satisfying §4.3.**

## Prerequisites

| | Version | Notes |
|---|---|---|
| JDK | 21 | The Gradle toolchain pins it |
| Docker | any recent | PostgreSQL, and Testcontainers for integration tests |
| Gradle | none needed | Use the committed wrapper (`./gradlew`) |

No credentials are required. All channel providers are simulated; the repository contains no real
secrets, only `.env.example`.

## Run it

```bash
cp .env.example .env      # set POSTGRES_PASSWORD before the first run
docker compose up -d      # PostgreSQL 16
./gradlew bootRun         # Flyway migrates on startup
```

Service on `http://localhost:8080`. Health at `/actuator/health`.

`.env` is read by docker compose **and** by the application (`spring.config.import`), so both
agree on the password without exporting anything. A deployment supplying real environment
variables needs no `.env` — the import is declared `optional:`.

> **If you see `password authentication failed`**: PostgreSQL fixes its credentials on **first**
> start, so editing `POSTGRES_PASSWORD` afterwards has no effect on an existing volume. Reset with
> `docker compose down -v && docker compose up -d`.

## Try it

**Submit** — returns `202` with a server-issued id:

```bash
curl -s -X POST localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' -d '{
  "clientNotificationId":"demo-1","sourceSystem":"billing","correlationId":"corr-1",
  "notificationType":"ALERT","severity":"HIGH","priority":"NORMAL",
  "recipients":["user-1","user-2"],"requestedChannels":["EMAIL","SMS"],
  "createdAt":"2026-09-07T10:00:00Z","content":{"body":"hello"}}'
```

**Retrieve status** — the id from above, not the client identifier:

```bash
curl -s localhost:8080/api/v1/notifications/{id}/status | python3 -m json.tool
```

Expect four delivery entries: two recipients × two channels, each with its own state.

### Things worth trying

| Try | Expect | Why |
|---|---|---|
| The same `clientNotificationId` twice | **Two different ids** | The client identifier is not unique; idempotency is deferred (spec D4) |
| `"severity":"LOW"` with `["SMS"]` only | `202`, but **zero** deliveries | The policy gates SMS at HIGH. Check `channelOutcomes` for `POLICY_EXCLUDED` |
| Empty `recipients` and no `content` | One `400` naming **both** fields | Rejections report every offending field, not the first |
| `"expiresAt"` in the past | `400` | Work that provably cannot be performed is refused up front |

**See retry and failures** — edit `src/main/resources/application.yaml`, then restart:

```yaml
notification:
  channel:
    simulate:
      EMAIL: { fail-with: TRANSIENT_PROVIDER_FAILURE, fail-first-attempts: 2 }
```

Two failures then success on attempt 3. Drop `fail-first-attempts` for `EXHAUSTED` at attempt 5.
Use `PERMANENT_PROVIDER_REJECTION` for `FAILED` on attempt 1 — no budget spent on a failure that
cannot succeed. Failures are configured, never carried in a request (ADR-015).

**Change routing** — `src/main/resources/routing-policy.yaml`, then restart. The policy is fixed
at startup on purpose (ADR-014).

## Tests

```bash
./gradlew test          # all five layers
./gradlew archTest      # architecture gate alone, ~4s
./gradlew check         # everything plus coverage floors
```

Docker must be running. See [docs/testing.md](docs/testing.md) for the approach, what the tests
caught, and the full limitations list.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Components, control flow, key decisions, state model, privacy design |
| [docs/testing.md](docs/testing.md) | Testing approach, limitations, trade-offs |
| [spec.md](specs/001-notification-management-core/spec.md) | 68 requirements, each tagged by provenance; 32-entry gap register |
| [plan.md](specs/001-notification-management-core/plan.md) | Technical context and constitution gate evaluation |
| [research.md](specs/001-notification-management-core/research.md) | ADR-001 … ADR-015 |
| [data-model.md](specs/001-notification-management-core/data-model.md) | Tables, invariants, state machines, rollup rule |
| [openapi.yaml](specs/001-notification-management-core/contracts/openapi.yaml) | The authoritative API contract |
| [constitution.md](.specify/memory/constitution.md) | The engineering rules this codebase is held to |

## Layout

```
src/main/java/com/notification/
├── api/           controllers, DTOs, validation, error handling
├── application/   orchestration and transaction boundaries
├── domain/        model, routing, retry, state machines, ports — no framework imports
├── persistence/   JdbcClient adapters, explicit SQL
├── worker/        outbox poller, delivery worker
├── channel/       provider adapters behind ChannelProviderPort
├── audit/         audit recorder, masking, sealed payload allowlist
└── config/        wiring and configuration properties
```

The domain depends on nothing outward. `ArchitectureRulesTest` enforces that as a blocking gate.
