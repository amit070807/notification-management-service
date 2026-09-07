# Phase 0 Research: Notification Management Core

**Date**: 2026-09-07 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Each decision is recorded in the ADR form Principle VIII requires: context, options, decision,
consequences, and the requirement or principle served. Decisions marked **owner** were made by
the project owner on 2026-09-07; decisions marked **derived** follow from an owner decision or
from a rule already in the spec or constitution. Nothing here is an unattributed assumption —
what remains open is listed at the end, not defaulted.

---

## ADR-001 (owner): Java 21 + Spring Boot

**Context**: The constitution left the stack unbound (`TODO(TECH_STACK)`, register item 12) and
set five disqualifying criteria: transactional multi-entity commit, a durable outbox or
equivalent, automated dependency/architecture testing, a controllable clock in tests, and
OpenAPI contract testing.

**Options**: Java 21 + Spring Boot; Kotlin + Spring Boot; Python 3.12 + FastAPI; Node 20 + NestJS.

**Decision**: Java 21 + Spring Boot.

**Consequences**: All five criteria met without workaround — `@Transactional` for the
single-commit rule, ArchUnit for the machine-checked dependency gate, `java.time.Clock` as an
injectable port, mature contract-testing tooling. Records and sealed interfaces express the
closed failure taxonomy and state machines of Principle IV in the type system rather than by
convention. Cost: more ceremony than Python or Node for equivalent behavior.

**Serves**: Constitution Technology Selection; Principles IV, VI, VII.

---

## ADR-002 (owner): Gradle, single project, boundaries enforced by ArchUnit

**Context**: Principle VII requires seven separated layers and states the domain "MUST NOT import
web frameworks, persistence frameworks, queue clients, or any provider SDK", enforced "by an
automated architecture/dependency test that runs in CI, not by convention". Whether "modules"
meant packages or build modules was ambiguous and was put to the owner.

**Options**: (a) single Gradle project, package boundaries, ArchUnit gate; (b) Gradle
multi-project build where the domain subproject has no Spring or JDBC on its compile classpath.

**Decision**: (a).

**Consequences**: The requirement is met literally — the rule is machine-checked and runs in CI —
and Principle VII's "simplicity is the default" clause is honoured. Option (b) would move
enforcement from test time to compile time, which is genuinely stronger; it was declined as build
ceremony disproportionate to a prototype with no stated scale target (G-11). **Accepted risk**: a
boundary violation is caught by a test run rather than by the compiler. **Mitigation**: the
ArchUnit gate is blocking and non-bypassable, per the constitution's rule that a red gate is
never overridden.

**Serves**: Principle VII, both clauses.

---

## ADR-003 (owner): PostgreSQL 16 + in-process transactional outbox poller

**Context**: Principle II forbids a non-transactional enqueue and requires a handoff that "cannot
lose or phantom-create work when the process dies between commit and enqueue". Work must also be
claimable concurrently without double-attempting a delivery.

**Options**: (a) PostgreSQL + outbox table polled in-process; (b) PostgreSQL + Kafka or RabbitMQ;
(c) H2 in-memory.

**Decision**: (a).

**Consequences**: The outbox row is written in the same local transaction as the entities, so the
commit is atomic with no distributed-transaction problem and no lost-update window. Claiming uses
`SELECT … FOR UPDATE SKIP LOCKED`, which is safe across instances, so horizontal scaling needs no
correctness change. One deployable and one datastore keeps the clean-clone startup path short
(SC-011). Rejected (b): adds a broker to the startup path and still needs the outbox anyway.
Rejected (c): H2's locking semantics differ from PostgreSQL, so the concurrent-worker test would
prove less than it appears to — unacceptable for a non-negotiable gate.

**Serves**: Principle II; Technology Selection; SC-011.

---

## ADR-004 (owner): Persistence via `JdbcClient` with explicit SQL

**Context**: Principle V requires audit records to be non-modifiable and non-removable. Principle
II requires a `FOR UPDATE SKIP LOCKED` worker claim. Neither the spec nor the constitution names
a persistence approach.

**Options**: (a) Spring `JdbcClient`, explicit SQL; (b) JPA/Hibernate; (c) Spring Data JDBC
repositories.

**Decision**: (a).

**Consequences**: No dirty-checking exists, so no accidental flush can mutate an append-only
audit row — the immutability rule is enforced by the absence of a mechanism rather than by
discipline. `FOR UPDATE SKIP LOCKED` is written directly instead of being coaxed out of a mapping
layer. Every query is visible and reviewable, which suits the constitution's review-evidence
requirement. Cost: more mapping code than an ORM, and no free schema derivation — mitigated by
Flyway migrations being explicit anyway.

**Serves**: Principles II and V; constitution Data Model Invariants.

---

## ADR-005 (owner): Two channels — EMAIL and SMS

**Context**: Spec G-18 assumes a closed, configured channel set but the source never names a
channel.

**Options**: EMAIL + SMS; EMAIL + SMS + PUSH; EMAIL + SMS + PUSH + WEBHOOK.

**Decision**: `EMAIL` and `SMS`.

**Consequences**: The minimum that still demonstrates everything the source requires: two
recipients across two channels produce the four independent delivery statuses of spec scenario
US2.2, and a routing exclusion is observable. `Channel` becomes a two-value closed enum in the
contract. Adding a third channel must remain a `channel/` plus config change only (Principle
VII), and that extensibility is proven by a test fixture channel rather than by shipping a third
adapter.

**Serves**: FR-009, FR-012, spec G-18; Principle VII.

---

## ADR-006 (owner): Duplicates accepted; server-issued identity is the retrieval key

**Context**: §4.1 names a client-supplied "Notification identifier" but never requires it to be
unique. §4.2 requires a notification's status to be retrievable, which requires a unique address.
Constitution v2.0.0 item 9 defers idempotency, so uniqueness could not be assumed into place.

**Options**: (a) client identifier is the natural primary key, duplicates rejected as a conflict;
(b) full idempotent replay of the stored state; (c) duplicates accepted with a surrogate key.

**Decision**: (c), with the server-issued identity as the status retrieval key.

**Consequences**: Recorded in the spec as **D4**; FR-008 is revised and FR-008a/FR-008b added.
This is **not** idempotency and must not be described as such — no body comparison, no replay, no
dedup window, no suppression of the second notification's deliveries. What it does provide is
defined behavior, so that "out of scope" does not silently become "whatever the datastore does".
Cost: callers hold two identifiers and must retain the server-issued one. Rejected (a): would
have implemented a deduplication behavior the constitution defers. Rejected (b): idempotency
outright.

**Serves**: Spec D4, G-12, G-31; constitution register item 9.

---

## ADR-007 (derived): Explicit state model, as §4.2 permits

**Context**: The source names no states (G-09), but §4.2 explicitly permits "a different state
model if it is documented and defensible". Principle IV requires explicit enums with legal
transition sets and a deterministic documented rollup. The spec's Assumptions section already
proposes the vocabulary; this ADR adopts it unchanged.

**Decision**: `DeliveryState` = `PENDING, QUEUED, IN_PROGRESS, DELIVERED, RETRY_SCHEDULED,
FAILED, EXHAUSTED, EXPIRED, UNDELIVERABLE`. `NotificationState` = `ACCEPTED, IN_PROGRESS,
COMPLETED, PARTIALLY_FAILED, FAILED, EXPIRED`. Transition tables and the rollup rule are in
`data-model.md`.

**Consequences**: `EXHAUSTED` is distinct from `FAILED`, so a budget-exhausted delivery is
distinguishable from a first-attempt permanent failure (FR-044). `EXPIRED` is distinct from every
failure state, because expiry is not a failure (FR-034). `UNDELIVERABLE` covers empty routing
(FR-024). Illegal transitions throw rather than being silently applied.

**Serves**: FR-016, FR-017, FR-024, FR-034, FR-044; Principle IV.

---

## ADR-008 (derived): Retry parameters from the constitution's declared defaults

**Context**: §4.5 requires bounded retry but supplies no numbers (G-08). The constitution declares
defaults and requires them configurable. The retryable/non-retryable partition is FR-040.

**Decision**: Max 5 attempts; exponential backoff from 1s, factor 2, ceiling 60s, jitter ±20%;
all bound to configuration properties. The retryability table lives in exactly one place in
`domain/retry`: retryable = `TRANSIENT_PROVIDER_FAILURE`, `TIMEOUT`, `UNKNOWN`; non-retryable =
`PERMANENT_PROVIDER_REJECTION`, `INVALID_RECIPIENT`, `AUTH_ERROR`.

**Consequences**: "Bounded" becomes testable. `UNKNOWN` is retryable but never success, failing
closed (FR-041). `AUTH_ERROR` is terminal and additionally raises an operational signal, being a
service configuration fault rather than a recipient fault (FR-042). Jitter comes from a port
injected outside the domain, so routing and retry decisions stay pure (Principle III).

**Serves**: FR-036–FR-045; Principle IV.

---

## ADR-009 (derived): Simulated channel providers behind the production port

**Context**: §5 requires a runnable prototype; the source never states that real providers are
available (G-22). Principle VI requires every failure classification to be exercisable and test
doubles to implement the same port as real adapters.

**Decision**: `SimulatedEmailProvider` and `SimulatedSmsProvider`, both implementing
`ChannelProviderPort`, able to emit any of the six classifications deterministically.

**Consequences**: All five named failure kinds plus `UNKNOWN` are reachable in tests and in a
live demo through the production code path (SC-005). No real credentials exist anywhere in the
repository (Principle V). This is a declared limitation under DO-004 — the prototype proves no
integration with any real provider. *How* a failure is triggered is left open as U-5 below.

**Serves**: FR-037, FR-041, SC-005; Principles V, VI, VII.

---

## ADR-010 (owner): A recipient is an opaque reference

**Context**: §4.1 requires "one or more recipients" but the source states no field of a recipient
— no address, no contact value, no name. An earlier draft assumed submitter-supplied per-channel
addresses and built an address table, a masking scheme, and a `NO_ADDRESS_FOR_CHANNEL` routing
outcome on top of it.

**Options**: (a) opaque reference only; (b) reference plus a declared but unimplemented
destination-resolver port; (c) keep submitter-supplied addresses.

**Decision**: (a).

**Consequences**: Removed as untraceable to the source — `RecipientAddressInput`, the
`recipient_address` table, `NO_ADDRESS_FOR_CHANNEL`, and the contact-value masking machinery.
`INVALID_RECIPIENT` is unchanged and becomes what §4.5 always described: a provider verdict at
delivery time. FR-052 narrows to protecting the reference itself. Rejected (b): declares an
abstraction for something explicitly out of scope, against Principle VII's YAGNI clause.
Rejected (c): it assumed a recipient directory into existence for addresses while D2 assumes one
out of existence for preferences — the same class of data treated two ways.

**What it exposes**: G-32 — delivery needs a destination and the source supplies none, defining
no directory or resolution mechanism. The previous assumption concealed that by inventing the
missing input. Now recorded as an unresolved finding of the same shape as G-05, reportable under
DO-004.

**Serves**: FR-005, FR-012, FR-052; spec D5; Principles V and VII.

---

## Resolved Unknowns

| Unknown | Resolution | Authority |
|---------|-----------|-----------|
| Language and version | Java 21 | ADR-001, owner |
| Build tool and layout | Gradle, single project + ArchUnit | ADR-002, owner |
| Storage and async substrate | PostgreSQL 16 + in-process outbox poller | ADR-003, owner |
| Persistence approach | `JdbcClient`, explicit SQL | ADR-004, owner |
| Channel set (G-18) | EMAIL, SMS | ADR-005, owner |
| Duplicate identifiers (G-12, G-31) | Accepted; server-issued retrieval key | ADR-006, owner |
| State vocabulary (G-09) | Adopted from the spec's Assumptions unchanged | ADR-007 |
| Retry numbers (G-08) | Constitutional defaults, configurable | ADR-008 |
| Provider availability (G-22) | Simulated adapters behind the real port | ADR-009 |
| Recipient model (G-17) | Opaque reference; no address data | ADR-010, owner |
| Performance targets (G-11) | **Deliberately unresolved**; none asserted | spec |

## Open — carried into implementation, not defaulted

These are recorded rather than decided, per the project instruction not to assume what the spec
does not settle. Each must be answered before the task that depends on it is implemented.

| ID | Open decision | Why it is not being assumed |
|----|---------------|------------------------------|
| **U-1** | OpenAPI tooling: generate server interfaces from the contract, or hand-write controllers and assert conformance in a test? | Principle I requires the contract to be authoritative but not how. Generation guarantees no drift; hand-written plus a conformance test is lighter and more readable. Both comply. |
| **U-2** | CI platform, and the concrete tool behind each of the 11 blocking gates. | The constitution names the gates, not the tools, and no CI provider is configured in this repository. |
| **U-3** | Routing policy reloadable at runtime, or fixed at startup? | FR-026 requires it changeable without altering surrounding behavior; it does not say whether a restart is acceptable, and there is no availability target (G-11) to decide against. |
| **U-4** | Masking scheme applied to the recipient reference in audit records and logs. | FR-052 requires masked or indirect recording and nothing more. Narrowed by ADR-010: no contact values remain, only the reference itself. |
| **U-5** | Simulated provider failures driven by static configuration or by a per-request directive? | G-22 authorises simulated providers but not the trigger mechanism. A per-request directive is convenient for demos but places a test affordance in the production contract. |

## Deliberately Not Researched

- Preference storage, preference APIs, opt-out semantics — out of scope by D2/G-26. Researching
  them risks introducing the proxy FR-019a forbids.
- Idempotency, deduplication, and replay strategies — deferred by constitution register item 9.
  ADR-006 defines behavior on duplicates without supplying any deduplication semantics.
- Message templating and rendering — out of scope by D1; content is opaque.
