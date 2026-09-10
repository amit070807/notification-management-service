---

description: "Task list for Push Channel, Deduplication and Provider Refactoring"
---

# Tasks: Push Channel, Deduplication and Provider Refactoring

**Input**: Design documents from `/specs/002-push-dedup-refactor/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/contract-delta.md](./contracts/contract-delta.md), [quickstart.md](./quickstart.md)

**Governing document**: `.specify/memory/constitution.md` **v2.1.0**

**Baseline**: phase 1 at `0c4ca3e` — 196 tests, all gates green. The phase-1 suite is the
**regression oracle** for this phase.

**Tests**: **MANDATORY.** Constitution Principle VI is NON-NEGOTIABLE — a failing test must exist
before the implementation that satisfies it. Two adversarial tests are newly mandatory in v2.1.0:
duplicate submission, and reclaim of a delivery stranded mid-attempt.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on incomplete work)
- **[Story]**: US1–US4, mapping to spec.md
- Every task carries an exact file path

## Path Conventions

Existing tree. Main `src/main/java/com/notification/`, tests `src/test/java/com/notification/`,
migrations `src/main/resources/db/migration/`. The authoritative contract stays at
`specs/001-notification-management-core/contracts/openapi.yaml` (ADR-020).

## Blocking decision

| ID | Decision | Blocks |
|----|----------|--------|
| **U-9** | Whether US2 survives as a separate story or folds into US1's tail (G-57) | Answer at the **start of Phase 5**, once US1 has shown how much provider divergence push actually introduces. Do not pre-empt it |

## The regression rule

Every phase ends by running the **unmodified phase-1 suite** against the flagged-off build
(FR-101, FR-102, FR-105, SC-101, SC-102). Exactly one phase-1 test is expected to change in this
whole phase — `DuplicateSubmissionTest` in Phase 6 — and it must be **replaced, not deleted**.

---

## Phase 1: Setup — feature flags and the regression harness

**Purpose**: make "off means exactly as before" mechanically checkable before any behaviour changes.

- [X] T001 Create `FeatureFlags` configuration properties in `src/main/java/com/notification/config/FeatureFlags.java` with `dedupSubmission`, `dedupDelivery` and `deliveryReclaim` booleans, **all defaulting to false** (ADR-016, FR-104)
- [X] T002 [P] Bind the flag defaults in `src/main/resources/application.yaml` under `notification.features`, with a comment recording that off must equal the phase-1 baseline (FR-105)
- [X] T003 [P] Add a `notification.dedup.window` property defaulting to `PT24H` in `src/main/resources/application.yaml` (D14, FR-141b)
- [X] T004 Write the regression harness test in `src/test/java/com/notification/regression/FlagsOffBaselineTest.java` asserting every flag in `FeatureFlags` is false by default — the guard that stops a flag being enabled by accident (FR-105, SC-102)
- [X] T005 [P] Extend the per-channel retry properties in `src/main/java/com/notification/config/RetryProperties.java` to hold an optional per-channel override map plus the existing defaults (FR-132, ADR-018)

**Checkpoint**: flags exist, default off, and are asserted. No behaviour has changed.

---

## Phase 2: Foundational — the closed sets that everything else keys off

**Purpose**: the enum and audit vocabulary changes every later story depends on.

**⚠️ CRITICAL**: `EnumParityTest` fails if code and contract disagree, so the enum and the contract
must change in the **same task**. That is the gate working, not an obstacle.

- [X] T006 Write the failing enum-parity expectation by adding `PUSH` to the `Channel` enum in `src/main/java/com/notification/domain/model/Channel.java` **and** to the `Channel` schema in `specs/001-notification-management-core/contracts/openapi.yaml` in one change (FR-110, FR-111, contract-delta Change 1)
- [X] T007 Run `./gradlew test --tests 'com.notification.contract.EnumParityTest'` against `src/test/java/com/notification/contract/EnumParityTest.java` and confirm it passes — proof the contract and code moved together rather than one drifting
- [X] T008 [P] Add `NOTIFICATION_SUPPRESSED`, `RETRY_EXECUTED` and `DELIVERY_RECLAIMED` to `src/main/java/com/notification/domain/model/AuditEventType.java`, bringing the total to 13 (data-model §5)
- [X] T009 Write the failing allowlist test in `src/test/java/com/notification/privacy/AuditPayloadAllowlistTest.java` asserting the sealed hierarchy now permits **13** subclasses, not 10
- [X] T010 Add the three sealed payload records to `src/main/java/com/notification/audit/payload/AuditPayload.java`: `NotificationSuppressed`, `RetryExecuted` (carrying the scheduling reference per FR-150a), `DeliveryReclaimed` (depends on T009)
- [X] T011 [P] Add the `IdempotencyKey` value type in `src/main/java/com/notification/domain/model/IdempotencyKey.java`, derived from `(notificationId, recipientId, channel, attemptNumber)` with **no stored state** (ADR-019, FR-163)
- [X] T012 Write the failing key-derivation truth-table test in `src/test/java/com/notification/unit/IdempotencyKeyTest.java`: identical inputs give identical keys; any component differing gives a different key; the same attempt number after a simulated crash reproduces the key
- [X] T013 Implement the derivation in `src/main/java/com/notification/domain/model/IdempotencyKey.java` to satisfy T012 (depends on T012)

**Checkpoint**: `PUSH` exists as a value, audit vocabulary is 13 types, the key type exists. Nothing routes or delivers over push yet.

---

## Phase 3: User Story 1 — Deliver notifications over a push channel (P1) 🎯 MVP

**Goal**: a source system can request `PUSH` and see per-recipient-per-channel outcomes, with
`EMAIL` and `SMS` behaviour untouched.

**Independent Test**: submit requesting `PUSH`, confirm a delivery is created, attempted and
reported; then submit an email-and-SMS notification and confirm it behaves exactly as in phase 1.

### Tests for User Story 1 ⚠️ WRITE FIRST, MUST FAIL

- [X] T014 [P] [US1] Integration test for push end-to-end — submit requesting `PUSH`, drain, run worker, assert `DELIVERED` and reported in status — in `src/test/java/com/notification/integration/PushDeliveryTest.java` (SC-103)
- [X] T015 [P] [US1] Integration test asserting push disabled in `routing-policy.yaml` yields `202`, **no push delivery**, and `channelOutcomes` showing `CHANNEL_DISABLED` in `src/test/java/com/notification/integration/PushDisabledTest.java` (ADR-017, FR-105, quickstart scenario 2)
- [X] T016 [P] [US1] Regression test asserting an email-and-SMS notification produces the identical state sequence, audit event sequence and status shape as the phase-1 baseline in `src/test/java/com/notification/regression/ExistingChannelsUnchangedTest.java` (SC-104, FR-112)
- [X] T017 [P] [US1] Integration test asserting a push provider auth rejection is classified `AUTH_ERROR`, is terminal, and raises the existing operational signal in `src/test/java/com/notification/integration/PushAuthErrorTest.java` (FR-115, spec US1 scenario 5)
- [X] T018 [P] [US1] Integration test asserting a push rate-limit signal is classified `TRANSIENT_PROVIDER_FAILURE` and is retried in `src/test/java/com/notification/integration/PushRateLimitTest.java` (FR-117, ADR-018)
- [X] T019 [P] [US1] Privacy test asserting push provider credentials appear in no audit record, log line or metric label in `src/test/java/com/notification/privacy/PushCredentialPrivacyTest.java` (FR-116, SC-111)

### Implementation for User Story 1

- [X] T020 [US1] Add `PUSH` to `src/main/resources/routing-policy.yaml` with `enabled: false` — the feature flag **is** the policy switch, so no separate boolean is introduced (ADR-017)
- [X] T021 [P] [US1] Add push provider configuration to `src/main/java/com/notification/config/ChannelProperties.java`: credential reference read from environment or secret manager, never inline, plus connect and read timeouts (FR-115, FR-116, G-37)
- [X] T022 [US1] Implement `SimulatedPushProvider` in `src/main/java/com/notification/channel/SimulatedPushProvider.java` implementing `ChannelProviderPort`, able to emit auth rejection and rate-limit conditions, mapping **every** outcome into the closed taxonomy with no unmapped pass-through (FR-118, FR-131, ADR-015)
- [X] T023 [US1] Register the push provider in `src/main/java/com/notification/config/ChannelConfig.java` so it is wired from configuration like the existing two (FR-111)
- [X] T024 [US1] Extend the scripted test provider in `src/test/java/com/notification/fixtures/ScriptedChannelProvider.java` to cover the push channel, so US1's integration tests drive it through the production port (Principle VI)
- [X] T025 [US1] Run `./gradlew archTest` against `src/test/java/com/notification/architecture/ArchitectureRulesTest.java` and confirm the Principle VII gate still passes. **If it fails, that is a finding to report — not a licence to relax the rule** (SC-105, quickstart scenario 4)
- [X] T026 [US1] Run `./gradlew test` over the unmodified phase-1 suite in `src/test/java/com/notification/` and confirm all 196 tests pass with flags off (FR-101, FR-102)

**Checkpoint**: push delivers end to end, is inert when disabled, and existing channels are provably unchanged.

---

## Phase 4: User Story 2 — Consolidate provider-specific delivery logic (P2)

**Goal**: per-provider error mapping and retry strategy live behind the existing abstraction, with
no per-provider behaviour leaking into routing, retry, state or audit.

**⚠️ ANSWER U-9 FIRST.** Task T027 decides whether this phase is real work or already satisfied by
US1. Do not skip it and do not assume the answer.

**Independent Test**: add a fixture channel with distinct error codes and confirm it requires no
change to routing, retry, state or audit.

### Decide the scope

- [X] T027 [US2] **Resolve U-9** and record it as ADR-022 in `specs/002-push-dedup-refactor/research.md`: having built US1, is per-provider error mapping and retry strategy still outstanding, or was it satisfied as a by-product? Record the answer either way — a phase that turns out to be already done is a legitimate finding (G-57)

### Tests for User Story 2 ⚠️ WRITE FIRST, MUST FAIL

- [X] T028 [P] [US2] Unit test asserting two providers reporting the same condition with **different codes** produce the same classification, with no unmapped outcome for any provider, in `src/test/java/com/notification/unit/ProviderErrorMappingTest.java` (FR-131, SC-107)
- [X] T029 [P] [US2] Integration test asserting a per-provider retry schedule is honoured **and still bounded** — a provider may differ in schedule, never opt out of the bound — in `src/test/java/com/notification/integration/PerProviderRetryTest.java` (FR-132, SC-108)
- [X] T030 [P] [US2] Architecture test adding a fixture channel with distinct error codes and asserting routing, retry, state and audit are untouched, in `src/test/java/com/notification/architecture/ChannelExtensibilityTest.java` (FR-130, FR-135)

### Implementation for User Story 2

- [X] T031 [US2] Extract the shared provider base behaviour — timeout application, outcome-to-classification mapping contract, bounded diagnostic sanitisation — into `src/main/java/com/notification/channel/AbstractChannelProvider.java`, leaving each adapter only its own code table (FR-130)
- [X] T032 [US2] Implement per-provider retry selection in `src/main/java/com/notification/application/DeliveryProcessingService.java`, resolving a `RetryPolicy` per channel with a documented fallback to the default (FR-132)
- [X] T033 [US2] Assert the bound centrally so a per-channel policy cannot exceed it, in `src/main/java/com/notification/config/RetryProperties.java` (FR-132 `[C]`)
- [X] T034 [US2] Confirm no observable change for `EMAIL` or `SMS` by re-running the unmodified phase-1 suite in `src/test/java/com/notification/` (FR-134, FR-101)

**Checkpoint**: provider differences are contained; existing channels behave identically.

---

## Phase 5: User Story 3 — Suppress duplicate notifications (P3)

**Goal**: duplicates are suppressed at submission and re-attempts are recognisable at delivery, both
switchable and both observable.

**Order within this phase is load-bearing**: **reclaim (T042–T046) before the key (T047–T049)** is
the wrong order and must not be used. Reclaim fixes a defect that exists today; the key makes that
fix safe. Shipping reclaim first would trade a silent stall for a silent duplicate (G-58).
The correct order is: **key first, then reclaim.**

**Constitutional note**: this story is authorised by the v2.1.0 amendment. Principle III now
requires recoverability, duplicate-execution safety, a documented boundary, observable suppression,
no suppression after terminal failure, switchability, and documented counterparty responsibility.

### Tests — delivery level ⚠️ WRITE FIRST, MUST FAIL

- [X] T035 [P] [US3] Integration test asserting a delivery orphaned in `IN_PROGRESS` with an expired lease is **reclaimed** and reaches a terminal state, in `src/test/java/com/notification/integration/StrandedDeliveryReclaimTest.java` (FR-159, B-13, quickstart scenario 5)
- [X] T036 [P] [US3] Integration test asserting a reclaimed delivery **consumes** its retry budget, so a repeatedly crashing provider cannot produce unbounded attempts, in `src/test/java/com/notification/integration/ReclaimBudgetTest.java` (FR-159a)
- [X] T037 [P] [US3] Integration test asserting expiry **outranks** reclaim — a stranded delivery whose notification has since expired reaches `EXPIRED`, not a fresh attempt — in `src/test/java/com/notification/integration/ReclaimExpiryPrecedenceTest.java` (FR-159b, FR-034)
- [X] T038 [P] [US3] Integration test asserting a re-attempt carries the **same** idempotency key as the attempt it repeats, in `src/test/java/com/notification/integration/IdempotencyKeyStabilityTest.java` (FR-161, FR-163, quickstart scenario 6)
- [X] T039 [P] [US3] Unit test asserting the delivery state machine accepts `IN_PROGRESS → QUEUED` and still rejects every transition outside the declared table, in `src/test/java/com/notification/unit/DeliveryStateTransitionTest.java` (Principle IV, data-model §2)

### Tests — submission level ⚠️ WRITE FIRST, MUST FAIL

- [X] T040 [P] [US3] Unit truth-table test for the deduplication boundary in `src/test/java/com/notification/unit/DeduplicationBoundaryTest.java`: same `(sourceSystem, correlationId)` inside the window is a duplicate; outside the window is not; **a terminally unsuccessful original is not** (FR-141, FR-141c, D9, D14)
- [X] T041 [P] [US3] Integration test asserting a duplicate submission returns **`200`, not `202`**, with a body naming the original notification, produces no second delivery, and writes a `NOTIFICATION_SUPPRESSED` audit record. **Assert the status code alone first**, since that is what a consumer ignoring unfamiliar fields sees, in `src/test/java/com/notification/integration/DuplicateSuppressionTest.java` (SC-109, FR-144, FR-144a, ADR-021)

### Implementation — delivery level (key first, then reclaim)

- [X] T042 [US3] Add the idempotency key parameter to `ChannelProviderPort.send` in `src/main/java/com/notification/domain/port/ChannelProviderPort.java`, putting the D12 agreement in the type system so no adapter can silently omit it (FR-161, ADR-019)
- [X] T043 [US3] Update every adapter and the scripted test provider to accept and honour the key, in `src/main/java/com/notification/channel/` and `src/test/java/com/notification/fixtures/ScriptedChannelProvider.java` (depends on T042)
- [X] T044 [US3] Supply the derived key on every provider call from `src/main/java/com/notification/application/DeliveryProcessingService.java` (FR-161, depends on T013, T042)
- [X] T045 [US3] Write the Flyway migration `src/main/resources/db/migration/V6__delivery_reclaim.sql` extending the claimable-state index to cover `IN_PROGRESS` rows with an expired lease. Forward-only, no column change (data-model §2)
- [X] T046 [US3] Declare `IN_PROGRESS → QUEUED` in the transition table in `src/main/java/com/notification/domain/state/DeliveryState.java` — a declared transition, not an exception path (Principle IV, depends on T039)
- [X] T047 [US3] Extend `claimDue` in `src/main/java/com/notification/persistence/JdbcDeliveryRepository.java` to reclaim `IN_PROGRESS` rows whose `claimed_until` has passed, gated on the `deliveryReclaim` flag (FR-159, depends on T045, T046)
- [X] T048 [US3] Configure the delivery lease **above** the provider read timeout in `src/main/resources/application.yaml`, with a comment recording why: a merely slow call must not be reclaimed mid-flight (G-58, data-model §2 risk note)
- [X] T049 [US3] Record `DELIVERY_RECLAIMED` audit events from `src/main/java/com/notification/application/DeliveryProcessingService.java` (data-model §5, depends on T010)

### Implementation — submission level

- [X] T050 [US3] Write the Flyway migration `src/main/resources/db/migration/V5__deduplication.sql` creating `notification_suppression` (`id`, `source_system` NOT NULL, `correlation_id` NOT NULL, `suppressed_at` NOT NULL, `original_notification_id` NOT NULL FK, `client_notification_id` NOT NULL) plus an index on `notification (source_system, correlation_id, received_at)`. **An index, not a unique constraint** — phase-1 D4's deliberate absence stands (data-model §4)
- [X] T051 [P] [US3] Implement the boundary as a pure function in `src/main/java/com/notification/domain/dedup/DeduplicationDecision.java` and its key in `src/main/java/com/notification/domain/dedup/DeduplicationKey.java` (FR-141, depends on T040)
- [X] T052 [US3] Implement `JdbcDeduplicationRepository` in `src/main/java/com/notification/persistence/JdbcDeduplicationRepository.java` with the boundary query — same pair, within the window, **and not terminally unsuccessful** (FR-141c, depends on T050)
- [X] T053 [US3] Perform the boundary check **inside** the existing acceptance transaction in `src/main/java/com/notification/application/SubmissionService.java`, so the suppression decision and its record commit together (Principle II, depends on T052)
- [X] T054 [US3] Record `NOTIFICATION_SUPPRESSED` audit events from `src/main/java/com/notification/application/SubmissionService.java` (FR-143, depends on T010)
- [X] T055 [US3] Add the `SubmissionSuppressed` response DTO in `src/main/java/com/notification/api/dto/SubmissionSuppressed.java` with `suppressed`, `originalNotificationId`, `clientNotificationId`, `suppressedAt` (contract-delta Change 2)
- [X] T056 [US3] Return `200 OK` with that body from `src/main/java/com/notification/api/NotificationController.java` when a submission is suppressed, leaving `202` for genuine acceptance (ADR-021, FR-144a)
- [X] T057 [US3] Add the `200` response and `SubmissionSuppressed` schema to `specs/001-notification-management-core/contracts/openapi.yaml`, then run the contract tests (contract-delta Change 2, ADR-020)
- [X] T058 [US3] **Replace, do not delete**, `src/test/java/com/notification/integration/DuplicateSubmissionTest.java` — it asserts phase-1 behaviour this story reverses, and deleting it would erase the record that behaviour changed (spec US3 scenario 8, FR-146)
- [X] T059 [US3] Integration test asserting that with both dedup flags off, duplicates produce two independent notifications with distinct identities exactly as in phase 1, in `src/test/java/com/notification/integration/DedupDisabledTest.java` (FR-147, FR-164, SC-109b, quickstart scenario 9)

**Checkpoint**: stranded deliveries recover safely, duplicates are suppressed and reported, and every bit of it is switchable.

---

## Phase 6: User Story 4 — Audit trail for retry and failure handling (P4)

**Goal**: an operator reconstructing a retried delivery can see the scheduling and the execution as
distinct, correctly paired actions.

**Independent Test**: drive a retryable failure and a successful retry, and confirm both records
exist with an unambiguous pairing.

### Tests for User Story 4 ⚠️ WRITE FIRST, MUST FAIL

- [X] T060 [P] [US4] Integration test asserting a retryable failure produces **distinct** `RETRY_SCHEDULED` and `RETRY_EXECUTED` records, in `src/test/java/com/notification/integration/RetryAuditTest.java` (FR-150, SC-110)
- [X] T061 [P] [US4] Integration test asserting that across several retries each execution is unambiguously paired with the scheduling that caused it, in `src/test/java/com/notification/integration/RetryPairingTest.java` (FR-150a)
- [X] T062 [P] [US4] Integration test asserting all **13** audit event types are reachable in a single end-to-end run, in `src/test/java/com/notification/integration/AuditCompletenessTest.java` (FR-152, B-06)

### Implementation for User Story 4

- [X] T063 [US4] Record `RETRY_EXECUTED` carrying the scheduling reference it followed, from `src/main/java/com/notification/application/DeliveryProcessingService.java` (FR-150, FR-150a, depends on T010)
- [X] T064 [US4] Verify `FR-151` needs no work — the existing routing record already carries the selected channels — and assert it rather than reimplement it, in `src/test/java/com/notification/integration/AuditCompletenessTest.java` (FR-151, D11)
- [X] T065 [US4] Extend the privacy gate to cover the three new record types in `src/test/java/com/notification/privacy/NoSensitiveDataLeakTest.java`, including push credentials (FR-153, SC-111)

**Checkpoint**: retry history is legible; every declared audit type is reachable.

---

## Phase 7: Polish, deliverables and the brownfield obligations

- [X] T066 Run `./gradlew test` over the unmodified phase-1 suite in `src/test/java/com/notification/` against the flagged-off build and confirm 196 tests pass, with `DuplicateSubmissionTest`'s replacement the only justified change (SC-101, SC-102, FR-102)
- [X] T067 Run `./gradlew check` and confirm every gate passes, including the coverage floors declared in `build.gradle.kts`, which must not fall (FR-108)
- [X] T068 [P] Measure and report the performance impact of each enhancement in `docs/performance-phase2.md`. **Assert no threshold** — no source document states one, and inventing a verdict would invent a requirement (FR-107, SC-112, G-40)
- [X] T069 [P] Write the migration and rollback procedures in `docs/migration-phase2.md`, stating for each enhancement what rollback **cannot** recover. For deduplication that is explicit: notifications suppressed while the flag was on were never delivered and are not recoverable (FR-106, FR-103, G-48, SC-114)
- [X] T070 [P] Require in `docs/migration-phase2.md` that a caller audits its event-identifier usage **before** deduplication is enabled for them, since the boundary rests on a contract the service cannot enforce (G-55, G-56)
- [X] T071 Execute each rollback procedure at least once and record the result in `docs/migration-phase2.md` — a procedure never run is a guess (SC-114)
- [X] T072 [P] Update `docs/architecture.md` with the push channel, the reclaim path, the deduplication boundary, and the **D12 responsibility split** — stating plainly that provider-side dedup is assumed, not implemented (DO-002, FR-162, G-59)
- [X] T073 [P] Update `docs/testing.md` limitations with G-59, G-49, G-55, G-56, G-46, D14's 24-hour consequence, ADR-018's unhonoured `Retry-After`, and the unchanged carry-overs G-26, G-32, G-15 (DO-004, DO-005)
- [X] T074 [P] Update `README.md` with push configuration, the deduplication flags, and the `200` suppression response (DO-003)
- [X] T075 Run the full `quickstart.md` validation — all 12 scenarios, scenario 0 first
- [X] T076 Confirm ADR-016 through ADR-022 are present and current in `specs/002-push-dedup-refactor/research.md` (Principle VIII)
- [X] T077 Review pass against the Review Evidence checklist in `.specify/memory/constitution.md`, recorded in `docs/constitution-compliance.md` alongside the phase-1 review (Principle VIII)

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 Setup**: none
- **Phase 2 Foundational**: depends on Setup — blocks all stories
- **Phase 3 US1**: depends on Phase 2
- **Phase 4 US2**: depends on US1 (T027 cannot be answered before push exists)
- **Phase 5 US3**: depends on Phase 2; independent of US1/US2 in principle, sequenced third so deduplication is designed against the final channel set
- **Phase 6 US4**: depends on US3 (records the reclaim actions) and US1 (push failure conditions)
- **Phase 7 Polish**: depends on all stories

### Within Phase 5 — the order that matters

`key (T042–T044) → reclaim (T045–T047) → lease sizing (T048)`. Reclaiming stranded deliveries
before the key exists would convert a silent stall into a silent duplicate (G-58). The lease must be
sized above the provider read timeout or reclaim races every slow call.

### Parallel opportunities

- Setup: T002, T003, T005 after T001
- Foundational: T008 and T011 in parallel; the two test/implementation pairs (T009/T010, T012/T013) run as pairs, test first
- Every story's test tasks are `[P]` and written together — all must fail before implementation begins
- US1: T014–T019 in parallel, then T021 alongside T020
- US3: T035–T041 in parallel; then the delivery and submission implementation tracks are largely independent
- Polish: T068–T070, T072–T074 in parallel

---

## Parallel Example: User Story 1

```bash
# Write all US1 tests first — they MUST fail before implementation
Task: "Push end-to-end in src/test/java/com/notification/integration/PushDeliveryTest.java"
Task: "Push disabled yields CHANNEL_DISABLED in src/test/java/com/notification/integration/PushDisabledTest.java"
Task: "Existing channels unchanged in src/test/java/com/notification/regression/ExistingChannelsUnchangedTest.java"
Task: "Push auth error is terminal in src/test/java/com/notification/integration/PushAuthErrorTest.java"
Task: "Push rate limit is retried in src/test/java/com/notification/integration/PushRateLimitTest.java"
Task: "Push credentials never leak in src/test/java/com/notification/privacy/PushCredentialPrivacyTest.java"
```

---

## Implementation Strategy

### MVP scope

**Phases 1–3** (T001–T026). Delivers the push channel end to end, inert when disabled, with the
existing channels provably unchanged. That is the enhancement most visible to a caller and the one
whose premise held cleanly against the delivered system.

### Incremental delivery

1. Setup + Foundational → flags default off, closed sets widened, nothing behaves differently
2. US1 → push delivers → **validate and stop**
3. US2 → answer U-9 first; provider differences contained
4. US3 → stranded deliveries recover safely; duplicates suppressed and reported
5. US4 → retry history legible
6. Polish → deliverables, measurement, rollback rehearsal

### Notes

- Verify every test fails before implementing against it (Principle VI, non-negotiable)
- A flaky test is a failing test — fix or remove it in the same change
- No `Thread.sleep`, no `Awaitility`, no real network, no real clock in tests
- **Every phase ends by running the unmodified phase-1 suite.** One phase-1 test changes in this
  whole phase — `DuplicateSubmissionTest`, replaced not deleted
- Two requirements stay knowingly unmet and must not be quietly closed: **G-26** (§4.3's
  recipient-preference factor) and **G-32/G-49** (no destination data — push sharpens it, since a
  device token has no source but the platform that issues it)
- **G-59**: provider-side deduplication is assumed, not implemented. The tests prove this service's
  half of D12 only
