# Feature Specification: Push Channel, Deduplication and Provider Refactoring

**Feature Branch**: `feature/phase-2-brownfield`

**Created**: 2026-09-09

**Status**: Draft — no open clarifications; constitutional precondition satisfied (v2.1.0); 1 constitutional amendment required before US3
(covering both halves of deduplication — see D7)

**Input**: Brownfield scenario covering all three proposed enhancement options, plus the
cross-cutting brownfield obligations.

**Source of record**: the brownfield requirements document (scenario overview, §4.3, §4.5, §4.9,
implementation phases, success criteria).

**Baseline**: the delivered phase-1 system —
[spec](../001-notification-management-core/spec.md) ·
[architecture](../../docs/architecture.md) · commit `0c4ca3e`, 196 tests, all gates green.

## Provenance Legend

Every requirement below carries exactly one tag. Brownfield adds a sixth to the phase-1 set,
because a specification for an existing system must distinguish *what the document asks for* from
*what the code already does*.

| Tag | Meaning | Authority |
|-----|---------|-----------|
| **[E]** | **Explicit requirement** — stated in the brownfield document, cited | Binding |
| **[C]** | **Direct consequence** — logically forced by an [E] requirement | Binding; the derivation is stated so it can be challenged |
| **[D]** | **Design-derived** — a choice that makes an [E] requirement coherent; alternatives existed | Changeable; the rejected alternative is named |
| **[A]** | **Assumption** — not derivable from the source | Requires confirmation; every [A] appears in the Gap Register |
| **[X]** | **Out of scope** — deliberately excluded, with the reason | Excluded until a requirement change says otherwise |
| **[B]** | **Baseline fact** — verifiable in the phase-1 code today, not a requirement | Statement of the existing system. Cited so a reader can tell enhancement from restatement |

**Rule applied throughout**: implementation preferences belong to `/speckit-plan`, not here. Where
an [E] requirement forces observable behaviour, the behaviour is stated; the mechanism is not.

## Baseline: what already exists

Stated first, because a brownfield specification that does not say what it is building on cannot
be reviewed. Each is verifiable in the phase-1 codebase.

| # | Baseline fact | Evidence |
|---|---------------|----------|
| **B-01** | Two channels exist: `EMAIL` and `SMS`, both simulated | `Channel` enum; ADR-005, G-22 |
| **B-02** | A channel abstraction is already extracted — `ChannelProviderPort` — and an architecture test fails if routing, retry, state or audit names a concrete channel | `ChannelProviderPort`, `ChannelExtensibilityTest` |
| **B-03** | **No idempotency or deduplication mechanism exists.** Duplicate client identifiers are accepted as independent notifications | Constitution v2.0.0 register item 9; phase-1 D4, FR-008b; `DuplicateSubmissionTest` |
| **B-04** | Routing consults three factors — requested channels, severity, policy — and is provably independent of recipient data | Phase-1 D2, FR-019a; `RoutingPreferenceIndependenceTest` |
| **B-05** | `priority` is captured, stored and returned but drives no behaviour | Phase-1 G-15 |
| **B-06** | Ten audit event types exist; all reachable in one run | `AuditEventType`; `AuditCompletenessTest` |
| **B-07** | Failure classification is a closed six-value taxonomy with retryability declared in one place | `FailureClassification`, `Retryability` |
| **B-08** | A recipient is an opaque reference; no destination data is held anywhere | Phase-1 D5, G-32 |
| **B-09** | Two source requirements are knowingly unmet: §4.3's recipient-preference factor (G-26) and the absence of destination data (G-32) | `docs/testing.md` limitations |
| **B-10** | No feature-flag mechanism exists | No flag infrastructure in the codebase |
| **B-11** | No performance or load testing exists, and no targets are asserted | Phase-1 G-11 |
| **B-12** | The API contract declares `Channel` as a **closed** enum with values `EMAIL`, `SMS` | `contracts/openapi.yaml` |
| **B-13** | **A crash mid-attempt loses the record of it, and can duplicate a send.** The whole attempt — the `IN_PROGRESS` write, the attempt row, the provider call and the outcome — is a single transaction, so a crash rolls all of it back: the delivery returns to `QUEUED` and is re-attempted as though nothing happened. But the provider may already have sent. Audit under-reports the attempt (FR-043), and the recipient can be messaged twice. The transaction also holds a database connection for the duration of an external call. **Corrected 2026-09-09**: two earlier drafts of this fact were wrong — first that the worker could double-send from an expired lease, then that a delivery was stranded in `IN_PROGRESS`. Neither happens, because a rolled-back attempt commits nothing. Verified empirically: after a crash the committed state is `QUEUED` with its lease intact | `DeliveryProcessingService.process` (`@Transactional` spanning the provider call); `JdbcDeliveryRepository.claimDue` |
| **B-14** | **No provider-call idempotency key exists**, and no agreement with a provider about duplicate handling. Constitution v2.0.0 struck the "derived per-attempt provider idempotency key" from Principle III. Harmless today only because B-13 means an orphaned delivery is never retried at all | constitution v2.0.0 sync report; `ChannelProviderPort.send` signature |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Deliver notifications over a push channel (Priority: P1)

**[E — Option 1]**

A source system requests delivery over push notifications alongside or instead of email and SMS,
and push deliveries appear in status and audit history exactly as the existing channels do.

**Why this priority**: It is the only one of the three options whose premise holds against the
existing system, it is the enhancement most visible to a caller, and it is what *creates* the
provider divergence that User Story 2 then consolidates.

**Independent Test**: Submit requesting `PUSH`, and confirm a delivery is created, attempted,
and reported per recipient and channel — without any change in behaviour for `EMAIL` or `SMS`.

**Acceptance Scenarios**:

1. **[E]** **Given** a notification requesting `PUSH`, **When** it is submitted, **Then** a
   delivery is created for that channel and its outcome is reported in status.
2. **[C ← "without breaking existing channels"]** **Given** a notification requesting `EMAIL` and
   `SMS`, **When** it is processed, **Then** its behaviour is identical to before this change —
   same states, same audit events, same status shape.
3. **[E — "ensure audit history captures channel-specific events"]** **Given** a push delivery,
   **When** its audit history is read, **Then** the push-specific outcome is recorded.
4. **[C ← B-02]** **Given** the push channel is added, **When** the architecture test runs,
   **Then** routing, retry, state and audit still name no concrete channel.
5. **[E — "handle provider authentication"]** **Given** the push provider rejects the service's
   credentials, **When** a delivery is attempted, **Then** it is classified
   `AUTH_ERROR` and raises the existing operational signal rather than being retried.
6. **[E — "rate limiting"]** **Given** the push provider signals a rate limit, **When** the
   outcome is classified, **Then** it is treated as retryable — **see G-38 for whether a
   rate limit warrants its own classification.**

---

### User Story 2 - Consolidate provider-specific delivery logic (Priority: P2)

**[E — Option 3]**

Provider-specific error handling and retry behaviour live behind one maintainable abstraction, so
that per-provider differences do not leak into the delivery pipeline.

**Why this priority**: Sequenced **after** User Story 1 deliberately. Option 3 asks to "identify
common patterns across channel implementations", but today's two channels are near-identical
simulated adapters (B-01) — there is little duplication to extract. Adding push, with genuinely
different provider semantics, is what produces the divergence worth consolidating. Refactoring
first would mean extracting an abstraction from a single example.

**Independent Test**: Add a fixture channel with distinct error codes and confirm it requires no
change to routing, retry, state or audit.

**Acceptance Scenarios**:

1. **[B-02, already true]** **Given** the channel provider abstraction, **When** a channel is
   added, **Then** routing, retry, state and audit are untouched. *This already holds; the test
   exists. It is restated so a reviewer can see what Option 3 does not need to deliver.*
2. **[E — "handle provider-specific error codes"]** **Given** two providers reporting the same
   condition with different codes, **When** each is mapped, **Then** both produce the same
   member of the closed taxonomy (B-07), with no unmapped pass-through.
3. **[E — "provider-specific retry strategies"]** **Given** providers with different retry
   characteristics, **When** a retryable failure occurs, **Then** the retry schedule may differ
   per provider while the bound remains enforced for every one.
4. **[X ← D10]** *Graceful degradation is out of scope.* §4.5 defines failure handling for this
   system as bounded retry across the five named classifications, which is already delivered. A
   provider that fails causes its deliveries to retry and terminate on the existing schedule,
   affecting no other channel or recipient.

---

### User Story 3 - Suppress duplicate notifications (Priority: P3)

**[E — Option 2]**

Redundant notifications are recognised and suppressed rather than delivered twice, and the
suppression is visible in status and audit history.

**Why this priority**: Last, because it is the only option that **reverses a recorded decision**
and requires a constitutional amendment before any code is written. Sequencing it after the other
two also means deduplication is designed against the final channel set rather than a subset.

> **✅ UNBLOCKED — constitution amended to v2.1.0 on 2026-09-09.** The deferral described below is
> lifted; Principle III now governs both halves and Register item 9 records the lift. The history
> is retained because it explains why these requirements exist and what they reversed.
>
> The source says Option 2 builds "upon the existing idempotency mechanism". **No such mechanism
> exists** (B-03). Constitution v2.0.0 register item 9 defers idempotency, deduplication and
> replay; phase-1 decision D4 chose the opposite behaviour, accepting duplicate client identifiers
> as independent notifications (FR-008b), and a test asserts the second submission is *not*
> suppressed.
>
> **One amendment covers both halves.** Register item 9 names all three of "Duplicate-submission
> semantics, at-least-once duplicate-execution handling, and provider-call deduplication", and
> v2.0.0 struck the "derived per-attempt provider idempotency key" from Principle III — which is
> precisely the delivery half. Neither level can be built under the constitution as it stands.
>
> Implementing this story therefore requires: (a) an amendment lifting the deferral, following the
> constitution's own procedure — rule changed, motivation, impact on existing code and tests,
> migration plan, owner approval; and (b) revising phase-1 D4 and FR-008b, which the submission
> half reverses. Neither may be done silently. See G-33.
>
> Item 9 also said these "MUST be specified before any production use" and deferred them "to a
> later deliverable" — this feature is that deliverable, so the deferral was honoured on its own
> terms rather than overridden.
>
> **What the amendment obliges beyond this spec**: suppression must be observable, deduplication
> must be switchable and inert when off, a terminally failed notification must not suppress a
> resubmission, and caller-contract dependencies must be documented in the migration path before
> the flag is enabled for any caller. All are already reflected in FR-140 – FR-165.

**Scope (D7)**: deduplication applies at **both** levels, which are distinct mechanisms solving
distinct problems:

| Level | Problem | Actor |
|---|---|---|
| **Submission** | Two requests that mean the same thing should not both reach the recipient | the caller |
| **Delivery** | An accepted notification must reach a terminal state even if a worker or provider crashes mid-attempt, and a re-attempt must not reach the recipient twice | the worker, **with the provider** |

The delivery half addresses B-13 and B-14, which are **coupled**. Today a delivery orphaned by a
worker or provider crash is stranded in `IN_PROGRESS` and never retried — which is the only reason
it has not also produced duplicate sends. **Reclaiming it is necessary**, because status otherwise
reports it as in progress forever, and **reclaiming it is exactly what creates the duplicate-send
exposure**: the provider may already have processed the call that the crash prevented us recording.

**Where responsibility sits (D12)**: duplicate suppression at delivery is an *agreement between the
worker and the provider*, not something the worker can enforce alone. The worker's obligations are
to reclaim the stranded delivery and to send a key that is stable across re-attempts. Recognising
that key and not processing the same send twice is the **provider's** obligation. This service
cannot implement the provider's half, and pretending otherwise would be inventing a guarantee it
does not hold.

This is the half that most deserves the word "brownfield": a correction to delivered behaviour
under conditions the happy path never exercises.

**Independent Test**: (submission) Submit two notifications the configured boundary treats as
duplicates and confirm the second is suppressed and visible as such. (delivery) Orphan a delivery
mid-attempt, confirm it is reclaimed and reaches a terminal state, and confirm the re-attempt
carries the same key as the attempt it is repeating.

**Acceptance Scenarios**:

1. **[E]** **Given** a notification the deduplication boundary treats as a duplicate of a recent
   one, **When** it is submitted, **Then** it is suppressed rather than delivered.
2. **[E — "track suppressed notifications in audit history"]** **Given** a suppression, **When**
   audit history is read, **Then** the suppression is recorded.
3. **[E — "reflected in status or audit history"]** **Given** a suppressed submission, **When**
   its status is retrieved, **Then** the suppression is visible to the caller.
4. **[C ← B-13]** **Given** a delivery orphaned in `IN_PROGRESS` by a worker or provider crash,
   **When** its lease has expired, **Then** it is reclaimed rather than stranded, and the
   notification eventually reaches a terminal state.
5. **[C ← B-14, D12]** **Given** a reclaimed delivery is re-attempted, **When** the provider call
   is made, **Then** it carries the same key as the attempt it repeats, so the provider can
   recognise it as the same send rather than a new one.
6. **[E — "reprocessing a queued delivery must not create uncontrolled duplicate side effects"]**
   **Given** a provider that honours that key, **When** a reclaimed delivery repeats a call the
   provider already processed, **Then** the recipient receives one notification, not two —
   **"uncontrolled" is undefined; see G-43. The provider's half is assumed, not implemented; see
   G-59.**
7. **[C ← FR-104]** **Given** either half is switched off, **When** submissions and deliveries are
   processed, **Then** behaviour matches the phase-1 baseline exactly — including, for the delivery
   half, the stranding behaviour of B-13.
8. **[C ← constitution v2.1.0]** **Given** the amendment is in force, **When** the delivered
   `DuplicateSubmissionTest` is re-read, **Then** it is replaced rather than deleted — it encodes
   the behaviour this story reverses, and deleting it would erase the record of the change.

---

### User Story 4 - Audit trail for retry and failure handling (Priority: P4)

**[E — §4.9 "Record significant actions with proper audit trail", scoped by D11 to retry and
failure handling]**

An operator reconstructing a failed or retried delivery can see each significant action in order:
that a retry was scheduled, that it was subsequently executed, and how each attempt failed —
including failures specific to the newly added channel.

**Why this priority**: Last, because it records actions the other stories produce. Small in scope,
and the part of §4.9 that this phase actually changes.

**Independent Test**: Run one notification through a retryable failure and a successful retry, and
confirm the scheduling and the execution of that retry are separately visible and correctly
ordered.

**Acceptance Scenarios**:

1. **[E ← §4.9 "Retry scheduled and executed"]** **Given** a retryable failure, **When** history is
   read, **Then** the retry being scheduled and the retry being executed are distinguishable
   actions, not one inferred from the other.
2. **[C ← D11]** **Given** a sequence of retries, **When** history is read, **Then** the order of
   scheduling and execution is unambiguous — a reader can tell which execution followed which
   scheduling.
3. **[C ← US1]** **Given** a push delivery that fails on a channel-specific condition, **When**
   history is read, **Then** the failure is recorded with its classification, subject to the
   existing minimisation rules.
4. **[C ← B-06]** **Given** new event types, **When** the audit completeness test runs, **Then**
   every declared type is still reachable in a single run.
5. **[C ← baseline Principle V]** **Given** any new audit record, **When** the privacy gate runs,
   **Then** no content, credential or unmasked recipient reference appears — including push
   provider credentials.
6. **[B-06, already true]** **Given** a routing decision, **When** history is read, **Then** the
   selected channels are recorded alongside it. *Restated as satisfied; see D11 for why this part
   of §4.9 needs no work.*

---

### Edge Cases

- **[C]** A notification requests `PUSH` while the push feature flag is off → behaves as though
  the channel were not enabled, with the exclusion reason recorded (G-39).
- **[C ← B-12]** An existing consumer, unaware of `PUSH`, receives a status response containing
  it → the contract's `Channel` enum is closed, so this is a compatibility question, not a
  formality. See G-46.
- **[A]** A push delivery has no device token to send to → the same shape as G-32, but sharper:
  push is unusable without a token, whereas an opaque reference is at least arguable for email.
  See G-49.
- **[E]** The push provider rate-limits the service → retryable, but whether it consumes the
  ordinary retry budget is unspecified (G-38).
- **[D]** A duplicate arrives while the original is still in flight → suppression must be decided
  against in-flight state, not only completed state, or the window has a hole.
- **[A]** A duplicate arrives after the original has permanently failed → suppressing it would
  prevent a legitimate retry by the caller. Whether failure resets the deduplication window is
  unspecified (G-41).
- **[C]** Deduplication is enabled and then rolled back → notifications suppressed while it was on
  were never delivered and are not recoverable. Rollback is not symmetric with rollout (G-48).

## Requirements *(mandatory)*

### Cross-cutting brownfield obligations

- **FR-101 [E — "without breaking existing functionality"]**: Every behaviour delivered in phase 1
  MUST continue to hold unchanged unless a requirement in this document explicitly changes it.
- **FR-102 [E — "validate changes against existing test suites"]**: The existing test suite MUST
  pass unmodified, except where a test asserts behaviour this document explicitly changes; any
  such change MUST be identified and justified individually.
- **FR-103 [E — "backward compatibility maintained or migration path clearly documented"]**: For
  each externally visible change, the system MUST either preserve the existing contract or provide
  a documented migration path. **Which of the two applies is not stated per change — see G-46.**
- **FR-104 [E — "implement feature with feature flags for gradual rollout"]**: Each enhancement
  MUST be independently switchable, and MUST be inert when switched off.
- **FR-105 [C ← FR-104]**: With every flag off, system behaviour MUST be indistinguishable from
  the phase-1 baseline — this is what makes a flag a rollback mechanism rather than a setting.
- **FR-106 [E — "document migration paths and rollback procedures"]**: Each enhancement MUST have
  a documented rollback procedure, and that procedure MUST state what is NOT recoverable by it.
- **FR-107 [E — "performance impact is measurable and acceptable"]**: The performance impact of
  the enhancements MUST be measured. **No target defines "acceptable" — see G-40.**
- **FR-108 [E — "code quality and test coverage meet or exceed existing standards"]**: Coverage
  MUST NOT fall below the existing floors, and no existing quality gate may be weakened.

### Push notification channel (Option 1)

- **FR-110 [E]**: The system MUST support delivery over a push notification channel.
- **FR-111 [C ← FR-110 + B-01]**: `PUSH` MUST become a member of the closed channel set, and MUST
  be selectable by routing, attemptable by the worker, and reportable in status — with no special
  case in any of them.
- **FR-112 [E — "without breaking existing channels"]**: Adding push MUST NOT alter the behaviour
  of `EMAIL` or `SMS`.
- **FR-113 [E — "channel-agnostic abstraction layer"]**: The abstraction through which channels
  are delivered MUST remain channel-agnostic. **[B-02]** This already holds; the requirement is
  that push does not break it.
- **FR-114 [E — "extend the routing logic to support new channel selection criteria"]**: Routing
  MUST support the selection criteria push introduces. **This is in tension with FR-113 — see
  G-51.**
- **FR-115 [E — "handle provider authentication"]**: The push provider integration MUST
  authenticate. **No scheme, credential source or provider is named — see G-37.**
- **FR-116 [C ← FR-115 + baseline Principle V]**: Push provider credentials MUST NOT appear in
  audit records, logs, metric labels or status responses.
- **FR-117 [E — "rate limiting"]**: The system MUST handle provider rate limiting. **Neither the
  limit nor the response to it is specified — see G-38.**
- **FR-118 [E — "audit history captures channel-specific events"]**: Channel-specific outcomes
  MUST be recorded in audit history, subject to FR-116 and the existing minimisation rules.

### Provider abstraction (Option 3)

- **FR-130 [E — "extract channel provider interface and base implementations"]**: Provider
  integrations MUST share a common interface and common base behaviour. **[B-02]** The interface
  exists; base behaviour for shared concerns does not.
- **FR-131 [E — "handle provider-specific error codes"]**: Each provider MUST map its own error
  codes into the closed classification taxonomy, with no unmapped pass-through. **[B-07]** The
  taxonomy exists and this rule is already enforced for the simulated providers.
- **FR-132 [E — "provider-specific retry strategies"]**: Retry behaviour MUST be configurable per
  provider. **[C]** Every per-provider strategy MUST still be bounded — a provider may not opt out
  of the bound.
- **FR-134 [E — "maintain backward compatibility with existing integrations"]**: The refactoring
  MUST NOT change the observable behaviour of existing channels.
- **FR-135 [C ← FR-130 + B-02]**: The existing architecture test MUST continue to pass unchanged —
  a refactoring that required weakening it would have removed the property it was protecting.

### Deduplication (Option 2)

> Every requirement in this group is **conditional on the constitutional amendment described in
> User Story 3**. They are specified so the amendment can be judged against concrete consequences,
> not so they can be built before it is granted.

- **FR-140 [E]**: The system MUST suppress redundant notifications rather than delivering them.
- **FR-141 [A ← D9]**: Two submissions are duplicates when they share the same **source system**
  and the same **event/correlation identifier**. Suppression is evaluated per notification: a
  second submission bearing an already-seen pair is suppressed in full, whatever recipients or
  channels it names.
- **FR-141a [C ← FR-141]**: The submitting system is responsible for event-identifier uniqueness.
  The service MUST NOT infer it and MUST NOT silently repair a violation. **See G-55: neither
  source document requires this identifier to be unique, and the service cannot enforce it.**
- **FR-141b [C ← FR-141]**: A deduplication window MUST bound how long a pair suppresses later
  submissions. **[A ← D14]** The default is **24 hours**, configurable. Neither source states a
  duration; this is an engineering assumption in the same class as the phase-1 retry bounds (G-08),
  not a requirement.
- **FR-141c [D]**: A notification that reached a terminal *unsuccessful* state MUST NOT suppress a
  later submission of the same pair. *(Design-derived: suppressing after a permanent failure would
  make that failure unrecoverable by the caller, turning a delivery problem into silent data loss.
  Alternative rejected: suppress on existence alone — simpler, but strictly worse for the caller.)*
- **FR-142 [E — "the deduplication boundary and retention policy must be documented"]**: That
  boundary and its retention policy MUST be documented as a deliverable.
- **FR-143 [E — "track suppressed notifications in audit history"]**: Every suppression MUST be
  recorded in audit history.
- **FR-144 [A ← D13]**: A suppressed submission MUST be reported to the caller **in the response to
  that submission**, not only in audit history. The response MUST identify the original notification
  the submission duplicated, so the caller can inspect it.
- **FR-144a [C ← FR-144]**: The response MUST be distinguishable from an acceptance **without
  reading the body**, because a caller that ignores an unfamiliar field would otherwise believe its
  notification was accepted — reintroducing the silent failure in a quieter form.
- **FR-144b [C ← FR-144 + FR-104]**: A suppression response can only occur once deduplication is
  enabled for that caller, so it is never a surprise to a caller who has not opted in through the
  migration path (G-56).
- **FR-146 [C ← FR-140 + phase-1 D4]**: Phase-1 FR-008b — that two submissions sharing a client
  identifier become two independent notifications — MUST be revised, and the test asserting the
  second is not suppressed MUST be replaced rather than deleted.
- **FR-147 [C ← FR-140 + FR-104]**: With submission-level deduplication switched off, submission
  behaviour MUST be identical to the phase-1 baseline.

#### Deduplication — delivery level (Option 2, second half; D7)

*Addresses B-13 and B-14, which are coupled: an orphaned delivery is stranded today, reclaiming it
is necessary, and reclaiming it is what creates the duplicate exposure. Responsibility is split by
D12 — the worker reclaims and supplies the key; the provider recognises it.*

- **FR-159 [C ← B-13]**: A delivery left non-terminal by a worker or provider that stopped
  mid-attempt MUST be reclaimable once its lease has expired, so that every delivery eventually
  reaches a terminal state. A delivery that can neither progress nor fail is worse than one that
  fails, because status reports it as in progress indefinitely.
- **FR-159a [C ← FR-159]**: Reclaiming MUST NOT bypass the bound: a reclaimed delivery MUST consume
  its retry budget in the same way as any other attempt, or a repeatedly crashing provider could
  produce unbounded attempts.
- **FR-159b [C ← FR-159 + baseline FR-034]**: Expiry MUST still outrank reclaim. A stranded
  delivery whose notification has since expired MUST reach `EXPIRED`, not be re-attempted.
- **FR-160 [E — "reprocessing a queued delivery must not create uncontrolled duplicate side
  effects"]**: Reprocessing a delivery MUST NOT produce uncontrolled duplicate side effects.
  **"Uncontrolled" is undefined — see G-43.**
- **FR-161 [C ← FR-160 + FR-159 + D12]**: Every provider call MUST carry a key that is stable
  across re-attempts of the same logical attempt, so a repeat is recognisable to the provider as
  the same send rather than a new one. **This is the worker's half of the agreement.**
- **FR-162 [A ← D12]**: The provider is responsible for recognising that key and not processing the
  same send twice. This service MUST NOT claim to prevent duplicate delivery on its own — it
  supplies the key and assumes the provider honours it. **See G-59: with a provider that ignores
  the key, a reclaimed delivery can produce a duplicate, and this service cannot detect it.**
- **FR-163 [C ← FR-161]**: The key MUST be derivable from data the system already holds, so it is
  identical on every re-attempt without having needed to be stored before the crash that caused
  the re-attempt.
- **FR-164 [C ← FR-160 + FR-104]**: With delivery-level deduplication switched off, delivery
  behaviour MUST be identical to the phase-1 baseline — including the stranding behaviour of B-13,
  since reclaim is part of this half.
- **FR-165 [C ← B-14 + constitution v2.1.0]**: The key restores, in substance, the "derived
  per-attempt provider idempotency key" that v2.0.0 struck from Principle III and v2.1.0
  reinstated.

### Audit vocabulary (§4.9 correction)

- **FR-150 [E — §4.9, scoped by D11]**: Audit history MUST record retry **scheduled** and retry
  **executed** as distinguishable actions. Scheduling is the decision to try again; execution is
  the retry actually running. An operator MUST NOT have to infer one from the other.
- **FR-150a [C ← FR-150]**: Where several retries occur, history MUST make it unambiguous which
  execution followed which scheduling.
- **FR-150b [C ← FR-150 + US1]**: Failures arising from channel-specific conditions on the new
  channel MUST be recorded with their classification, subject to the existing minimisation rules.
- **FR-151 [B-06 — already satisfied]**: Audit history records the routing decision together with
  the channels selected. The existing routing record carries both, so §4.9's "routing decision made
  and channel selected" requires no work. Stated so a reader can see it was checked rather than
  overlooked (D11).
- **FR-152 [C ← B-06]**: Every declared audit event type MUST remain reachable in a single
  end-to-end run.
- **FR-153 [C ← baseline Principle V]**: New audit records MUST satisfy the existing privacy gate
  unchanged.

### Key Entities

- **[B] Channel** — closed set, currently `EMAIL` and `SMS`. **[E]** Gains `PUSH`.
- **[B] ChannelProviderPort** — the existing channel-agnostic abstraction. **[E]** Gains shared
  base behaviour for error mapping and per-provider retry.
- **[A] Push credentials** — required by FR-115, with no source defined (G-37). Sensitive under
  the existing rules; never persisted or logged.
- **[A ← D9] Deduplication key** — the pair (source system, event/correlation identifier). Its correctness depends on a caller contract the service cannot enforce (G-55).
- **[A] Suppression record** — a suppressed submission, visible in status or audit (FR-143,
  FR-144). Its relationship to the notification entity is undefined until the boundary is.
- **[A] Feature flag** — per-enhancement switch (FR-104). No mechanism exists today (B-10).
- **[B] Notification, Delivery, DeliveryAttempt, RoutingDecision, AuditEvent** — unchanged in
  shape by this phase, except where a requirement above says otherwise.

### Out of Scope *(this iteration)*

- **[X] Real push provider integration** — the source names no provider, and phase-1's simulated
  approach (G-22) is unchanged. Nothing here proves interoperability with a real push service.
- **[X] A recipient preference store** — §4.3 still names preferences and still defines no source.
  **G-26 survives unchanged**; this is the second document to name the factor without supplying it.
- **[X] Destination data (device tokens, addresses)** — G-32 survives unchanged and sharpens
  (G-49).
- **[X] Performance targets** — measurement is required (FR-107); no target is stated (G-40).
- **[X ← D10] Resilience: circuit breaking, load shedding and channel fallback.** §4.5 defines this
  system's failure handling as bounded retry across five classifications, and that is delivered.
  **Accepted consequence**: a provider outage lasting beyond the retry window exhausts the
  deliveries in flight against it, and those are terminal — dead-letter replay is out of scope by
  constitution register item 10. With the current schedule that window is short.
- **[X] Multi-tenancy, cancellation, rate limiting of *callers*** — not present in either source
  document.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-101 [C ← FR-101/FR-102]**: The complete phase-1 test suite passes unmodified, apart from
  tests asserting behaviour this document explicitly changes — each such change individually
  justified. Zero unexplained modifications.
- **SC-102 [C ← FR-105]**: With every feature flag off, the system's observable behaviour is
  indistinguishable from the phase-1 baseline — verified by running the phase-1 suite against the
  flagged-off build.
- **SC-103 [E ← Option 1]**: A notification requesting push is delivered, and its outcome is
  visible per recipient and channel, exactly as for the existing channels.
- **SC-104 [C ← FR-112]**: For an identical email-and-SMS notification, every observable outcome
  before and after this phase is identical — states, audit event sequence, status shape.
- **SC-105 [C ← FR-111 + B-02]**: Adding the push channel required no change to routing, retry,
  state or audit logic — verified by the existing architecture test passing unchanged.
- **SC-107 [E ← Option 3]**: Two providers reporting the same condition with different codes
  produce the same classification, with no unmapped outcome in any provider.
- **SC-108 [C ← FR-132]**: No delivery on any channel exceeds its configured attempt bound,
  including channels with a provider-specific strategy.
- **SC-109 [E ← Option 2, submission level]**: A duplicate submission is suppressed, produces no
  second delivery, and returns a response the caller can distinguish from an acceptance **without
  reading the body** — verified by asserting the status code alone, since that is what a consumer
  ignoring unfamiliar fields would see.
- **SC-109a [E ← Option 2, delivery level]**: A delivery whose provider call succeeded but whose
  state write did not is re-processed and produces **one** user-visible notification, not two —
  demonstrated by forcing that exact interleaving rather than by inspection.
- **SC-109b [C ← FR-164]**: With deduplication switched off at both levels, the phase-1 suite —
  including the test asserting a second submission is not suppressed — passes unmodified.
- **SC-110 [E ← §4.9, scoped by D11]**: One run including a retry produces distinguishable records
  for the retry being scheduled and for it being executed, with the pairing between them
  unambiguous, and every declared audit event type remains reachable in a single run.
- **SC-111 [C ← FR-153]**: The privacy gate passes unchanged, including for push provider
  credentials and any new audit record.
- **SC-112 [E ← "performance impact is measurable"]**: The performance impact of each enhancement
  is measured and reported. **No pass/fail threshold is asserted, because the source states none
  (G-40).**
- **SC-113 [E ← "documentation is comprehensive and up-to-date"]**: The architecture, setup and
  testing documents reflect the enhanced system, and the deduplication boundary and retention
  policy are documented per FR-142.
- **SC-114 [C ← FR-106]**: Each enhancement has a rollback procedure that has been executed at
  least once, and that states explicitly what it cannot recover.

## Gap Register

Continues phase-1 numbering (which ended at G-32). Every gap names the source requirement that
creates it. **Unresolved** items have no answer; **Assumed** items have a working answer that
remains a candidate for correction and flows into the limitations deliverable.

| ID | Gap | Type | Source requirement that creates it | Status |
|----|-----|------|------------------------------------|--------|
| G-33 | **Option 2's premise was false.** It says it builds "upon the existing idempotency mechanism"; no such mechanism exists | **Contradiction between source and system** | Option 2 preamble, against constitution v2.0.0 item 9 and phase-1 D4/FR-008b | **RESOLVED 2026-09-09: constitution amended to v2.1.0.** Register item 9 names duplicate-submission semantics, at-least-once duplicate-execution handling and provider-call deduplication together, so one amendment covers both levels. Also requires revising D4 and FR-008b, which the submission half reverses. |
| G-54 | If §4.3's wording delta is context rather than requirement (D8), the §4.9 deltas sit in the same section and may be too | **Consistency question raised by D8** | "Applicable Functional Requirements" heading, §4.9 | **RESOLVED 2026-09-09 (D11): §4.9 applies, scoped to retry and failure handling.** The sections differ in kind: §4.3 offers "factors such as" and no option names priority; §4.9 issues a directive about what must be recorded, and retry and failure handling is what this phase changes. |
| G-57 | With graceful degradation out of scope (D10) and the provider abstraction already delivered (G-50), Option 3's remaining new work is narrow: per-provider error-code mapping and per-provider retry strategies | **Scope observation, not a gap in the source** | Option 3 after D10 | **Recorded.** Both remaining items are needed by Option 1 regardless — push introduces the first genuinely different provider error vocabulary. Option 3 may therefore be largely satisfied as a by-product of Option 1 rather than as separate work. Worth confirming before planning. |
| G-53 | **The provider call sits inside a database transaction.** A crash rolls back the attempt row along with everything else, so the system retries as though no call was made — but the database can be rolled back and the notification cannot. The same boundary holds a connection across an external call | **Defect in the existing system**, not a gap in the source | Found while scoping Option 2's second half; verified empirically | **Addressed by FR-159 plus the transaction split.** **Corrected 2026-09-09**: recorded first as a double-send, then as permanent stranding. Both were wrong. Stranding is not a pre-existing condition — it is what the split deliberately *creates*, so that a crash becomes recognisable and recoverable instead of silently retried. |
| G-58 | Fixing G-53 creates a duplicate-send exposure that does not exist today: a reclaimed delivery may already have had a successful provider call | **Consequence of the fix**, not an independent defect | Created by FR-159 against B-14 | **Addressed by FR-161–FR-163 plus D12.** The two must land together — reclaiming stranded deliveries without a key would trade a silent stall for a silent duplicate. |
| G-59 | **Delivery-level duplicate suppression depends on the provider honouring the key.** This service supplies it and cannot verify it is respected | **Assumed counterparty behaviour** | Created by the D12 split of responsibility | **Assumed, per D12.** Simulated providers honour it, so the worker's half is demonstrable; that demonstration proves nothing about a real provider. Against one that ignores the key, a reclaimed delivery can duplicate and this service cannot detect it. Belongs in the DO-004 limitations beside G-22. |
| G-34 | The document presents three options as alternatives ("Proposed Enhancement Options") | Ambiguous scope | Enhancement Options section | **RESOLVED 2026-09-09 (D6).** The project owner directed that all three be covered. |
| G-35 | The brownfield §4.3 text reads "notification severity **and priority levels**" where the greenfield text read "notification severity" | **Wording difference between source documents** | §4.3, under the heading "Applicable Functional Requirements" | **RESOLVED 2026-09-09 (D8): out of scope.** Priority appears only inside a "factors such as" list, in a section that supplies context for the enhancement options rather than new requirements, and no option names priority in its key considerations. Recorded so the difference is not lost, but not implemented. **Phase-1 G-15 stays open**: `priority` remains a mandatory field that drives no behaviour. |
| G-36 | §4.3 introduces "configured channels" as a new term, undefined — configured by whom, stored where, distinct from the policy's enabled channels or not | Missing | §4.3, brownfield text | **Assumed** to mean the policy's channel enablement, which already exists. If it means a per-recipient configuration, it is a second form of G-26 and equally unsupplied. |
| G-37 | Push provider authentication is required but no scheme, credential source, or provider is named | Missing | Option 1, "handle provider authentication" | **Assumed**: credentials arrive from configuration or a secret manager, as for any other secret, and never enter audit, logs or responses. Which provider, and which scheme, is unanswered. |
| G-38 | Rate limiting must be "handled" but no limit, and no required response, is stated. It is also absent from §4.5's five failure kinds | Missing, and a **taxonomy question** | Option 1, "rate limiting", against §4.5's closed list | **Unresolved.** Options: map to the existing transient failure; add a classification, which changes a closed enum the contract publishes; or throttle before attempting. Each has different observable consequences. |
| G-39 | Feature flags are required but their granularity, rollout criteria and lifecycle are unstated | Missing | Implementation Phase 2, "feature flags for gradual rollout" | **Assumed**: one flag per enhancement, default off, removable once an enhancement is permanent. "Gradual" is assumed to mean per-deployment, not per-caller or percentage-based. |
| G-40 | Performance impact must be "measurable and acceptable" and load testing is required, but no target defines acceptable | Missing | Phase 3 and Success Criteria, against phase-1 G-11 | **Unresolved, non-blocking.** Measurement is specified; no pass/fail criterion is asserted. G-11 survives. |
| G-41 | The deduplication boundary is explicitly delegated — what makes two submissions duplicates, over what window, and whether a failed original resets it | **Delegated by the source** | Option 2, "design a deduplication strategy" | **RESOLVED 2026-09-09 (D9).** Key: (source system, event identifier); suppression per notification. Window duration and failure-reset behaviour are assumptions, not source requirements. |
| G-55 | **Deduplication correctness depends on a caller contract the service cannot enforce.** Neither source document requires the event/correlation identifier to be unique, and phase 1 does not constrain it — `correlation_id` is indexed, not unique, and the contract describes it only as keying audit history | **Unenforceable precondition** | Created by the D9 resolution of G-41 | **MITIGATED 2026-09-09 (D13).** A caller that reuses an identifier is told on the first duplicate, in the response, naming the original notification. The precondition remains unenforceable — the service still cannot verify uniqueness — but the failure is no longer silent, which was the damaging part. |
| G-56 | Enabling deduplication changes the meaning of an existing field for existing callers, who were never told it must be unique | **Backward-compatibility risk** | Created by D9, against phase-1 FR-002/FR-048 and the published contract | **Unresolved.** FR-103's migration path MUST require callers to audit identifier usage before the flag is enabled; FR-104's flag is what makes that audit possible before any suppression occurs. |
| G-42 | The boundary and retention policy "must be documented" — an obligation on the deliverable, not a behaviour | Explicit obligation | Option 2 | **Tracked** as FR-142; satisfied only when G-41 is answered. |
| G-43 | "Reprocessing a queued delivery must not create uncontrolled duplicate side effects" — "uncontrolled" is undefined, implying some duplication is controlled and acceptable | Ambiguous | Option 2 | **Assumed**: a duplicate provider call is acceptable if it cannot produce a second user-visible notification; the phrase is read as at-least-once processing with at-most-once visible effect. |
| G-44 | §4.9 names "retry scheduled **and executed**". Whether "executed" is distinct from the existing delivery-attempted record is unclear | Ambiguous | §4.9, brownfield text, against B-06 | **RESOLVED 2026-09-09 (D11): distinct.** Scheduling is the decision to try again; execution is the retry running. Both records exist today, but nothing ties an execution to the scheduling that caused it — FR-150a closes that. |
| G-45 | §4.9 names "routing decision made **and channel selected**". The existing routing record already carries selected channels | Ambiguous | §4.9, brownfield text, against B-06 | **RESOLVED 2026-09-09 (D11): one action, already satisfied.** FR-151 records it as met rather than dropping it, so a reviewer can see the clause was checked against the baseline. No work. |
| G-46 | Backward compatibility must be "maintained **or** migration path documented" — which applies is not stated per change, and no consumer inventory exists | Ambiguous | Success Criteria | **Unresolved, per-change.** Adding `PUSH` to a **closed** contract enum (B-12) is the concrete instance: additive for tolerant consumers, breaking for strict ones. |
| G-47 | "Graceful degradation for provider failures" is undefined, and the obvious reading conflicts with an existing guarantee | **Potential contradiction** | Option 3 key considerations, against phase-1 FR-035 | **RESOLVED 2026-09-09 (D10): out of scope.** The Applicable Functional Requirements name §4.5 — bounded retry across five classifications — as this system's failure handling, and that is delivered. Resilience (circuit breaking, load shedding, channel fallback) is a separate concern the requirements do not ask for. The FR-035 conflict is therefore avoided rather than resolved. |
| G-48 | Rollback procedures are required, but rollback is not symmetric with rollout for deduplication: notifications suppressed while it was on were never delivered and cannot be recovered | **Missing, with a real consequence** | Phase 3, "document migration paths and rollback procedures" | **Assumed**: rollback restores behaviour going forward only. This MUST be stated in the rollback procedure rather than discovered. |
| G-49 | Push requires a device token. Phase-1 G-32 already records that the source supplies no destination data; push makes it unavoidable | **Sharpens an existing gap** | Option 1 against §4.1, which defines no destination field | **Unresolved.** An opaque reference is arguable for email; for push it is not, since a token is issued by the platform and has no other source. |
| G-50 | Option 3's premise is partly already delivered | Overlap with baseline | Option 3 against B-02 | **Recorded.** The interface exists and is enforced. What remains genuinely undone: per-provider error mapping beyond the simulated case, per-provider retry strategy, and graceful degradation. |
| G-51 | Option 1 asks for a "channel-agnostic abstraction layer" and, two lines later, to "extend the routing logic to support new channel selection criteria" | **Internal tension** | Option 1, key considerations | **Assumed** resolvable: the criteria belong in the routing *policy*, which is data, so the routing *logic* stays channel-agnostic. If push needs channel-specific logic in the router, the two requirements genuinely conflict. |
| G-52 | The brownfield document restates §4.3, §4.5 and §4.9 with wording that differs from the greenfield document, without saying whether it supersedes it | Ambiguous | Applicable Functional Requirements section | **Assumed superseding** for the sections it restates: §4.3 and §4.9 changes are treated as corrections. §4.5 is unchanged in substance. The greenfield document remains authoritative for sections this one does not restate. |

### Resolved Decisions

#### D6: All three enhancement options are in scope — **decided 2026-09-09 by the project owner** *(closes G-34)*

**The ambiguity**: the document presents three options under "Proposed Enhancement Options",
phrasing that reads as a choice of one.

**Decision**: all three, plus the cross-cutting brownfield obligations and the two §4.3/§4.9
corrections.

**Consequences**: the scope is larger than one option, and the three interact — deduplication must
account for the push channel, and the provider refactoring is shaped by what push introduces.
Sequencing is therefore not arbitrary: **US1 (push) precedes US2 (refactoring)**, because Option 3
asks to identify common patterns across channel implementations and today's two channels are
near-identical simulated adapters (B-01). Adding push creates the divergence worth consolidating;
refactoring first would extract an abstraction from a single example.

**Cost**: Option 2 brings a constitutional amendment with it (G-33), which is a governance action
rather than an engineering one and cannot be absorbed into implementation.

#### D7: Deduplication applies at both submission and delivery levels — **decided 2026-09-09 by the project owner**

**The question**: Option 2 contains two separable mechanisms. Submission-level suppression stops
two *requests* that mean the same thing from both reaching a recipient. Delivery-level
deduplication stops one *accepted* notification reaching a recipient twice because the worker
re-attempted it. They solve different problems, and the second could have been taken alone.

**Decision**: both.

**Why the distinction still matters**: they have different justifications and different risk. The
submission half is a new capability that reverses a recorded decision (D4) and carries an
asymmetric rollback — notifications suppressed while it was enabled were never delivered and
cannot be recovered (G-48). The delivery half is a **correction to existing behaviour**: B-13
records that the worker can genuinely double-send today. Keeping them separate in the requirements
means each can be flagged, tested, measured and rolled back independently (FR-104).

**Correction to an earlier reading**: the delivery half was initially assessed as not needing the
constitutional amendment. That was wrong. Register item 9 names "at-least-once duplicate-execution
handling, and provider-call deduplication" alongside duplicate-submission semantics, and v2.0.0
struck the "derived per-attempt provider idempotency key" from Principle III — which is exactly
the delivery half. **One amendment covers both.**

#### D8: Priority is not a routing factor in this phase — **decided 2026-09-09 by the project owner** *(closes G-35; leaves phase-1 G-15 open)*

**The observation**: the brownfield §4.3 reads "notification severity **and priority levels**"
where the greenfield §4.3 read "notification severity". An earlier draft of this specification
treated that delta as a deliberate correction and carried a user story to make `priority`
influence channel selection.

**Decision**: out of scope. There is no requirement specific to priority in the brownfield
scenario.

**Why that reading is better**: the phrase occurs only inside a "factors **such as**" list, within
a section headed "**Applicable** Functional Requirements" — which supplies context for the three
enhancement options rather than issuing new requirements. None of the three options mentions
priority among its key considerations. Treating an incidental restatement as a mandate would have
invented a requirement, which is the failure this specification discipline exists to prevent.

**Consequences**: the priority user story, its five requirements and its success criterion are
removed. **Phase-1 G-15 stays open and unchanged** — `priority` remains a mandatory submission
field that drives no behaviour, and that remains a limitation to declare rather than a defect
fixed here.

**Still open**: whether the same reasoning applies to the §4.9 wording differences, which sit in
the same "Applicable Functional Requirements" section. See G-54.

#### D9: The deduplication boundary is (source system, event identifier) — **decided 2026-09-09 by the project owner** *(closes G-41; opens G-55, G-56)*

**The question**: Option 2 says "design a deduplication strategy" and requires the boundary to be
documented — the source delegates the decision rather than making it.

**Decision**: two submissions are duplicates when they share the same source system and the same
event/correlation identifier. The owner's stated basis: *"event id will be unique for a submitter,
so combining it with submitter source will give us a unique identifier."*

**Suppression is therefore per notification, not per recipient.** A second submission bearing an
already-seen pair is suppressed in full, whatever recipients it names. That follows from the basis:
if an event identifier is unique per submitter, a second submission under the same identifier is by
definition the same event, and a differing recipient list means the caller erred rather than
expressed a new intent.

**What this rests on, and cannot enforce**: uniqueness is a *caller contract*, not a system
guarantee. Neither source document requires the event/correlation identifier to be unique, and the
delivered system does not constrain it — `correlation_id` is indexed but not unique, and the
published contract describes it only as keying the audit history. If a caller violates the
contract, notifications are **silently suppressed and never delivered** — the most damaging failure
mode this feature can have, because nothing surfaces it. Recorded as G-55.

**Backward-compatibility consequence** (G-56): this gives an existing field a new, load-bearing
meaning. Callers integrated against phase 1 were never told the identifier must be unique and may
legitimately reuse it today. Enabling deduplication against such a caller silently drops their
notifications. This is what FR-103's migration path and FR-104's feature flag are for, and the
migration path MUST require callers to audit their identifier usage before the flag is enabled.

#### D10: Resilience is out of scope; §4.5 defines failure handling — **decided 2026-09-09 by the project owner** *(closes G-47)*

**The question**: Option 3's key considerations include "implement graceful degradation for
provider failures". The phrase is undefined, and its most natural reading — reroute to another
channel when a provider fails — contradicts phase-1 FR-035, which forbids attempting a delivery on
a channel absent from its recorded routing decision.

**Decision**: out of scope. The requirement asks for **retry and failure handling**, which §4.5
defines precisely: a bounded retry strategy distinguishing transient provider failure, permanent
provider rejection, invalid recipient, timeout, and authentication or authorization error. That is
delivered and unchanged.

**How this differs from D8**: the priority removal corrected a *misreading* — a phrase inside a
"factors such as" list treated as a mandate. This is a **scoping decision**: "graceful degradation"
genuinely appears in the source, as a key consideration under Option 3. It is being scoped out
deliberately, not reinterpreted away, and the distinction matters for anyone auditing the spec
against the document.

**Consequences**: FR-133 is removed, and US2's fourth acceptance scenario becomes a statement of
what is deliberately absent. **FR-035 survives untouched** — the conflict is avoided rather than
resolved, which is the cheaper outcome. What remains of Option 3 is per-provider error-code
mapping and per-provider retry strategies; see G-57 for what that leaves.

**What is thereby not built**: circuit breaking, load shedding, and channel fallback. A provider
outage lasting beyond the retry window will exhaust the deliveries in flight against it, and those
deliveries are terminal — dead-letter replay is out of scope by constitution register item 10.
This is a known and accepted consequence, and belongs in the limitations deliverable.

#### D11: §4.9 applies, scoped to retry and failure handling — **decided 2026-09-09 by the project owner** *(closes G-44, G-45, G-54)*

**The question (G-54)**: D8 removed the priority story because §4.3's wording delta sat inside a
"factors such as" list in a section supplying context. §4.9's deltas sit in the same section, so
consistency demanded asking whether they survive the same scrutiny.

**Decision**: they do. §4.9 opens with a directive — *"Record significant actions with proper audit
trail"* — not an illustrative list, and the obligation is scoped to **retry and failure handling**,
which is what §4.5 covers and what this phase changes.

**Why this is not inconsistent with D8**: the two sections differ in kind, not only in wording.
§4.3 offers "factors **such as**", explicitly illustrative, and no enhancement option names
priority. §4.9 issues an instruction about what must be recorded, and retry and failure handling is
squarely within what the phase touches — push introduces new failure conditions, and deduplication
introduces suppression as a recordable action.

**Consequences**:

- "Retry **scheduled and executed**" is in scope and becomes FR-150/150a/150b. The distinction is
  real: today the scheduling decision and the attempt that follows it are separate records, but
  nothing ties an execution to the scheduling that caused it (G-44 confirmed).
- "Routing decision made **and channel selected**" needs **no work**. The existing routing record
  already carries the selected channels (B-06), so FR-151 is marked satisfied rather than dropped —
  a reader can see it was checked, not overlooked (G-45 confirmed).
- Audit for push-specific failure conditions falls in scope through the same clause (FR-150b).

#### D12: The worker reclaims and supplies the key; the provider deduplicates — **decided 2026-09-09 by the project owner** *(shapes FR-159 – FR-165; opens G-59)*

**The situation**: a worker or provider crash mid-attempt leaves a delivery stranded in
`IN_PROGRESS` (B-13). Reclaiming it is necessary — otherwise status reports it as in progress
forever — but reclaiming it means possibly repeating a provider call that already succeeded, which
the service has no way to detect (B-14).

**Decision**, in the owner's words: *"Idempotency should be implemented on the provider side to
ensure no duplicates are processed. It is an agreement between delivery worker and provider on how
to handle dedup at the provider end. For now we can assume the provider has the mechanism."*

**The split**:

| Obligation | Owner |
|---|---|
| Reclaim a delivery stranded by a crash | **this service** (FR-159) |
| Send a key stable across re-attempts of the same attempt | **this service** (FR-161, FR-163) |
| Recognise the key and not process the same send twice | **the provider** (FR-162) |

**Why this split is the honest one**: duplicate suppression at the delivery boundary is not
achievable by the sender alone. Once a call has left this service, whether it was processed is
knowable only to the provider. The service can make a repeat *recognisable*; it cannot make it
*harmless*. Claiming otherwise would assert a guarantee this system does not hold — the same class
of error as inventing a data source for an input the requirements never supply.

**What is therefore assumed, not built** (G-59): that the delivery provider honours the key. The
simulated providers will honour it, so the behaviour is demonstrable end to end, but that
demonstration proves the *worker's* half only. Against a real provider that ignores the key, a
reclaimed delivery can produce a duplicate and this service cannot detect it. That belongs in the
limitations deliverable, next to G-22.

#### D13: Suppression is reported to the caller in the submission response — **decided 2026-09-09 by the project owner** *(mitigates G-55; fixes the response representation)*

**The question**: G-55 records that deduplication rests on a caller contract the service cannot
enforce — event identifiers unique per source system. If a caller violates it, notifications are
suppressed. The open decision was how loudly that fails.

**Decision**: *"we need to have a response for a caller with suppression."* Every suppression is
reported to the caller in the response to the submission that was suppressed, naming the original
notification it duplicated.

**Why this is the mitigation, not merely a representation**: the G-55 risk was never really a choice
between rejecting, warning, or publishing a contract clause. It was that suppression could be
**silent** — a notification that never arrives and never errors, which no ordinary test or alert
catches. A caller that has reused an identifier now discovers it on the very first duplicate rather
than from a support ticket weeks later. Silence was the danger; the response removes it.

**Response shape**: `200 OK` with a suppression body, rather than `202 Accepted`.

`202` means *accepted for processing*, and a suppressed submission creates no delivery — returning
it would misreport what happened. The alternative considered and rejected was `202` carrying a
`suppressed: true` field: purely additive and therefore the most literal reading of FR-103, but a
consumer ignoring unfamiliar fields would see `202` and believe its notification was accepted. That
is the same silent failure in a quieter form, moved from "no difference" to "a difference they will
not notice". FR-144a makes being noticeable a requirement rather than a hope.

`409 Conflict` was also rejected: `4xx` implies caller error, but suppression is the system working
as designed on a legitimate request, and it would make routine deduplication appear as failure in
every client's error metrics.

**Backward-compatibility cost, and why it is bounded**: a new status code on an operation that has
only ever returned `202` or `400` is a real change for consumers that switch on status. The feature
flag bounds it — a suppression response can only occur once deduplication is enabled for that
caller, which per G-56 requires the migration-path audit first. The status code cannot appear before
the caller has been told to expect it.

#### D14: The deduplication window defaults to 24 hours — **decided 2026-09-09 by the project owner**

**The question**: FR-141b requires the window to be bounded. Neither source document states a
duration, so the value had to come from somewhere.

**Decision**: 24 hours, configurable, recorded as an assumption rather than a requirement.

**No constitutional amendment is needed.** Principle III (v2.1.0) requires a deduplication boundary
to be "defined and documented — what makes two submissions duplicates, over what window". It
requires the window to *exist and be documented*, not to hold a particular value. An earlier plan
note (U-6) suggested this belonged in the constitution's Declared Operating Defaults beside the
retry bounds; on inspection that was over-cautious. Those values are constitutional because they
predate any specification — this one has a specification to live in.

**What 24 hours costs**: a caller that legitimately repeats the same event within a day has the
repeat suppressed. Under D9 the event identifier is unique per submitter, so a genuine repeat would
carry a new identifier and this cannot arise — but it is exactly where D9's caller contract is most
likely to break in practice, through a periodic job reusing an identifier. D13 ensures the caller is
told rather than left to discover missing notifications.

### Open Questions

**None.** All four questions raised during drafting are resolved: D6 (scope),
D7 (deduplication levels), D8 (priority), D9 (deduplication boundary), D10 (resilience).
What remains open is recorded in the Gap Register with working answers, plus one governance
precondition — the constitutional amendment required by US3.

## Assumptions

Each is a working answer to a Gap Register entry. None is a source requirement, and each flows
into the limitations deliverable if unconfirmed at implementation time.

- **[A ← G-41 / D14]** The deduplication window defaults to **24 hours** and is configurable.
  Neither source states a duration, so this is an engineering assumption in the same class as the
  phase-1 retry bounds (G-08) — not a requirement, and changeable without a spec change.
  **Consequence worth stating**: a caller that deliberately repeats the same event within 24 hours —
  a daily reminder reusing one event identifier, say — has the repeat suppressed. Under D9's caller
  contract a genuine repeat carries a new identifier, so this cannot arise; but it is precisely the
  case where that contract is most likely to be broken in practice, by a periodic job. D13 ensures
  the caller is told rather than left guessing.
- **[A ← G-41 / D9]** A terminally failed notification does not suppress a later submission of the
  same pair (FR-141c).
- **[A ← G-55]** Submitting systems supply event identifiers unique within their own source system.
  The service cannot verify this. Where the assumption does not hold for a caller, deduplication
  must not be enabled for them.
- **[A ← G-36]** "Configured channels" means the routing policy's channel enablement, which
  already exists. If it means per-recipient configuration, it is a second instance of G-26.
- **[A ← G-37]** Push credentials come from configuration or a secret manager and never enter
  audit, logs, metric labels or responses. The provider and scheme are unnamed.
- **[A ← G-39]** One feature flag per enhancement, default off, per-deployment rather than
  per-caller, removable once an enhancement becomes permanent.
- **[A ← G-43]** "Uncontrolled" duplication is read as: a duplicate provider call is tolerable, a
  second user-visible notification is not — at-least-once processing with at-most-once visible
  effect.
- **[A ← G-44]** "Retry executed" is a distinct action from "retry scheduled".
- **[A ← G-45]** "Routing decision made and channel selected" is one action, already satisfied by
  the existing record.
- **[A ← G-48]** Rollback restores behaviour going forward only. Notifications suppressed while
  deduplication was enabled are not recoverable, and the rollback procedure must say so.
- **[A ← G-51]** Push's selection criteria are expressible as policy data, so the routing logic
  stays channel-agnostic. If push requires channel-specific logic in the router, Option 1's two
  requirements genuinely conflict.
- **[A ← G-52]** The brownfield document supersedes the greenfield document for the sections it
  restates; the greenfield document remains authoritative elsewhere.
- **[A — process]** Phase-1 gaps G-26 (recipient preferences) and G-32 (destination data) survive
  unchanged. This is the **second** document to name recipient preferences as a routing factor
  without supplying them.

## Dependencies

- **[E ← Option 2]** A constitutional amendment lifting the idempotency deferral was a
  prerequisite for US3. **Satisfied**: constitution v2.1.0, 2026-09-09, with the amendment record
  and migration plan in that document's sync impact report.
- **[E ← Option 1]** A push provider must be reachable for a real delivery to succeed. Under G-22
  it is simulated, so nothing here proves interoperability.
- **[A ← G-49]** Push delivery needs a device token that no source document supplies. The
  simulated provider accepts the opaque recipient reference; a real one would not.
- **[B]** The phase-1 test suite is the regression baseline for FR-102, and must pass unmodified
  except where a change is individually justified.
