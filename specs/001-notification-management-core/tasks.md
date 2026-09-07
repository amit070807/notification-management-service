---

description: "Task list for Notification Management Core"
---

# Tasks: Notification Management Core

**Input**: Design documents from `/specs/001-notification-management-core/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/openapi.yaml](./contracts/openapi.yaml), [quickstart.md](./quickstart.md)

**Tests**: **MANDATORY, not optional.** Constitution v2.0.0 Principle VI is NON-NEGOTIABLE:
"A failing test MUST exist before the implementation that satisfies it." Every story phase below
leads with tests, and a pull request whose history shows implementation before a failing test is
rejected. Principles II, IV and V are likewise non-negotiable and carry their own blocking gates.

**Organization**: Tasks are grouped by user story so each can be implemented and validated
independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete work)
- **[Story]**: US1–US6, mapping to the user stories in spec.md
- Every task carries an exact file path

## Path Conventions

Single Gradle project (ADR-002). Main code `src/main/java/com/notification/`, tests
`src/test/java/com/notification/`, migrations `src/main/resources/db/migration/`.

## Blocking decisions

Five decisions were deliberately left open in [research.md](./research.md) rather than assumed.
Each has a task below and **blocks the work that depends on it**. Do not silently default them.

| ID | Decision | Blocks |
|----|----------|--------|
| U-1 | OpenAPI tooling: generate interfaces, or hand-write + conformance test | T025 → T041, T046, T055 |
| U-2 | CI platform and tooling for the 11 blocking gates | T007 → T008, T104 |
| U-3 | Routing policy reloadable at runtime, or fixed at startup | T061 → T063 |
| U-4 | Masking scheme for the recipient reference in audit and logs | T023 → T026, T029, T102 |
| U-5 | Simulated provider failures via static config or per-request directive | T073 → T074 |

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization. No domain logic.

- [X] T001 Initialize Gradle project with Java 21 toolchain and Spring Boot in `build.gradle.kts`, `settings.gradle.kts`, and commit the Gradle wrapper (`gradlew`, `gradle/wrapper/`)
- [X] T002 [P] Add PostgreSQL 16 service in `docker-compose.yml` with a named volume and no default credentials in source control
- [X] T003 [P] Create the package skeleton `api/`, `application/`, `domain/{model,routing,retry,state,port}/`, `persistence/`, `worker/`, `channel/`, `audit/`, `config/` under `src/main/java/com/notification/`
- [X] T004 [P] Configure datasource, Flyway, and application properties in `src/main/resources/application.yaml`, with all credentials read from environment variables and an `.env.example` committed in place of real values
- [X] T005 [P] Configure JaCoCo coverage verification at 90% for `com.notification.domain.*` and 80% overall in `build.gradle.kts`
- [X] T006 [P] Configure structured JSON logging with `logstash-logback-encoder` in `src/main/resources/logback-spring.xml`, including MDC fields for correlationId, notificationId, deliveryId
- [X] T007 **Resolve U-2** and record the answer as ADR-011 in `specs/001-notification-management-core/research.md`: CI platform, plus the concrete tool behind each of the 11 blocking gates
- [X] T008 Implement the CI pipeline with all 11 gates blocking and non-bypassable in the CI config file named by the U-2 answer (e.g. `.github/workflows/ci.yml`) (depends on T007)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The domain core, the schema, and the three non-negotiable gates. Every user story
depends on this phase.

**⚠️ CRITICAL**: No user story work may begin until this phase is complete.

### Architecture gate (Principle VII)

- [X] T009 Write the ArchUnit rules in `src/test/java/com/notification/architecture/ArchitectureRulesTest.java`: no class in `..domain..` may depend on `org.springframework..`, `java.sql..`, `javax.sql..`, `..api..`, `..persistence..`, `..worker..`, `..channel..`, `..config..`; and no call to `Instant.now()`, `LocalDateTime.now()` or `UUID.randomUUID()` from `..domain..`

### Ports (Principle III)

- [X] T010 [P] Define `ClockPort`, `IdPort` and `RandomPort` interfaces in `src/main/java/com/notification/domain/port/`
- [X] T011 [P] Define `ChannelProviderPort` returning a `DeliveryOutcome(success, FailureClassification, bounded diagnostic)` in `src/main/java/com/notification/domain/port/ChannelProviderPort.java` — the bounded diagnostic is what stops a provider's raw response body reaching audit
- [X] T012 [P] Define `NotificationRepositoryPort`, `DeliveryRepositoryPort`, `OutboxRepositoryPort` in `src/main/java/com/notification/domain/port/`
- [X] T013 [P] Define `AuditRepositoryPort` in `src/main/java/com/notification/domain/port/AuditRepositoryPort.java` with **only** `append` and query methods — no update, no delete (Principle V enforced by absence of mechanism)
- [X] T014 [P] Implement `MutableClock` and `SequentialIdGenerator` test fixtures in `src/test/java/com/notification/fixtures/`

### Domain enums and state machines (Principle IV)

- [X] T015 [P] Define `Channel` (EMAIL, SMS), `Severity`, `Priority`, `NotificationType` enums in `src/main/java/com/notification/domain/model/`, values matching `contracts/openapi.yaml` exactly
- [X] T016 [P] Write the failing transition truth-table test for all 9 delivery states, **including every illegal transition**, in `src/test/java/com/notification/unit/DeliveryStateTransitionTest.java`
- [X] T017 Implement `DeliveryState` with an explicit `allowedTransitions` set that throws on an illegal transition in `src/main/java/com/notification/domain/state/DeliveryState.java` (depends on T016)
- [X] T018 [P] Write the failing rollup test covering all 6 rules from data-model.md, asserting rule 4 outranks rule 6 so a wholly expired notification is not FAILED, in `src/test/java/com/notification/unit/NotificationStateRollupTest.java`
- [X] T019 Implement `NotificationState` and the rollup function in `src/main/java/com/notification/domain/state/NotificationState.java` (depends on T018)
- [X] T020 [P] Write the failing retryability matrix test for all 6 classifications in `src/test/java/com/notification/unit/FailureClassificationTest.java`
- [X] T021 Implement `FailureClassification` and `Retryability` with the partition declared in exactly one place in `src/main/java/com/notification/domain/retry/` (depends on T020)

### Schema and persistence foundations

- [X] T022 Write the Flyway migration `src/main/resources/db/migration/V1__initial_schema.sql` creating `notification`, `notification_content`, `recipient`, `routing_decision`, `routing_channel_outcome`, `delivery`, `delivery_attempt`, `audit_event`, `outbox` per data-model.md — including a **comment explaining that `client_notification_id` intentionally has no unique constraint** (D4), or a future maintainer will "fix" it and silently implement deduplication
- [X] T023 **Resolve U-4**, record it as ADR-012 in `specs/001-notification-management-core/research.md`, then implement the recipient-reference masking function in `src/main/java/com/notification/audit/Masking.java`
- [X] T024 [P] Define the sealed audit payload records, one per event type, in `src/main/java/com/notification/audit/payload/` — a closed allowlist, never a free-form map, so the content payload is structurally excluded
- [X] T025 **Resolve U-1**, record it as ADR-013 in `specs/001-notification-management-core/research.md`, and wire the chosen OpenAPI approach into `build.gradle.kts` so `contracts/openapi.yaml` governs the API surface
- [X] T026 Implement the `JdbcClient` audit adapter in `src/main/java/com/notification/persistence/JdbcAuditRepository.java`, append-only (depends on T013, T022, T023)
- [X] T027 Write the migration `src/main/resources/db/migration/V2__grants.sql` granting the application role `INSERT, SELECT` only on `audit_event`, `routing_decision` and `routing_channel_outcome`
- [X] T028 [P] Implement `ApiExceptionHandler` in `src/main/java/com/notification/api/ApiExceptionHandler.java` producing the `ValidationProblem` body that names **every** offending field, not just the first (FR-004)
- [X] T029 Implement the privacy test harness in `src/test/java/com/notification/privacy/SensitiveDataScanner.java`, scanning audit rows, log output and metric labels for a planted marker string (Principle V gate)
- [X] T030 [P] Implement the Testcontainers PostgreSQL base class in `src/test/java/com/notification/integration/PostgresIntegrationTest.java`
- [X] T031 [P] Configure Micrometer counters and the liveness/readiness endpoints, with readiness reflecting datastore reachability, in `src/main/java/com/notification/config/ObservabilityConfig.java`

**Checkpoint**: Domain core, schema and all three non-negotiable gates are in place. User stories may begin.

---

## Phase 3: User Story 1 - Submit a notification and receive a decision (Priority: P1) 🎯 MVP

**Goal**: A source system submits a notification and immediately learns whether it was accepted
or rejected, with every offending field named on rejection.

**Independent Test**: Submit a well-formed request and confirm a 202 carrying a server-issued
identity. Submit requests each missing one mandatory element and confirm each is rejected with
the offending element named. Requires no delivery machinery.

> **Scope note**: Principle II requires the routing decision and deliveries to be written inside
> the acceptance transaction, so US1 needs *a* router. It uses an identity policy — every
> requested channel selected with reason `REQUESTED_AND_ALLOWED`. That is a real implementation
> of `ChannelRouter`, not a stub to be deleted; US3 replaces the policy behind it. This dependency
> is stated rather than hidden.

### Tests for User Story 1 ⚠️ WRITE FIRST, MUST FAIL

- [X] T032 [P] [US1] Contract test for `POST /notifications` 202 and 400 responses against `contracts/openapi.yaml` in `src/test/java/com/notification/contract/SubmitNotificationContractTest.java`
- [X] T033 [P] [US1] Integration test asserting the acceptance response reports no delivery outcome and no provider was called (FR-029, SC-004) in `src/test/java/com/notification/integration/SubmissionAcceptanceTest.java`
- [X] T034 [P] [US1] Integration test asserting a single rejection names all three offending fields at once and creates no notification, delivery or routing decision (FR-004, FR-007) in `src/test/java/com/notification/integration/SubmissionRejectionTest.java`
- [X] T035 [P] [US1] Integration test asserting duplicate `clientNotificationId` yields two accepted notifications with different server-issued ids and independent deliveries (FR-008b, D4) in `src/test/java/com/notification/integration/DuplicateSubmissionTest.java`
- [X] T036 [P] [US1] Integration test asserting all six validation rules from data-model.md reject correctly, including `notBefore >= expiresAt` and an already-past `expiresAt` (FR-003d, FR-003e) in `src/test/java/com/notification/integration/SubmissionValidationTest.java`

### Implementation for User Story 1

- [X] T037 [P] [US1] Implement the `Notification`, `Recipient` and `ContentRef` domain records in `src/main/java/com/notification/domain/model/` — recipient is an **opaque reference only**, with no address field (D5)
- [X] T038 [P] [US1] Implement `RoutingDecision` and `RoutingChannelOutcome` domain records with reason codes in `src/main/java/com/notification/domain/routing/`
- [X] T039 [P] [US1] Implement `Delivery` domain record in `src/main/java/com/notification/domain/model/Delivery.java`
- [X] T040 [US1] Implement the identity `ChannelRouter` in `src/main/java/com/notification/domain/routing/ChannelRouter.java` as a pure function of `(RoutingRequest, RoutingPolicy)` — no clock, no randomness, no I/O (depends on T038)
- [X] T041 [P] [US1] Implement the submission DTOs and mappers in `src/main/java/com/notification/api/mapper/`, per the OpenAPI approach chosen in T025
- [X] T042 [P] [US1] Implement the `JdbcClient` notification, content, recipient and delivery repositories in `src/main/java/com/notification/persistence/`
- [X] T043 [P] [US1] Implement the `JdbcClient` outbox repository in `src/main/java/com/notification/persistence/JdbcOutboxRepository.java`
- [X] T044 [US1] Implement `SubmissionService.accept()` in `src/main/java/com/notification/application/SubmissionService.java` as **one `@Transactional` unit** writing notification, content, recipients, routing decision, deliveries, audit events and the outbox row — no provider I/O on this thread (Principle II) (depends on T037–T043)
- [X] T045 [US1] Implement Jakarta Validation constraints for all six boundary rules in the request DTO in `src/main/java/com/notification/api/dto/SubmitNotificationRequest.java`, so rejection is structural rather than procedural (depends on T041)
- [X] T046 [US1] Implement `NotificationController.submit()` returning 202 with a `Location` header in `src/main/java/com/notification/api/NotificationController.java` (depends on T044, T045)
- [X] T047 [US1] Record `NOTIFICATION_ACCEPTED` and `NOTIFICATION_REJECTED` audit events from `src/main/java/com/notification/application/SubmissionService.java` and `src/main/java/com/notification/api/ApiExceptionHandler.java` (depends on T026, T044)

**Checkpoint**: Notifications can be submitted, validated, accepted and durably recorded. Nothing is delivered yet.

---

## Phase 4: User Story 2 - Retrieve the status of a notification (Priority: P2)

**Goal**: An operator sees overall status, selected channels, per-recipient-per-channel delivery
status, and the relevant timestamps.

**Independent Test**: Submit a notification, then retrieve its status and assert all four
required elements are present, including one row per recipient-and-channel combination.

**Depends on**: US1 (needs accepted notifications to report on).

### Tests for User Story 2 ⚠️ WRITE FIRST, MUST FAIL

- [ ] T048 [P] [US2] Contract test for `GET /notifications/{id}/status` 200 and 404 against `contracts/openapi.yaml` in `src/test/java/com/notification/contract/NotificationStatusContractTest.java`
- [ ] T049 [P] [US2] Integration test asserting a just-accepted notification is found immediately and reports a not-yet-delivered state rather than 404 (FR-015, SC-002) in `src/test/java/com/notification/integration/StatusImmediacyTest.java`
- [ ] T050 [P] [US2] Integration test asserting 2 recipients × 2 channels yields 4 independently varying delivery entries (FR-012, SC-003) in `src/test/java/com/notification/integration/StatusMatrixTest.java`
- [ ] T051 [P] [US2] Integration test asserting an unknown server-issued identity returns 404, distinguishable from a known notification with no completed deliveries (FR-018) in `src/test/java/com/notification/integration/StatusUnknownIdTest.java`

### Implementation for User Story 2

- [ ] T052 [P] [US2] Implement the status query DTOs and mapper in `src/main/java/com/notification/api/mapper/StatusMapper.java`, asserting the content payload is never joined into the response (FR-056)
- [ ] T053 [US2] Implement `StatusQueryService` in `src/main/java/com/notification/application/StatusQueryService.java`, computing the overall state via the rollup function from T019 rather than by scanning audit (FR-014, FR-016)
- [ ] T054 [US2] Implement the status read queries in `src/main/java/com/notification/persistence/JdbcStatusRepository.java`
- [ ] T055 [US2] Implement `StatusController` in `src/main/java/com/notification/api/StatusController.java` (depends on T053)

**Checkpoint**: US1 and US2 together form the smallest demonstrable end-to-end slice.

---

## Phase 5: User Story 3 - Select delivery channels for each recipient (Priority: P3)

**Goal**: Channels are chosen from requested channels, severity and routing policy, and the
decision is recorded with a reason per channel.

**Independent Test**: Drive selection with a table of inputs and assert the selected channel set
and recorded reasons for each combination, without performing any delivery.

**Scope deviation (G-26)**: §4.3 also names recipient preferences. They are excluded by D2, so
this story delivers **three of the source's four factors**.

### Tests for User Story 3 ⚠️ WRITE FIRST, MUST FAIL

- [ ] T056 [P] [US3] Write the routing truth-table test in `src/test/java/com/notification/unit/ChannelRouterTest.java`, covering every combination of requested channels × severity × policy rule, asserting the selected set and every reason code
- [ ] T057 [P] [US3] Write the determinism test asserting identical inputs always produce identical selections (FR-023, SC-012) in `src/test/java/com/notification/unit/RoutingDeterminismTest.java`
- [ ] T058 [P] [US3] Write the **preference-independence test** in `src/test/java/com/notification/unit/RoutingPreferenceIndependenceTest.java`: two submissions differing only in recipient reference must produce identical selections and reason codes (SC-013). This test fails if anyone later adds a recipient attribute that routing could consult — the G-26 regression worth catching
- [ ] T059 [P] [US3] Integration test asserting a recorded decision is unchanged after the policy changes (FR-021) in `src/test/java/com/notification/integration/RoutingDecisionImmutabilityTest.java`
- [ ] T060 [P] [US3] Integration test asserting a recipient with no selected channel reaches `UNDELIVERABLE`, not perpetual pending (FR-024) in `src/test/java/com/notification/integration/EmptyRoutingTest.java`

### Implementation for User Story 3

- [ ] T061 [US3] **Resolve U-3** and record it as ADR-014 in `specs/001-notification-management-core/research.md`: is the routing policy reloadable at runtime, or fixed at startup?
- [ ] T062 [P] [US3] Author the versioned policy resource `src/main/resources/routing-policy.yaml` with an explicit `version` field
- [ ] T063 [US3] Implement the immutable `RoutingPolicy` value object and its loader in `src/main/java/com/notification/domain/routing/RoutingPolicy.java` and `src/main/java/com/notification/config/RoutingPolicyLoader.java`, per the U-3 answer (depends on T061, T062)
- [ ] T064 [US3] Replace the identity router from T040 with policy evaluation in `src/main/java/com/notification/domain/routing/ChannelRouter.java` — **policy is the deciding authority**; requested channels and severity are inputs it consults (FR-020a). The router must have no parameter, field or import through which a recipient attribute could reach it
- [ ] T065 [US3] Persist `policy_version` and per-channel reason codes with every decision in `src/main/java/com/notification/persistence/JdbcRoutingDecisionRepository.java` (FR-022, FR-026)
- [ ] T066 [US3] Expose `selectedChannels`, `channelOutcomes` and `routingPolicyVersion` in `src/main/java/com/notification/api/mapper/StatusMapper.java` (depends on T052)
- [ ] T067 [US3] Record the `ROUTING_DECISION_MADE` audit event with the policy version from `src/main/java/com/notification/application/SubmissionService.java` (depends on T026)

**Checkpoint**: Routing is policy-driven, explained, reproducible, and provably independent of recipient data.

---

## Phase 6: User Story 4 - Attempt delivery asynchronously (Priority: P4)

**Goal**: Accepted notifications are delivered on the system's own time, honouring not-before and
expiry, with outcomes visible through status.

**Independent Test**: Accept a notification, run processing, assert each delivery reaches a
terminal outcome visible in status, with submission having returned before any attempt.

**Depends on**: US1 (accepted notifications), US3 (recorded decisions to attempt against).

### Tests for User Story 4 ⚠️ WRITE FIRST, MUST FAIL

- [ ] T068 [P] [US4] Integration test for the full lifecycle submit → outbox → claim → attempt → DELIVERED → status, using the injected clock and invoking the worker directly with no sleeps, in `src/test/java/com/notification/integration/DeliveryLifecycleTest.java`
- [ ] T069 [P] [US4] **Concurrency test**: two workers against one delivery produce exactly one claim and one attempt row per attempt number (Principle II) in `src/test/java/com/notification/integration/ConcurrentWorkerClaimTest.java`
- [ ] T070 [P] [US4] Integration test asserting no attempt occurs before `notBefore`, and the delivery becomes eligible once it passes, across all four present/absent combinations (FR-031, SC-014) in `src/test/java/com/notification/integration/NotBeforeTest.java`
- [ ] T071 [P] [US4] Integration test asserting no attempt occurs after `expiresAt` (FR-032, SC-007) in `src/test/java/com/notification/integration/ExpiryTest.java`
- [ ] T072 [P] [US4] Integration test asserting an attempt on a channel absent from the recorded routing decision is impossible (FR-035) in `src/test/java/com/notification/integration/UnroutedChannelGuardTest.java`

### Implementation for User Story 4

- [ ] T073 [US4] **Resolve U-5** and record it as ADR-015 in `specs/001-notification-management-core/research.md`: are simulated provider failures driven by static configuration or a per-request directive? A per-request directive puts a test affordance in the production contract — decide deliberately
- [ ] T074 [P] [US4] Implement `SimulatedEmailProvider` and `SimulatedSmsProvider` in `src/main/java/com/notification/channel/`, both implementing `ChannelProviderPort` and able to emit all six classifications deterministically, per the U-5 answer (depends on T073)
- [ ] T075 [P] [US4] Implement the outbox poller with `SELECT … FOR UPDATE SKIP LOCKED` in `src/main/java/com/notification/worker/OutboxPoller.java`
- [ ] T076 [US4] Implement delivery claiming with a lease in `src/main/java/com/notification/persistence/JdbcDeliveryRepository.java` using `FOR UPDATE SKIP LOCKED` (depends on T042)
- [ ] T077 [US4] Implement `DeliveryProcessingService` in `src/main/java/com/notification/application/DeliveryProcessingService.java`, driving one attempt through the state machine (depends on T017, T074, T076)
- [ ] T078 [US4] Implement the **pre-attempt eligibility check** in `src/main/java/com/notification/application/DeliveryProcessingService.java`, evaluating both `notBefore` and `expiresAt` immediately before every attempt, not only at first processing (FR-033) — a delivery can cross either boundary while waiting in backoff
- [ ] T079 [US4] Implement `DeliveryWorker` in `src/main/java/com/notification/worker/DeliveryWorker.java`, invocable directly so tests need no real waiting (Principle VI)
- [ ] T080 [US4] Implement the guard rejecting any attempt on a channel absent from the persisted routing decision in `src/main/java/com/notification/application/DeliveryProcessingService.java` (FR-035) (depends on T065)
- [ ] T081 [US4] Persist `delivery_attempt` rows with outcome and bounded, sanitised diagnostic in `src/main/java/com/notification/persistence/JdbcDeliveryAttemptRepository.java` — adapters must not propagate a provider's raw response body, which may echo submitted content
- [ ] T082 [US4] Record `DELIVERY_QUEUED`, `DELIVERY_ATTEMPTED`, `DELIVERY_SUCCEEDED` and `DELIVERY_EXPIRED` audit events from `src/main/java/com/notification/application/DeliveryProcessingService.java` (depends on T026)

**Checkpoint**: Notifications are delivered end to end. Retry is not yet implemented — failures are terminal.

---

## Phase 7: User Story 5 - Retry retryable failures within a bound (Priority: P5)

**Goal**: Retryable failures are retried within a finite bound; non-retryable failures stop
immediately; every failure kind is classified and recorded.

**Independent Test**: Drive the simulated provider to produce each of the five named failure kinds
and assert, per kind, whether a retry was scheduled, how many attempts occurred, and the terminal
outcome — with no dependence on real timing.

**Depends on**: US4 (needs attempts to retry).

### Tests for User Story 5 ⚠️ WRITE FIRST, MUST FAIL

- [ ] T083 [P] [US5] Integration test per failure classification asserting the retry decision, attempt count and terminal state for all six (SC-005) in `src/test/java/com/notification/integration/FailureClassificationBehaviourTest.java`
- [ ] T084 [P] [US5] Integration test asserting zero deliveries exceed the configured maximum attempts across a full run (FR-038, SC-006) in `src/test/java/com/notification/integration/RetryBoundTest.java`
- [ ] T085 [P] [US5] Integration test asserting `EXHAUSTED` is distinguishable from a first-attempt `FAILED` (FR-044) in `src/test/java/com/notification/integration/ExhaustedVsFailedTest.java`
- [ ] T086 [P] [US5] **Expiry-outranks-budget test**: put a delivery in backoff, advance the clock past `expiresAt`, run the worker, assert `EXPIRED` — not `FAILED`, not `EXHAUSTED` — with budget remaining (FR-034) in `src/test/java/com/notification/integration/ExpiryOutranksRetryTest.java`
- [ ] T087 [P] [US5] Integration test asserting an unclassifiable provider outcome becomes `UNKNOWN`, is retried, and is never recorded as success (FR-041) in `src/test/java/com/notification/integration/UnknownOutcomeTest.java`
- [ ] T088 [P] [US5] Integration test asserting `AUTH_ERROR` is terminal **and** raises an operational signal rather than being absorbed as a recipient fault (FR-042) in `src/test/java/com/notification/integration/AuthErrorSignalTest.java`

### Implementation for User Story 5

- [ ] T089 [P] [US5] Implement `RetryPolicy` in `src/main/java/com/notification/domain/retry/RetryPolicy.java` as a pure function returning the next attempt delay, with jitter drawn from `RandomPort` so the domain stays deterministic
- [ ] T090 [P] [US5] Bind max attempts, base delay, factor, ceiling and jitter to configuration properties in `src/main/java/com/notification/config/RetryProperties.java` and `application.yaml` (constitution defaults: 5 attempts, 1s base, factor 2, 60s ceiling, ±20%)
- [ ] T091 [US5] Implement classification mapping in `src/main/java/com/notification/channel/SimulatedEmailProvider.java` and `SimulatedSmsProvider.java` so every provider outcome maps into the closed enum with no unmapped pass-through (FR-037) (depends on T074)
- [ ] T092 [US5] Implement retry scheduling and the `RETRY_SCHEDULED` transition in `src/main/java/com/notification/application/DeliveryProcessingService.java`, setting `next_attempt_at` (depends on T077, T089)
- [ ] T093 [US5] Implement budget exhaustion producing `EXHAUSTED`, distinct from `FAILED`, in `src/main/java/com/notification/application/DeliveryProcessingService.java` (depends on T092)
- [ ] T094 [US5] Implement provider connect and read timeouts in `src/main/java/com/notification/channel/` adapters and bind them in `src/main/java/com/notification/config/ChannelProperties.java` so `TIMEOUT` is producible at all (constitution Observability — an unbounded provider call is a defect)
- [ ] T095 [US5] Implement the operational signal for `AUTH_ERROR` as a metric and a WARN-level structured log in `src/main/java/com/notification/application/DeliveryProcessingService.java` (FR-042)
- [ ] T096 [US5] Record `DELIVERY_FAILED`, `RETRY_SCHEDULED` and `RETRY_BUDGET_EXHAUSTED` audit events from `src/main/java/com/notification/application/DeliveryProcessingService.java` (depends on T026)

**Checkpoint**: Bounded retry works, every failure kind behaves per the taxonomy, and expiry outranks the budget.

---

## Phase 8: User Story 6 - Reconstruct what happened from audit history (Priority: P6)

**Goal**: The full lifecycle of a notification is reconstructable from its audit history, and that
history carries no sensitive data.

**Independent Test**: Run one notification through acceptance, routing, a failing attempt, a retry
and a terminal outcome; assert every named action type appears, and that no forbidden value
appears anywhere.

**Depends on**: US1–US5 (they produce the actions this story records and queries).

### Tests for User Story 6 ⚠️ WRITE FIRST, MUST FAIL

- [ ] T097 [P] [US6] Integration test asserting one full run produces **all 10 event types** — the 8 from §4.9 plus `DELIVERY_EXPIRED` and `RETRY_BUDGET_EXHAUSTED` (FR-046, FR-053, SC-008) in `src/test/java/com/notification/integration/AuditCompletenessTest.java`
- [ ] T098 [P] [US6] Integration test asserting the complete history is retrievable by `correlationId` alone and every record is timestamped (FR-048, FR-049) in `src/test/java/com/notification/integration/AuditCorrelationTest.java`
- [ ] T099 [P] [US6] Integration test asserting audit records cannot be updated or deleted through any available path (FR-050) in `src/test/java/com/notification/integration/AuditImmutabilityTest.java`
- [ ] T100 [P] [US6] **Privacy test (merge blocker)**: plant a marker string in the content payload, run a full lifecycle including a failure and a retry, assert zero occurrences of the marker, zero credentials and zero unmasked recipient references across audit records, log output and metric labels — including the case where the simulated provider echoes content in its error text (SC-009) in `src/test/java/com/notification/privacy/NoSensitiveDataLeakTest.java`

### Implementation for User Story 6

- [ ] T101 [US6] Implement the audit query by correlation identifier in `src/main/java/com/notification/persistence/JdbcAuditRepository.java` (depends on T026)
- [ ] T102 [US6] Apply the T023 masking function in `src/main/java/com/notification/audit/AuditRecorder.java` to every recipient reference written to audit, logs and metric labels (FR-052)
- [ ] T103 [US6] Verify every audit payload record uses the sealed allowlist from T024 and that `payload_ref` is the only route by which content is referenced, via a test in `src/test/java/com/notification/privacy/AuditPayloadAllowlistTest.java` (FR-051, FR-054)
- [ ] T104 [US6] Wire the privacy scanner from T029 into CI as a blocking gate, in the CI config file created by T008 (e.g. `.github/workflows/ci.yml`) (depends on T008, T100)

**Checkpoint**: All six user stories are functional and independently testable.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T105 [P] Write the architecture overview (DO-002) at `docs/architecture.md`: components, tools, execution approach, control flow, key decisions — and **name §4.3's unmet recipient-preference factor explicitly** (G-26, DO-006)
- [ ] T106 [P] Write setup instructions (DO-003) at `README.md`, verified by a reviewer running them from a clean clone with no undocumented step (SC-011)
- [ ] T107 [P] Write the testing approach, limitations and trade-offs (DO-004) at `docs/testing.md`, listing **every** carried limitation: G-26, G-32, G-12, G-22, G-01, G-11, G-15, G-25, and any U-item still unresolved (DO-005)
- [ ] T108 [P] Add the extensibility fixture channel in `src/test/java/com/notification/architecture/ChannelExtensibilityTest.java`, proving a new channel needs no change to routing, retry, state or audit (Principle VII)
- [ ] T109 [P] Verify the `Channel`, `Severity`, `Priority`, `NotificationType`, `DeliveryState`, `NotificationState`, `FailureClassification` and `RoutingReasonCode` enums match `contracts/openapi.yaml` exactly, via a test in `src/test/java/com/notification/contract/EnumParityTest.java`
- [ ] T110 Run the full validation in `specs/001-notification-management-core/quickstart.md` — all 11 scenarios against a live instance
- [ ] T111 Confirm JaCoCo floors in `build.gradle.kts` pass at 90% domain / 80% overall, and that no test was muted or retried to get there
- [ ] T112 [P] Confirm every ADR in `specs/001-notification-management-core/research.md` is present and current, including ADR-011 through ADR-015 created by the U-item tasks (Principle VIII)
- [ ] T113 Review pass against the Review Evidence checklist in `.specify/memory/constitution.md`, recording the verification in the pull request

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 Setup**: no dependencies
- **Phase 2 Foundational**: depends on Setup — **blocks all user stories**
- **Phase 3 US1**: depends on Phase 2
- **Phase 4 US2**: depends on US1
- **Phase 5 US3**: depends on US1 (replaces the identity router from T040)
- **Phase 6 US4**: depends on US1 and US3
- **Phase 7 US5**: depends on US4
- **Phase 8 US6**: depends on US1–US5
- **Phase 9 Polish**: depends on all stories

### A note on story independence

The template's ideal is fully independent stories. That is **not achievable here**, and pretending
otherwise would be dishonest: the source describes one pipeline, and Principle II requires routing
and delivery records to be written inside the acceptance transaction. The real shape is a chain —
US1 → US3 → US4 → US5, with US2 and US6 as observability layers over whatever exists. Each story
is still **independently testable** at its own checkpoint, which is the property that matters for
incremental delivery.

### Parallel opportunities

- Setup: T002–T006 in parallel after T001
- Foundational: T010–T015 in parallel; the three test/implementation pairs (T016/T017, T018/T019, T020/T021) run in parallel as pairs, tests first within each
- Every story's test tasks are marked [P] and can be written together — they must all fail before implementation begins
- US1: T037, T038, T039, T041, T042, T043 in parallel
- Polish: T105–T109 and T112 in parallel

---

## Parallel Example: User Story 1

```bash
# Write all US1 tests first - they MUST fail before any implementation
Task: "Contract test for POST /notifications in src/test/java/com/notification/contract/SubmitNotificationContractTest.java"
Task: "Acceptance reports no delivery outcome in src/test/java/com/notification/integration/SubmissionAcceptanceTest.java"
Task: "Rejection names all offending fields in src/test/java/com/notification/integration/SubmissionRejectionTest.java"
Task: "Duplicate clientNotificationId yields two notifications in src/test/java/com/notification/integration/DuplicateSubmissionTest.java"
Task: "All six validation rules reject correctly in src/test/java/com/notification/integration/SubmissionValidationTest.java"

# Then the domain records and adapters, in parallel
Task: "Notification, Recipient, ContentRef records in src/main/java/com/notification/domain/model/"
Task: "RoutingDecision and RoutingChannelOutcome in src/main/java/com/notification/domain/routing/"
Task: "Delivery record in src/main/java/com/notification/domain/model/Delivery.java"
Task: "Submission DTOs and mappers in src/main/java/com/notification/api/mapper/"
Task: "JdbcClient notification/content/recipient/delivery repositories in src/main/java/com/notification/persistence/"
Task: "JdbcClient outbox repository in src/main/java/com/notification/persistence/JdbcOutboxRepository.java"
```

---

## Implementation Strategy

### MVP scope

**Phase 1 + Phase 2 + Phase 3 (US1)** — T001–T047. Delivers durable, validated submission with a
retrievable identity and a complete audit trail for acceptance and rejection. Nothing is delivered
to a channel yet.

Adding **Phase 4 (US2)** — through T055 — gives the smallest slice that demonstrates the
source's two required APIs together, and is the better demo boundary if the goal is to show
something end to end.

### Incremental delivery

1. Setup + Foundational → the three non-negotiable gates are live before any feature code
2. US1 → submission works → **validate and stop**
3. US2 → status works → demo the two required APIs
4. US3 → routing becomes policy-driven and explained
5. US4 → deliveries actually happen
6. US5 → retry and the failure taxonomy
7. US6 → audit history and the privacy gate
8. Polish → deliverables DO-001 through DO-006

### Notes

- Verify every test fails before implementing against it (Principle VI, non-negotiable)
- A flaky test is a failing test — fix or remove it in the same change; never mute or retry it
- No `Thread.sleep`, no `Awaitility`, no real network, no real clock in tests
- Commit after each task or logical group
- Five U-item decisions block specific tasks. Answer them; do not default them
- Two source requirements are knowingly unmet — G-26 and G-32 — and DO-006 requires both to be
  named in the architecture overview and the limitations statement
