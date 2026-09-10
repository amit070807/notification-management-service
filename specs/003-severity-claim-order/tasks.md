# Tasks: Severity-Ordered Delivery Claim

**Input**: Design documents from `specs/003-severity-claim-order/`

**Prerequisites**: [spec.md](./spec.md) · [plan.md](./plan.md) · [research.md](./research.md) ·
[data-model.md](./data-model.md) · [quickstart.md](./quickstart.md)

**Tests**: mandatory. Constitution Principle VI is NON-NEGOTIABLE — a failing test exists before the
code that satisfies it, and a history showing otherwise is rejected.

**No `contracts/`**: FR-209 puts claim order outside every external interface, so there is no contract
delta. Stated so its absence reads as a decision.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelisable — different file, no dependency on an incomplete task
- **[Story]**: `[US1]` — this feature has one user story

---

## Phase 1: Foundational — the rank

**Purpose**: the rank is what every other task depends on. The query cannot be written and no assertion
can be phrased until it is a settled, tested fact.

### Tests ⚠️ WRITE FIRST, MUST FAIL

- [X] T001 [P] Pairwise truth-table test in `src/test/java/com/notification/unit/SeverityRankTest.java`: **all six ordered pairs** of the four severities, asserting `CRITICAL > HIGH > MEDIUM > LOW`. **Not just `CRITICAL` against `LOW`** — G-60 means an implementation sorting the stored string passes an extremes-only test while inverting `MEDIUM` and `LOW` (SC-202, FR-202)
- [X] T002 [P] In the same file, assert the rank is **total** (every constant has one) and **distinct** (no two share one). A duplicated rank makes two severities tie and fall through to age, which every extremes-only test still passes (data-model §1)
- [X] T003 [P] In the same file, assert the rank does **not** equal `ordinal()` as a source — express it as a test that the ordering survives a change in declaration order, or assert the explicit values. FR-202 forbids `ordinal()` by name, and the declaration order agrees today, which is exactly what makes it a trap
- [X] T004 [P] Test in `src/test/java/com/notification/unit/SeverityOrderingSqlTest.java` asserting the generated SQL fragment **names every enum constant** and its rank, so a partially-generated `CASE` fails here rather than silently ranking a severity `0` (ADR-027)
- [X] T005 [P] In the same file, assert the fragment ends in `ELSE 0`. A missing `ELSE` yields `NULL` for an unmatched value, whose position under `DESC` depends on `NULLS FIRST/LAST` defaults (data-model §4)

### Implementation

- [X] T006 [US1] Add an explicit `rank` field to each constant in `src/main/java/com/notification/domain/model/Severity.java` — `CRITICAL=4, HIGH=3, MEDIUM=2, LOW=1` — supplied through the constructor so it **cannot be omitted** for a future constant (ADR-025, depends on T001–T003)
- [X] T007 [US1] Add `src/main/java/com/notification/domain/model/SeverityOrdering.java` generating the SQL `CASE` from `Severity.values()`. **Domain package, no framework import** — `archTest` must stay green. Record in a comment why interpolating these strings is safe: enum constant names from a closed compile-time set, never caller input (ADR-027, depends on T004, T005)

**Checkpoint**: the rank is declared once, tested pairwise, and its SQL form cannot drift from it.

---

## Phase 2: Foundational — configuration

**Purpose**: the flag and the batch size are needed by every Phase 3 test. Neither changes behaviour at
its default.

### Tests ⚠️ WRITE FIRST, MUST FAIL

- [X] T008 [P] Extend `src/test/java/com/notification/regression/FlagsOffBaselineTest.java` to assert `severity-claim-order` is **`false`** in the committed `application.yaml`. Read the file, not a resolved bean — the point is what ships (FR-206, spec 002 FR-105 precedent)
- [X] T009 [P] Test asserting `notification.worker.batch-size` defaults to **50**, unchanged from the constant it replaces, in `src/test/java/com/notification/regression/WorkerPropertiesTest.java` (ADR-030)

### Implementation

- [X] T010 [US1] Add `severityClaimOrder` to `src/main/java/com/notification/config/FeatureFlags.java`, boxed `Boolean` defaulting false in the compact constructor, matching the three existing flags (FR-206, depends on T008)
- [X] T011 [US1] Add `src/main/java/com/notification/config/WorkerProperties.java` as `@ConfigurationProperties(prefix = "notification.worker")` carrying `batchSize` (default 50). Leave the existing lease constant alone — spec 002 T048 tied it to the provider read timeout, and moving it is not this feature's business (ADR-030, depends on T009)
- [X] T012 [US1] Register the properties class and set both values in `src/main/resources/application.yaml`: flag `false`, `batch-size: 50`, each with a comment saying what turning it changes
- [X] T013 [US1] Read the batch size from `WorkerProperties` in `src/main/java/com/notification/worker/DeliveryWorker.java`, replacing the `BATCH` constant (depends on T011)

**Checkpoint**: the flag exists and is off; the batch size is configurable at its old default.

---

## Phase 3: User Story 1 — severity-ordered claim (P1)

**Goal**: an operator's `CRITICAL` notifications are attempted before `LOW` ones when a backlog exists.

**Independent test**: enqueue `LOW`, then `CRITICAL`, batch size one, run the worker — the `CRITICAL`
delivery is attempted first despite being younger.

### Tests ⚠️ WRITE FIRST, MUST FAIL

- [X] T014 [P] [US1] Integration test in `src/test/java/com/notification/integration/SeverityClaimOrderTest.java`: a **younger `CRITICAL`** is claimed before an **older `LOW`**, batch size one, flag on (SC-201, FR-201)
- [X] T015 [P] [US1] In the same file: two `HIGH` deliveries at different clock times are claimed **oldest-first**. Severity refines the order, it does not replace it (SC-203, FR-203)
- [X] T016 [P] [US1] In the same file: all four severities claimed one at a time yield `CRITICAL, HIGH, MEDIUM, LOW`. **Assert `MEDIUM` before `LOW` explicitly** — that is the pair lexicographic ordering inverts (G-60)
- [X] T017 [P] [US1] Integration test in `src/test/java/com/notification/integration/ClaimOrderPreservedTest.java`: with the **default** batch size, four deliveries claimed in **one batch** are *attempted* in rank order. Assert what the **worker observed**, not what the query selected — `RETURNING` does not guarantee the CTE's order (SC-205, FR-205, B-05)
- [X] T018 [P] [US1] Integration test in `src/test/java/com/notification/integration/SeverityReclaimOrderTest.java`: a fresh `CRITICAL` delivery is claimed **ahead of** a stranded `LOW` one whose lease expired. Strand it with `ScriptedChannelProvider.thenThrow()`; remember a stranded delivery has **already had one provider call**, so do not assert zero calls (SC-206, FR-207b, D-16)
- [X] T019 [P] [US1] Integration test in `src/test/java/com/notification/integration/SeverityStarvationTest.java`: under a continuous stream of due `CRITICAL` work, a due `LOW` delivery stays **unclaimed** — asserted as **intended** behaviour — and turning the flag off claims it. This is what makes a future age-override reverse D-15 loudly instead of looking like a bug fix (SC-207, FR-207, FR-207a)
- [X] T020 [P] [US1] Integration test in `src/test/java/com/notification/integration/ClaimEligibilityUnchangedTest.java`: claim the same backlog flag-on and flag-off and assert the **sets** of claimed ids are identical, differing only in order. A join that silently narrowed the result would break FR-204 while every ordering test above still passed (FR-204, quickstart scenario 7)
- [X] T021 [P] Regression test in `src/test/java/com/notification/regression/ClaimOrderUnchangedTest.java`: with the flag **off**, deliveries of mixed severity are claimed **purely oldest-first**, ignoring severity — B-01 exactly (SC-204, FR-206)

### Implementation

- [X] T022 [US1] Rewrite `claimDue` in `src/main/java/com/notification/persistence/JdbcDeliveryRepository.java` to the three-CTE shape in data-model §5: `candidate` (ordered, with `ROW_NUMBER()`) → `claimed` (`UPDATE … RETURNING`) → final `SELECT … ORDER BY candidate.ord`. Depends on T007, T010, T014–T021. Three obligations, each with a named failure mode:
  - **`FOR UPDATE OF d`**, never bare `FOR UPDATE` — a bare lock would also lock the joined `notification` row and contend with the acceptance transaction. The one new locking hazard the join introduces
  - The eligibility `WHERE` clause copied **byte-identical** from the current query, reclaim branch included — FR-204, and D-16 means the reclaim branch shares the new `ORDER BY` rather than being exempted
  - The final `ORDER BY candidate.ord` present — without it, selection order is right and processing order is arbitrary, which passes today and breaks on a plan change
- [X] T023 [US1] Build the flagged sort term as `CASE WHEN ? THEN <generated CASE> ELSE 0 END` and interpolate it in **one** place used by both the window function and the CTE's `ORDER BY`, so the two cannot disagree. **One SQL string, not two** — spec 002 T047 made the same choice for the reclaim flag, because the disabled path is the rollback and is the path nobody looks at (ADR-029, depends on T022)
- [X] T024 [US1] Verify the join cannot narrow the result: confirm `delivery.notification_id` is `NOT NULL` with its foreign key, and record that as the reason an inner join is safe. Asserted by T020, not merely reasoned about (FR-204)

**Checkpoint**: `CRITICAL` is claimed and processed before `LOW`; equal severity keeps oldest-first;
flag off is the baseline; eligibility is untouched.

---

## Phase 4: Polish and deliverables

- [X] T025 Run `./gradlew test` and confirm **no existing test needed editing**. Unlike spec 002, this feature reverses no previous behaviour, so any required edit is a **finding to report**, not a licence to edit (SC-204, quickstart scenario 0)
- [X] T026 Run `./gradlew clean check archTest privacyTest` and confirm every gate passes, including the coverage floors, which must not fall. The new domain code is a rank and a string generator, so the **domain** floor is what thin tests break first
- [X] T027 [P] Measure the join's cost via `./gradlew perfTest` and record it in `docs/performance-phase2.md`. **Assert no threshold** — no source document states one (G-61, phase-1 G-11), and a verdict would invent a requirement. Note B-06 alongside: the sort was already unindexed
- [X] T028 [P] Document the operator consequence of D-15 in `docs/migration-phase2.md`: an aged `LOW` delivery sitting unclaimed is **correct**, not a stalled worker; G-64 records that nothing distinguishes the two; turning the flag off is the documented and **only** mitigation. FR-207 requires this stated, not implied
- [X] T029 [P] Document the operator consequence of D-16 in the same file: **recovery is not expedited** — a reclaimed low-severity delivery waits behind fresh high-severity work
- [X] T030 [P] Update `docs/architecture.md` with the claim-ordering rule, the rank's single declaration, and the reason the order is carried in SQL rather than re-sorted in Java. Rewrite the affected section whole rather than appending — a reader has no memory of the previous version
- [X] T031 [P] Add G-60, G-61, G-63 and G-64 to the limitations list in `docs/testing.md`, and add the two new trade-offs: the join over denormalisation, and the three-CTE query over a bare `ORDER BY`
- [X] T032 [P] Update `README.md` with the flag, the batch-size property, and one line on what the flag changes
- [X] T033 Run the full [quickstart.md](./quickstart.md) validation — all nine scenarios, scenario 0 first — and record the result with per-scenario evidence
- [X] T034 Confirm ADR-025 … ADR-030 are present and current in [research.md](./research.md), and add an ADR for any decision taken during implementation that none of them covers (Principle VIII)
- [X] T035 Review against the Review Evidence checklist in `.specify/memory/constitution.md` and append the result to `docs/constitution-compliance.md` alongside the phase-1 and phase-2 reviews

---

## Dependencies & Execution Order

```text
Phase 1 (rank)          T001–T005 parallel  →  T006, T007
                                                    │
Phase 2 (config)        T008, T009 parallel  →  T010–T013
                                                    │
Phase 3 (claim)         T014–T021 parallel  ────────┴→  T022 → T023 → T024
                                                                        │
Phase 4 (polish)                                        T025, T026 → T027–T032 parallel → T033–T035
```

**The one ordering constraint that matters**: the rank (Phase 1) precedes the query (Phase 3). Writing
the query first means hand-writing a `CASE` and then retrofitting the generator, which is how the second
declaration FR-202 forbids gets introduced and left behind.

## MVP scope

**Phases 1–3.** Delivers the ordering rule, flag-gated and inert when off, with the pairwise guard
against G-60 and the processing-order guarantee FR-205 requires. Phase 4 is the deliverable
documentation and the measurement that FR-107 asks for without a verdict.

## Total

**35 tasks** — 5 unit tests, 8 integration/regression tests, 11 implementation, 11 polish and
documentation. One user story.
