# Architecture Overview

**Deliverable DO-002** (source §5) · Service: notification-management-service · 2026-09-07

Governing documents: [constitution](../.specify/memory/constitution.md) v2.0.0 ·
[specification](../specs/001-notification-management-core/spec.md) ·
[plan](../specs/001-notification-management-core/plan.md) ·
[ADRs](../specs/001-notification-management-core/research.md)

---

## 1. What this service does

Accepts notification requests, selects delivery channels, delivers asynchronously with bounded
retry, and exposes both delivery status and an audit history. Two HTTP APIs, one in-process
worker, one PostgreSQL database, one deployable unit.

## 2. Requirements this iteration does NOT meet

Stated first, because a reader deserves to know the shape of the gap before the design.

### §4.3 recipient-preference routing factor — **not implemented** (spec G-26)

Source §4.3 names four routing factors: requested channel, notification severity, **recipient
preferences**, and routing policy. This service implements **three**.

The reason is in the source, not in the schedule: §4.3 names preferences as an input, but §4.1's
submission field list does not carry them and no section of §3–§5 defines a preference store,
owner, shape, or lifecycle (spec G-05). **The document requires an input it never makes
obtainable.** Implementing the factor would have meant inventing both a data source and a
preference model, then presenting the result as a requirement.

**This service must not be described as satisfying §4.3.** Closing the gap requires answering
G-05 first — where preferences come from and what they look like.

The deferral is actively defended, not merely documented: `RoutingRequest` carries exactly three
factors and no field through which a recipient attribute could reach a decision, and
`RoutingPreferenceIndependenceTest` fails if a recipient-shaped component is ever added (FR-019a).

### Delivery has no destination data (spec G-32)

Attempting delivery on a channel requires an address, endpoint or token. §4.1 defines no such
field, and no section defines a directory or resolution mechanism — yet §3.1 ("delivery attempts")
and §4.5 ("invalid recipient") both presuppose a resolvable destination.

A recipient is therefore modelled as an **opaque reference and nothing else** (spec D5). The
reference is passed to the channel provider unchanged. How a real provider would resolve it is
undefined by the source and is not invented here.

Full list: [testing.md § Limitations](./testing.md#limitations).

## 3. Components

```
                    ┌──────────────────────────────────────────┐
   source system ──▶│ api/          controllers, DTO validation │
                    └──────────────┬───────────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────────┐
                    │ application/  SubmissionService (1 tx)    │
                    │               StatusQueryService          │
                    │               DeliveryProcessingService   │
                    └──────────────┬───────────────────────────┘
                                   │  depends only on ports
                    ┌──────────────▼───────────────────────────┐
                    │ domain/       model, routing, retry,      │
                    │               state machines, ports       │
                    │               NO framework imports        │
                    └──────────────┬───────────────────────────┘
                                   │  implemented by
        ┌──────────────┬───────────┴────────┬──────────────────┐
        ▼              ▼                    ▼                  ▼
   persistence/    worker/              channel/            audit/
   JdbcClient      OutboxPoller         Simulated*Provider  AuditRecorder
   explicit SQL    DeliveryWorker       (ChannelProviderPort)
        │              │                    │                  │
        └──────────────┴────────┬───────────┴──────────────────┘
                                ▼
                          PostgreSQL 16
```

The dependency direction is inward: adapters depend on the domain, never the reverse. The domain
declares what it needs as ports and the adapters come to it. This is enforced by
`ArchitectureRulesTest` as a blocking CI gate, not by convention (Principle VII, ADR-002).

## 4. Control flow

### Submission — the acceptance transaction

```
POST /api/v1/notifications
  │
  ├─ Jakarta Validation on the DTO ──── fails ─▶ 400 naming EVERY offending field
  │                                              + NOTIFICATION_REJECTED audit record
  │                                              (no notification row is created)
  ▼
  SubmissionService.accept()  ── ONE @Transactional unit ──┐
      1. notification_content   (payload, separate table)  │
      2. notification                                      │
      3. recipient rows                                    │
      4. ChannelRouter.route()  → routing_decision         │  commit
      5. delivery rows, one per SELECTED (recipient, chan) │
      6. audit events                                      │
      7. outbox row                                        │
  ────────────────────────────────────────────────────────┘
  ▼
202 Accepted + Location  ← nothing has been delivered, and no provider was touched
```

**Why one transaction**: the common failure of accept-then-process is acknowledging work that was
never durably recorded, so a status read moments later says "unknown" — the API lying about work
it claimed. Everything the status API will need is committed before the caller is told anything
(Principle II, FR-015).

### Delivery

```
OutboxPoller.drainOnce()          DeliveryWorker.runOnce()
  claim outbox rows                 claim due deliveries
  FOR UPDATE SKIP LOCKED            FOR UPDATE SKIP LOCKED
  PENDING → QUEUED                    │
                                      ▼
                          DeliveryProcessingService.process()
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
              expired?          not yet due?      channel in the
              → EXPIRED         → release lease   recorded decision?
                                                  → else UNDELIVERABLE
                                      │
                                      ▼
                             ChannelProviderPort.send()
                                      │
                    ┌─────────────────┴─────────────────┐
                    ▼                                   ▼
                 success                             failure
              → DELIVERED                    classify → Retryability
                                                        │
                                      ┌─────────────────┼──────────────┐
                                      ▼                 ▼              ▼
                              retryable +        retryable,      not retryable
                              budget left        budget spent    → FAILED
                              → RETRY_SCHEDULED  → EXHAUSTED     (+ alert if AUTH_ERROR)
```

Eligibility is re-checked immediately before **every** attempt, not once at first processing: a
delivery can cross the not-before or expiry boundary while waiting in backoff (FR-033).

## 5. Key decisions

Full reasoning in [research.md](../specs/001-notification-management-core/research.md); ADR-001
through ADR-015.

| Decision | Why |
|---|---|
| Java 21 + Spring Boot, Gradle, single project | Meets every Technology Selection criterion. Package boundaries with ArchUnit rather than build modules — the constitution requires a machine-checked rule, and simplicity is its default (ADR-001, ADR-002) |
| PostgreSQL + in-process transactional outbox | The outbox row commits with the entities, so the handoff cannot lose or phantom-create work. `SKIP LOCKED` makes a second instance safe with no coordination. H2 was rejected: its locking differs, so the concurrency test would prove nothing (ADR-003) |
| `JdbcClient`, explicit SQL, no ORM | No dirty-checking exists, so no accidental flush can mutate an append-only audit row. Immutability enforced by absence of a mechanism (ADR-004) |
| Duplicates accepted; server-issued identity is the retrieval key | §4.1 never requires the client identifier to be unique, and idempotency is deferred. Rejecting duplicates would have implemented the deferred behaviour; a surrogate key defines what happens without doing so (ADR-006, spec D4) |
| Two timestamps, not one | §4.1's single "scheduling/expiration timestamp" names two opposite constraints. Split into not-before and expiry (spec D3) |
| Routing policy fixed at startup | Determinism is then trivial: no in-flight submission can straddle two policy versions. No availability target exists to justify hot reload (ADR-014) |
| Simulated providers driven by configuration | A per-request directive would put a test affordance in the production contract and make the simulator an injection surface (ADR-015) |

## 6. State model

The source names no states at all (spec G-09); §4.2 explicitly permits a different model "if it is
documented and defensible". This is that documentation.

**Delivery**: `PENDING → QUEUED → IN_PROGRESS → {DELIVERED | RETRY_SCHEDULED | FAILED | EXHAUSTED}`,
with `EXPIRED` reachable from every non-terminal state and `UNDELIVERABLE` from `PENDING`.
Transition tables in [data-model.md](../specs/001-notification-management-core/data-model.md);
illegal transitions throw rather than being silently applied.

Three distinctions carry weight:

- **`EXHAUSTED` ≠ `FAILED`** — a flaky provider and an outright rejection both end unsuccessfully
  but mean different things operationally (FR-044).
- **`EXPIRED` is neither** — expiry is not a failure, and outranks the retry budget when a
  delivery crosses its window in backoff (FR-034).
- **`UNDELIVERABLE`** — routing selected nothing. The source does not address this case (G-20).

**Notification** state is a deterministic rollup of its deliveries, computed on read. The stored
column is a worker checkpoint; the derived value is authoritative, so status stays truthful even
if a worker died mid-update (FR-016).

## 7. Privacy design

Principle V is enforced structurally rather than by discipline:

- `AuditRepositoryPort` exposes no update or delete method; `V2__grants.sql` withholds the
  privileges so a raw SQL call cannot do it either.
- Audit payloads are a **sealed** allowlist — no member carries the content payload, so no code
  path can add it.
- Content is referenced by SHA-256 digest, never contained (FR-051).
- The recipient reference is masked as prefix-plus-digest in audit, logs and metric labels, but
  echoed unmasked in status responses — that response goes back to the system that supplied it,
  so masking would protect nothing (FR-052).
- Provider diagnostics must be code-shaped (`[A-Za-z0-9_.:-]{1,32}`) or they are redacted. A
  provider echoing the submitted message in its error text is a real leak path, and bounding the
  length alone did not stop it.

`NoSensitiveDataLeakTest` plants a marker and scans audit rows, captured logs and metric labels.
It is a merge blocker.

## 8. Execution approach

Single deployable. `OutboxPoller` and `DeliveryWorker` run in-process on a fixed delay, and both
expose a public `runOnce()`/`drainOnce()` invoked directly by tests — no test waits on a
scheduler, which would prove timing rather than behaviour.

Horizontal scaling needs no correctness change: both claim work with `FOR UPDATE SKIP LOCKED`.
This is designed for but not required — the source states no volume target (G-11).
