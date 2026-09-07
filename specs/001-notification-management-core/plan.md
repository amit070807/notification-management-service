# Implementation Plan: Notification Management Core

**Branch**: `feature/phase-1-greenfield` | **Date**: 2026-09-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-notification-management-core/spec.md`

**Governing document**: `.specify/memory/constitution.md` v2.0.0

## Summary

Build a single-deployable notification management service that accepts notifications, selects
delivery channels, processes deliveries asynchronously with bounded retry, and exposes both
status and audit history — implementing the functional requirements traced to
`Greenfield_Requirements.pdf` §3–§5 in [spec.md](./spec.md).

**Technical approach**: Java 21 + Spring Boot on Gradle, with a ports-and-adapters package layout
enforced by ArchUnit. PostgreSQL 16 is the single store, accessed through `JdbcClient` with
explicit SQL — no ORM. Acceptance commits the notification, its recipients, the routing decision,
the deliveries, the audit events and an outbox row in one local transaction, then returns 202;
an in-process poller drains the outbox and a worker claims deliveries with
`SELECT … FOR UPDATE SKIP LOCKED`. Time and identifier generation reach the domain only through
injected ports, so bounded retry and expiry are testable without real waits.

**Decision authority**: every technology choice below was made by the project owner on
2026-09-07, closing the constitution's `TODO(TECH_STACK)` (register item 12). Nothing in this
plan is assumed; open items are listed under Unresolved rather than defaulted.

## Technical Context

**Language/Version**: Java 21 (LTS). Records and sealed interfaces express the closed failure
taxonomy and state machines that Principle IV requires.

**Build**: Gradle (Kotlin DSL), **single project**. Owner decision: the seven layers of Principle
VII are packages, with ArchUnit as the machine-checked boundary gate.

**Primary Dependencies**: Spring Boot (Web, Validation, JDBC, Actuator, Tx), PostgreSQL JDBC
driver, Flyway (forward-only migrations), ArchUnit (dependency gate), Testcontainers (PostgreSQL),
JUnit 5 + AssertJ, Micrometer (metrics), logstash-logback-encoder (structured JSON logs), JaCoCo
(coverage gates). Contract tooling is listed under Unresolved.

**Storage**: PostgreSQL 16, accessed via Spring's `JdbcClient`. Explicit SQL, no ORM, no
dirty-checking. Sole store for notifications, recipients, deliveries, attempts, routing
decisions, audit events and the outbox — no second datastore (Principle VII, YAGNI).

**Async substrate**: transactional outbox table, polled in-process. No external broker.

**Channels**: `EMAIL` and `SMS` only (owner decision, closing spec G-18), both simulated behind
one provider port.

**Recipients**: an opaque reference and nothing more (spec D5). No address, contact value, or
per-channel addressing is submitted or stored, because the source defines no recipient field.

**Identity**: server-issued notification identity is the retrieval key; the client-supplied
identifier is descriptive and non-unique (spec D4).

**Testing**: JUnit 5 + AssertJ (unit, truth tables), Testcontainers PostgreSQL (integration,
concurrency), ArchUnit (architecture), JaCoCo (coverage floors). No `Thread.sleep`, no
`Awaitility`, no real network, no real clock — the worker is invoked directly and time advances
through the injected clock.

**Target Platform**: Linux container, JVM 21. Docker Compose starts PostgreSQL for local runs.

**Project Type**: Web service — one deployable exposing two HTTP APIs plus an in-process worker.

**Performance Goals**: **None asserted.** The source states no throughput, latency or volume
target (spec G-11). Any figure used while tuning is an engineering assumption, not a requirement.

**Constraints**: The constitution's Declared Operating Defaults — max 5 attempts; exponential
backoff from 1s, factor 2, ceiling 60s, jitter ±20%; provider timeouts 5s connect / 10s read;
audit retention ≥90 days; coverage floors 90% domain / 80% overall. All configurable; all
constitutional defaults rather than source requirements.

**Scale/Scope**: Not stated by the source (G-11). Designed so a second instance is safe to add
without a correctness change, but only one is required.

## Constitution Check

*GATE: evaluated before Phase 0; re-evaluated after Phase 1 design.*

| Principle | How this plan satisfies it | Verdict |
|-----------|----------------------------|---------|
| **I. Contract-First APIs** | `contracts/openapi.yaml` is hand-authored and authoritative. All enums closed (`Channel` = EMAIL, SMS). `recipients` carries `minItems: 1` (FR-005). Jakarta Validation at the controller boundary; one `@ControllerAdvice` produces the structured per-field rejection body (FR-004). Generation and conformance tooling: see Unresolved U-1. | **PASS** |
| **II. Durable Accept-Then-Process (NON-NEG)** | One `@Transactional` acceptance writing notification, recipients, routing decision, deliveries, audit events and outbox row; returns 202 + `Location`. Provider ports unreachable from `api` (ArchUnit). Outbox poller; worker claims with `FOR UPDATE SKIP LOCKED`; two-worker concurrency test. | **PASS** |
| **III. Deterministic Domain Core** | `ChannelRouter` is a pure function of `(RoutingRequest, RoutingPolicy)`; no clock, no randomness, no I/O. Decision persisted with `policy_version`. `ClockPort` and `IdPort` injected; ArchUnit forbids `Instant.now()` and `UUID.randomUUID()` inside `domain`. | **PASS** |
| **IV. State Machine + Failure Taxonomy (NON-NEG)** | Two enums with explicit legal-transition sets; illegal transitions throw. Six-value closed `FailureClassification` including `UNKNOWN`; retryability declared in one place. Expiry re-checked immediately before every attempt and outranking retry budget. Attempts guarded against the persisted routing decision. Tables in `data-model.md`. | **PASS** |
| **V. Audit Without Sensitive Data (NON-NEG)** | `audit_event` exposed through an append-only repository interface with no update or delete method; DB grant withholds both. Every row carries the correlation identifier. Payload is a typed per-event allowlist, so the content payload is structurally excluded. After D5 no contact values exist to protect; the recipient reference is masked in audit and logs. Marker-string privacy test is a merge blocker; gitleaks in CI. | **PASS** |
| **VI. Test-First Layered Deterministic (NON-NEG)** | `/speckit-tasks` will order each test before its implementation. Four layers scoped in `quickstart.md`. `SimulatedChannelProvider` implements the same port as any real adapter and emits every classification on demand. `MutableClock` advances time; the worker is invoked directly rather than waited on. | **PASS** |
| **VII. Modular Boundaries & Justified Simplicity** | Packages `api / application / domain / persistence / worker / channel / audit / config`. ArchUnit forbids `org.springframework`, `javax.sql`, `java.sql` and every adapter package from `domain`. Adding a channel touches only `channel/` plus config, proven by a fixture channel. No dependency beyond the list above without an ADR. | **PASS** |
| **VIII. Documented, Defensible, Engineer-Owned** | ADRs in `research.md` cover every owner decision and every design choice this plan makes. DO-001…DO-006 tracked, including the G-26 deviation. | **PASS** |

**Technology Selection criteria** (constitution): the stack supports transactional multi-entity
commit, a durable outbox, automated dependency testing, a controllable clock, and OpenAPI
contract testing — with no workaround. **Qualifies.**

**Result: all gates PASS. No violations.** The Complexity Tracking table is therefore omitted per
its own instruction.

### Carried-forward obligations (not violations)

| Item | Source | Obligation |
|------|--------|-----------|
| **G-26** — §4.3 recipient-preference factor unmet | spec D2 | `RoutingRequest` carries exactly three factors. No preference-shaped field under any other name (FR-019a). DO-006 requires it in the architecture overview and limitations. |
| **G-12** — idempotency deferred | constitution item 9 | D4 defines what happens on duplicates (both accepted, distinct identities) and supplies no deduplication semantics. Must appear in DO-004 limitations. |
| **G-32** — delivery needs a destination the source never supplies | spec D5 | The recipient reference is passed to the provider unchanged; no address data is stored or invented. Providers are simulated. Must appear in DO-004. |
| **G-01, G-11, G-15, G-25** | spec | Unresolved, non-blocking; DO-005 requires each in the limitations statement. |

## Project Structure

### Documentation (this feature)

```text
specs/001-notification-management-core/
├── spec.md              # Feature specification (D1-D4, gap register)
├── plan.md              # This file
├── research.md          # Phase 0: ADRs + resolved unknowns
├── data-model.md        # Phase 1: entities, invariants, state machines, rollup rule
├── quickstart.md        # Phase 1: run + validate end-to-end
├── contracts/
│   └── openapi.yaml     # Phase 1: authoritative API contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
build.gradle.kts
settings.gradle.kts
docker-compose.yml                        # PostgreSQL for local runs
src/main/java/com/notification/
├── api/                                  # Transport only
│   ├── NotificationController.java
│   ├── StatusController.java
│   ├── ApiExceptionHandler.java          # structured per-field rejection bodies
│   └── mapper/                           # DTO <-> domain
├── application/                          # Orchestration + transaction boundaries
│   ├── SubmissionService.java            # the single accept transaction (Principle II)
│   ├── DeliveryProcessingService.java
│   └── StatusQueryService.java
├── domain/                               # PURE. No Spring, no SQL, no adapters.
│   ├── model/                            # Notification, Recipient, Delivery, Attempt, ...
│   ├── routing/                          # ChannelRouter, RoutingPolicy, RoutingDecision
│   ├── retry/                            # RetryPolicy, FailureClassification, Retryability
│   ├── state/                            # DeliveryState, NotificationState, transitions, rollup
│   └── port/                             # ClockPort, IdPort, repositories, ChannelProviderPort
├── persistence/                          # JdbcClient adapters, row mappers, outbox repository
├── worker/                               # OutboxPoller, DeliveryWorker, RetryScheduler
├── channel/                              # ChannelProviderPort adapters (simulated EMAIL, SMS)
├── audit/                                # AuditRecorder + allowlisted payload types
└── config/                               # Spring wiring, properties, policy loading

src/main/resources/
├── db/migration/                         # Flyway V1__*.sql, forward-only
├── routing-policy.yaml                   # versioned, externally configurable (FR-026)
└── application.yaml

src/test/java/com/notification/
├── architecture/                         # ArchUnit rules (Principle VII gate)
├── unit/                                 # routing truth tables, retry matrix, transitions
├── contract/                             # against contracts/openapi.yaml
├── integration/                          # Testcontainers: full lifecycle, concurrency
└── privacy/                              # marker-string audit/log scan (Principle V gate)
```

**Structure Decision**: Single Gradle project, hexagonal package layout, boundaries enforced by
ArchUnit as a blocking CI gate. Owner decision. This satisfies Principle VII literally — the rule
is machine-checked and runs in CI — and honours its "simplicity is the default" clause. A Gradle
multi-project build would move enforcement from test time to compile time; that was considered
and declined as build ceremony disproportionate to a prototype with no stated scale target.
Accepted risk: a boundary violation is caught by a test run rather than by the compiler,
mitigated by making the ArchUnit gate non-bypassable.

## Unresolved — decisions this plan deliberately does not make

Per the project instruction not to assume anything the spec does not settle, these are recorded
rather than defaulted. None blocks `/speckit-tasks`; each must be answered before the code that
depends on it is written.

| ID | Open decision | Why it is not assumed | Needed by |
|----|---------------|-----------------------|-----------|
| **U-1** | OpenAPI tooling: generate server interfaces from the contract, or hand-write controllers and assert conformance in tests? | Principle I requires the contract to be authoritative but does not say how. Generation guarantees no drift; hand-written plus a conformance test is lighter and keeps controller code readable. Both satisfy the principle. | First controller task |
| **U-2** | CI platform and the concrete tool for each of the 11 blocking gates (secret scan, dependency audit, OpenAPI breaking-change diff). | The constitution names the gates, not the tools. No CI provider is configured in this repository yet. | CI setup task |
| **U-3** | Whether the routing policy is reloadable at runtime or fixed at startup. | FR-026 requires it to be changeable without altering surrounding behavior; it does not say whether a restart is acceptable. There is no stated availability target (G-11) to decide it against. | Routing policy task |
| **U-4** | Masking scheme applied to the recipient reference in audit records and logs. | FR-052 requires masked or indirect recording and no more. Narrowed by ADR-010 — no contact values exist to protect, only the reference. | Persistence task |
| **U-5** | Whether the simulated providers are driven by static configuration or by a per-request directive in the submission. | Spec G-22 authorises simulated providers but says nothing about how failures are triggered. A per-request directive is convenient for demos but puts a test affordance in the production contract. | Channel adapter task |

## Phase Outputs

| Phase | Artifact | Status |
|-------|----------|--------|
| 0 | `research.md` — ADRs, resolved unknowns, open items | Complete |
| 1 | `data-model.md`, `contracts/openapi.yaml`, `quickstart.md` | Complete |
| 2 | `tasks.md` | Run `/speckit-tasks` |

## Post-Design Constitution Re-Check

Re-evaluated after `data-model.md` and `contracts/openapi.yaml`:

- **Principle I** — Enums closed in the contract; `recipients` has `minItems: 1`; submission
  returns 202 with `Location` pointing at the server-issued identity. **Still PASS.**
- **Principle II** — `outbox` lives in the same database as the entities it references, so
  acceptance is one local transaction with no distributed commit. **Still PASS.**
- **Principle IV** — Transition tables and the rollup rule are written out and directly
  translatable into unit truth tables. **Still PASS.**
- **Principle V** — `audit_event.payload` is a per-event-type typed allowlist rather than free
  JSON, so the content payload cannot be carried even by accident. **Strengthened.**
- **Principle VII** — Design added no dependency beyond the Technical Context list and no second
  datastore. **Still PASS.**
- **New risk surfaced during design, mitigated**: a provider's error text could echo submitted
  content back to us (spec edge case). `ChannelProviderPort` returns a classification plus a
  bounded, non-content diagnostic; adapters must not propagate raw provider bodies into audit.
  Covered by the privacy test.

**No gate regressed. No complexity justification required.**
