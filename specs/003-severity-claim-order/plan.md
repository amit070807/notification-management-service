# Implementation Plan: Severity-Ordered Delivery Claim

**Branch**: `feat-3-ambiguous-requirements` | **Date**: 2026-09-10 |
**Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-severity-claim-order/spec.md`

**Baseline**: commit `68727a2` — 308 tests, all gates green.

## Summary

Make the delivery worker claim higher-severity deliveries first, and process them in that order.

One ordering rule in one place. The rank is an explicit field on the `Severity` enum; the SQL sort
expression is generated from that enum so it cannot drift; the claim joins `notification` rather than
denormalising a column; a `ROW_NUMBER()` carries the order through `RETURNING` so processing order
matches claim order; and the whole thing is a single flag-gated term inside one `ORDER BY`, so the
disabled path is the same query and cannot diverge from the baseline.

Nine files touched, one new migration-free. No new state, endpoint, audit type, or schema column.

## Technical Context

**Language/Version**: Java 21 (unchanged, ADR-001)

**Primary Dependencies**: Spring Boot 3.3.4, `JdbcClient`. **No new dependency.**

**Storage**: PostgreSQL 16, `JdbcClient` with explicit SQL. **No migration** — ADR-026 rejects the
denormalised column that would have needed one.

**Testing**: JUnit 5, Testcontainers, ArchUnit. Docker Engine 25+ (API 1.44).

**Target Platform**: Linux server, single deployable (unchanged)

**Project Type**: Web service with an in-process worker (unchanged)

**Performance Goals**: **None stated.** Phase-1 G-11 and G-61 both record the absence. The join added by
ADR-026 is measured and reported per spec 002 FR-107; **no threshold is asserted**, because inventing
one would invent a requirement.

**Constraints**: the claim query is on the path of every delivery, so it carries a rollback switch
(FR-206). Flag off must be byte-for-byte B-01.

**Scale/Scope**: one ordering rule. Batch size 50 by default, now configurable (ADR-030).

## Constitution Check

*GATE: evaluated against constitution v2.1.0 before Phase 0, re-evaluated after Phase 1.*

| Principle | Applies? | Evaluation |
|---|---|---|
| **I. Contract-First** | No | FR-209: claim order is not observable in any response. `openapi.yaml` is untouched, and the contract test proves that by continuing to pass unmodified |
| **II. Durable Accept-Then-Process** (NON-NEG) | Yes | **PASS.** The acceptance transaction is not touched — ADR-026 rejected the denormalised column precisely because populating it would have altered that transaction. The claim's `FOR UPDATE SKIP LOCKED` semantics are unchanged; only the `ORDER BY` moves |
| **III. Idempotent, Deterministic Domain Core** | Yes | **PASS.** The rank is a pure total function of the enum. Ordering is deterministic given the same rows: severity rank then `state_changed_at`. FR-204 forbids any change to eligibility, so idempotency and reclaim behaviour are untouched |
| **IV. State Machine + Failure Taxonomy** (NON-NEG) | No | **PASS by non-participation.** No state, no transition, no failure classification changes. Ordering decides *when* a delivery is picked up, never *what may happen to it* |
| **V. Auditability Without Sensitive Data** (NON-NEG) | Yes | **PASS.** No new audit type (spec header), and severity is already recorded on the notification. Severity is not a sensitive value and reaches no new surface — in particular it is **not** added to a metric label (out of scope) |
| **VI. Test-First, Deterministic** (NON-NEG) | Yes | **PASS with an explicit obligation.** Every task below leads with a failing test. Determinism needs care here: ordering assertions must not depend on insertion order or on wall-clock ties, so tests set `state_changed_at` through `MutableClock` and assert on identity, not position alone |
| **VII. Modular Boundaries** | Yes | **PASS.** The rank lives in `domain/model`, the SQL fragment generator beside it in the domain, and only `persistence/` and `worker/` change. `archTest` must stay green with no new rule relaxed |
| **VIII. Documented, Engineer-Owned** | Yes | **PASS.** ADR-025 … ADR-030 in [research.md](./research.md), one per decision that had a defensible alternative |

**Declared Operating Defaults**: coverage floors 90% domain / 80% overall must not fall (FR-108
precedent). The new domain code is a rank and a string generator — both trivially coverable, so the
domain floor is the one at risk if their tests are thin.

**Gate result: PASS.** No violation, no justified exception, nothing to record in the register.

### Re-evaluation after Phase 1 design

No change. The design adds no runtime dependency, no schema object, no framework import to the domain,
and no new audit or contract surface. The one thing Phase 1 sharpened is Principle VI: see the
determinism note in [quickstart.md](./quickstart.md) about lexicographic near-misses (G-60), which is
why the truth table is pairwise rather than "CRITICAL first".

## Project Structure

### Documentation (this feature)

```text
specs/003-severity-claim-order/
├── spec.md              # Requirements, decisions D-15/D-16, gap register
├── plan.md              # This file
├── research.md          # ADR-025 … ADR-030
├── data-model.md        # Rank table, ordering contract, query shape
├── quickstart.md        # Validation scenarios
└── tasks.md             # /speckit-tasks output
```

No `contracts/` directory. FR-209 puts claim order outside every external interface, so there is no
contract delta to write — recorded here so its absence reads as a decision rather than an omission.

### Source code

```text
src/main/java/com/notification/
├── domain/model/
│   ├── Severity.java                    MODIFIED  explicit rank per constant (ADR-025)
│   └── SeverityOrdering.java            NEW       generates the SQL rank fragment (ADR-027)
├── config/
│   ├── FeatureFlags.java                MODIFIED  + severityClaimOrder, default false
│   └── WorkerProperties.java            NEW       batch size + lease as configuration (ADR-030)
├── persistence/
│   └── JdbcDeliveryRepository.java      MODIFIED  ordered CTE, ROW_NUMBER, flagged sort term
└── worker/
    └── DeliveryWorker.java              MODIFIED  reads batch size from configuration

src/main/resources/
└── application.yaml                     MODIFIED  flag (false) + worker.batch-size (50)

src/test/java/com/notification/
├── unit/
│   ├── SeverityRankTest.java            NEW  pairwise truth table, all four values (SC-202)
│   └── SeverityOrderingSqlTest.java     NEW  generated fragment covers every constant
├── integration/
│   ├── SeverityClaimOrderTest.java      NEW  SC-201, SC-203
│   ├── ClaimOrderPreservedTest.java     NEW  SC-205 — processing order, not selection order
│   ├── SeverityReclaimOrderTest.java    NEW  SC-206 — reclaims are ordered, not privileged
│   └── SeverityStarvationTest.java      NEW  SC-207 — starvation as INTENDED, flag off releases
└── regression/
    └── ClaimOrderUnchangedTest.java     NEW  SC-204 — flag off equals B-01
```

## Approach

### The query

```text
WITH candidate AS (
    SELECT d.id,
           ROW_NUMBER() OVER (ORDER BY <flagged rank term> DESC, d.state_changed_at) AS ord
    FROM delivery d
    JOIN notification n ON n.id = d.notification_id        -- ADR-026
    WHERE  <eligibility predicate, UNCHANGED from B-01>
    ORDER BY <flagged rank term> DESC, d.state_changed_at
    LIMIT ?
    FOR UPDATE OF d SKIP LOCKED
),
claimed AS (
    UPDATE delivery d SET claimed_until = ?
    FROM candidate WHERE d.id = candidate.id
    RETURNING d.*, ...recipient_ref
)
SELECT claimed.* FROM claimed
JOIN candidate ON candidate.id = claimed.id
ORDER BY candidate.ord                                      -- ADR-028, FR-205
```

`<flagged rank term>` is `CASE WHEN ? THEN <generated rank CASE> ELSE 0 END` (ADR-029). The eligibility
`WHERE` clause is copied unchanged, which is what FR-204 requires — and the reclaim branch inside it is
covered by the same `ORDER BY` rather than exempted, which is D-16.

Two details that are easy to get wrong and are therefore called out as obligations:

- **`FOR UPDATE OF d`**, not bare `FOR UPDATE`. With the join added, a bare `FOR UPDATE` would also lock
  the `notification` row, which the claim has no business locking and which would create contention
  against the acceptance transaction. This is the one new locking hazard the join introduces.
- The join must not narrow the result set. `delivery.notification_id` is `NOT NULL` with a foreign key,
  so an inner join cannot drop a row — asserted rather than assumed, because a join that silently
  filtered would violate FR-204 while every ordering test still passed.

### Order of work

1. Rank and its SQL generator, with unit tests. Nothing else can be written correctly first, because
   both the query and every assertion depend on the rank being a settled, tested fact.
2. Configuration: flag and batch size. Needed by the tests in step 3.
3. The query, driven by the integration tests.
4. Processing-order preservation, which is a separate test from selection order (B-05).
5. Regression: flag off equals baseline.
6. Measurement and documentation.

### Risks

| Risk | Handling |
|---|---|
| **G-60, the lexicographic near-miss.** `ORDER BY severity ASC` gets `CRITICAL, HIGH` right and inverts `MEDIUM, LOW`, so a wrong implementation passes any "CRITICAL first" test | SC-202's pairwise truth table over all four values. This is the single most likely way to ship this feature broken |
| **`RETURNING` order.** Trusting it passes today and breaks on a plan change | ADR-028's `ROW_NUMBER()`, and a test that asserts what the **worker** observed |
| The join changes eligibility | FR-204. A test asserts the claimed set is identical with the flag on and off, not merely reordered |
| The added `ORDER BY` term drifts from the enum | ADR-027 generates it; a test asserts every constant appears |
| Starvation looks like a stalled worker (G-64) | Not mitigated — D-15 accepted it. Documented in the operator notes, and SC-207 asserts it as intended behaviour so nobody later "fixes" it |

## Complexity Tracking

No constitutional violation requires justification.

One deliberate cost is worth naming: the query grows from a two-CTE shape to a three-CTE shape with a
window function. That is more complex than a bare `ORDER BY`, and Principle VII's simplicity default
says so. It is accepted because B-05 makes the simple version wrong in a way that would not fail a
test — and an ordering feature whose order is not guaranteed to reach the worker has not been built.
