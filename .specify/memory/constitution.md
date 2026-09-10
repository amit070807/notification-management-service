<!--
SYNC IMPACT REPORT
==================
Version change: 2.0.0 -> 2.1.0
Bump rationale: MINOR. Existing guidance is materially expanded: Principle III regains
idempotency obligations and Principles II, VI and VIII are realigned with them. No principle
is removed, and none is redefined in a backward-incompatible way, so the MAJOR trigger in the
versioning policy is not met. (An argument for MAJOR exists, since delivered code becomes
non-compliant. It is answered by the versioning policy naming removal and redefinition as the
MAJOR triggers, and by the constitution's own rule that an amendment leaving code
non-compliant requires a linked remediation task rather than a larger version bump. The
remediation task is feature 002 US3.)

AMENDMENT RECORD (required by the Governance amendment procedure)
-----------------------------------------------------------------
Rule changed:
  Principle III's scope note deferring duplicate-submission semantics, deduplication and
  provider-call idempotency is removed. Six obligations are added in its place, and Register
  item 9's deferral is lifted.

Motivating requirement and evidence:
  The brownfield requirements document (Option 2) requires deduplication. Register item 9
  itself required these semantics to be "specified before any production use" and deferred
  them "to a later deliverable" - feature 002 is that deliverable, so the deferral is being
  honoured on its own terms rather than overridden.

  Evidence of need beyond the document (corrected twice; this version verified by running the
  code rather than reading it):
  baseline fact B-13 records that the entire attempt - the IN_PROGRESS write, the attempt row,
  the provider call and the outcome - is ONE transaction. A crash rolls all of it back, so the
  delivery returns to QUEUED and is retried as though nothing happened, while the provider may
  already have sent. The database can be rolled back; the notification cannot. Audit therefore
  under-reports the attempt (FR-043) and the recipient can be messaged twice.

  Two earlier drafts of this record were wrong: first that a lease expiring mid-attempt caused
  a double-send, then that deliveries were stranded in IN_PROGRESS. Neither occurs, because a
  rolled-back attempt commits nothing. Both errors came from reading claimDue without running
  it.

  The correct argument is narrower and stronger. Recoverability is not about repairing an
  existing stranding bug; it is what makes it SAFE to move the provider call out of the
  transaction, which must happen because a real provider is an external API and holding a
  database connection across it is untenable. Once the call is outside, a crash genuinely
  leaves work in flight - and the key required below is what lets the provider recognise the
  repeat.

Impact on existing code and tests:
  - Delivered code becomes NON-COMPLIANT on two counts: no provider call carries a stable
    key, and no deduplication boundary exists. This is expected and is the point of the
    amendment.
  - DuplicateSubmissionTest asserts that a second submission is NOT suppressed. It encodes
    the behaviour this amendment reverses and MUST be replaced rather than deleted, so the
    change of intent stays visible in history.
  - Phase-1 spec decision D4 and FR-008b are superseded for the submission path.
  - Principle VI now requires two adversarial tests that do not yet exist.
  - Principle VIII now requires an ADR that does not yet exist.

Migration plan:
  1. Feature 002 US3 implements both halves behind independent feature flags, default off.
  2. With the flags off, behaviour is identical to the pre-amendment baseline, so the
     amendment can merge before the implementation without breaking anything.
  3. The submission-level boundary depends on a caller contract the service cannot enforce
     (feature 002 G-55). Before the flag is enabled for any caller, the migration path MUST
     require that caller to audit its event-identifier usage.
  4. Rollback is asymmetric and MUST be documented as such: notifications suppressed while
     the flag was on were never delivered and are not recoverable afterwards.

Linked remediation task (required because this amendment leaves code non-compliant):
  specs/002-push-dedup-refactor - User Story 3, requirements FR-140 through FR-165.

Approval: project owner, 2026-09-09.

Modified principles:
  - III. Deterministic Domain Core -> III. Idempotent, Deterministic Domain Core
    Added: duplicate-execution safety via a stable per-attempt provider key; a documented
    deduplication boundary; observable suppression; no suppression after terminal failure;
    switchable and inert-when-off; documented caller-contract dependencies.
    Retained unchanged: pure-function routing, persisted routing decision, injected clock and
    id ports. Principles VI and VII still depend on these.
    Removed: the scope note deferring these concerns.
  - II. Durable Accept-Then-Process Asynchrony
    Restored: the at-least-once "workers MUST be safe to re-run on the same item" bullet,
    struck in v2.0.0, now cross-referencing Principle III.
  - VI. Test-First: duplicate submission returns to the required adversarial tests, joined by
    reclaim of a delivery stranded mid-attempt - the B-13 case.
  - VIII. Documented Decisions: the deduplication and idempotency strategy returns to the
    mandatory ADR list, now explicitly including boundary, window and caller-contract
    dependencies.

Added sections: none. Principle count remains 8; numbering unchanged; the NON-NEGOTIABLE set
(II, IV, V, VI) is unchanged.

Removed sections: none.

Register changes:
  - Item 9: deferral LIFTED. Ordering remains unspecified and that part of the item stands.

Deferred items from earlier versions that remain open: TODO(SOURCE_REQUIREMENTS_GAPS),
TODO(MESSAGE_CONTENT_MODEL) [resolved in feature 001 D1], TODO(API_AUTHENTICATION),
TODO(NFR_TARGETS), TODO(TECH_STACK) [resolved in feature 001 ADR-001..004].
-->

# Notification Management Service Constitution

This constitution governs the greenfield Notification Management Service: submission of
notifications, recipient and channel selection, asynchronous processing, delivery attempts,
retry and failure handling, status retrieval, and audit history. It is derived from
`Greenfield_Requirements.pdf` (sections 3-7). Every rule below is either (a) traceable to an
explicit requirement, or (b) an implicit engineering constraint directly justified by one, or
(c) an explicitly named assumption recorded in the Assumption and Ambiguity Register. No rule
here silently invents a requirement.

## Core Principles

### I. Contract-First Notification APIs

The two required APIs — notification submission (req. 4.1) and status retrieval (req. 4.2) —
MUST be defined in a machine-readable API contract (OpenAPI 3.x) that is committed to the
repository and is the single source of truth for request and response shapes.

- The submission contract MUST require: notification identifier, source system, event or
  correlation identifier, notification type, severity, priority, at least one recipient, and
  requested or eligible channels; it MUST accept a creation timestamp and an optional
  scheduling/expiration timestamp. `recipients` MUST have `minItems: 1` in the schema.
- The status contract MUST return: overall notification status, the selected channels, the
  delivery status broken down by recipient AND channel, and the timestamps for each recorded
  state transition.
- Every enumerated field (type, severity, priority, channel, status, failure classification)
  MUST be a closed enum in the schema. Free-form strings for these fields are forbidden.
- Requests MUST be validated at the transport boundary against the contract. No unvalidated
  or partially validated payload may reach the domain layer.
- Rejections MUST return a structured, machine-readable error body identifying every failing
  field. Rejection is an auditable event (Principle V), not a silent drop.
- Contract changes MUST be additive within a major version. Any removal, type narrowing, or
  enum-value removal is a breaking change and requires a new API major version path.

*Rationale*: The requirements specify exact payload and response content. Encoding them in an
enforced schema makes conformance verifiable by contract tests and CI schema diffing rather
than by reviewer memory.

### II. Durable Accept-Then-Process Asynchrony (NON-NEGOTIABLE)

Processing MUST be asynchronous (req. 3.1), and status MUST be retrievable for anything the
service acknowledged (req. 4.2). Together these force one governing rule:

**A submission is acknowledged only after its full initial state is durably committed.**

- Within a single transaction, submission MUST persist: the notification record, one delivery
  record per (recipient, channel) selected by routing, the routing decision, and the
  acceptance audit event. Partial commits are forbidden.
- The submission response MUST be a non-committal acceptance (HTTP 202) carrying the
  notification identifier and the status-retrieval location. It MUST NOT report delivery
  outcome, because no delivery has been attempted.
- No provider I/O, no network call to a channel provider, and no blocking wait may occur on
  the submission request thread.
- Handoff from the transactional store to the worker MUST NOT rely on a non-transactional
  enqueue. Use the transactional outbox pattern or an equivalent that cannot lose or
  phantom-create work when the process dies between commit and enqueue.
- Concurrent workers MUST claim work with a lease or row-level lock. Two workers MUST NOT be
  able to attempt the same delivery concurrently; this MUST be proven by a concurrency test.
- Workers MUST assume at-least-once delivery of work items and MUST be safe to re-run on the
  same item. A lease can expire and a process can die after a provider call but before the
  outcome is recorded; the duplicate-execution rule in Principle III is what makes that
  harmless. *(Restored in v2.1.0; struck in v2.0.0 when idempotency was deferred.)*

*Rationale*: The most common failure of an accept-then-process design is acknowledging work
that was never durably recorded, which makes the status API lie. This rule closes that gap.

### III. Idempotent, Deterministic Domain Core

- Channel routing (req. 4.3) MUST be implemented as a pure function of its declared inputs:
  requested channels, notification severity, recipient preferences as snapshotted at
  submission time, and the active routing policy version. Routing MUST NOT read the wall
  clock, generate randomness, or perform I/O.
- The routing input snapshot and the resulting decision (including the policy version and the
  reason each channel was selected or excluded) MUST be persisted. Later preference changes
  MUST NOT retroactively alter a recorded decision.
- Time and identifier generation MUST be obtained through injected abstractions (a clock port
  and an id port). Direct calls to system time or random UUID generation inside domain code
  are forbidden and MUST be caught by the architecture test in Principle VII.

*Rationale*: Determinism is what makes routing verifiable as a truth table and what makes a
recorded routing decision reproducible during incident analysis. An injected clock is also a
precondition for testing bounded retry and expiration without real waits (Principle VI).

#### Deduplication and duplicate-execution safety *(added in v2.1.0)*

Register item 9 deferred these to a later deliverable and required them to be specified "before any
production use". That deliverable is feature 002, so the deferral is lifted rather than extended.

- **Recoverability.** A delivery left non-terminal by a worker or provider that stopped
  mid-attempt MUST be reclaimable once its lease has expired, so that every delivery eventually
  reaches a terminal state. A delivery that can neither progress nor fail is worse than one that
  fails: status reports it as in progress indefinitely, and nothing surfaces it.
- **Duplicate-execution safety.** Every provider call MUST carry a key that is stable across
  re-attempts of the same logical attempt, so that a re-attempt is recognisable to the provider
  as the same send. The key MUST be derivable from data the system already holds, so it is
  identical after a crash that occurred before anything could be recorded.
- **Where duplicate suppression depends on a counterparty**, the division of responsibility MUST
  be documented and the counterparty's obligation MUST NOT be claimed as this system's guarantee.
  Whether a call that already left this service was processed is knowable only to the provider:
  the sender can make a repeat recognisable, it cannot make it harmless. Asserting otherwise
  would claim a guarantee the system does not hold.
- **A deduplication boundary MUST be defined and documented** — what makes two submissions
  duplicates, over what window, and whether a terminally failed notification resets it. An
  undocumented boundary is not a strategy; it is behaviour nobody can predict.
- **Suppression MUST be observable.** A suppressed submission MUST be recorded in audit history and
  MUST be visible to the caller. A notification that is silently dropped is indistinguishable from
  one that was lost, which is the failure mode deduplication most easily introduces.
- **A terminally unsuccessful notification MUST NOT suppress a later submission** of the same key.
  Suppressing after a permanent failure converts a delivery problem into unrecoverable data loss.
- **Deduplication MUST be switchable, and MUST be inert when switched off**, with behaviour then
  identical to the pre-deduplication baseline. This is what makes it a rollout mechanism rather
  than a one-way door, and it is required because suppression is not reversible: notifications
  suppressed while it was enabled were never delivered and cannot be recovered afterwards.
- **Where correctness depends on a caller contract the service cannot enforce**, that dependency
  MUST be documented and MUST be stated in the migration path before the feature is enabled for
  any caller. A deduplication key built from caller-supplied identifiers is the motivating case:
  if the caller reuses one, notifications are silently suppressed and nothing surfaces it.

*Rationale for the addition*: asynchrony plus bounded retry plus at-least-once claiming make
duplicate execution inevitable, not exceptional. Idempotency is what makes it harmless. The
observability and switchability rules exist because deduplication's failure mode is silence — a
notification that never arrives and never errors — which no ordinary test or alert catches.

### IV. Explicit State Machine and Failure Taxonomy (NON-NEGOTIABLE)

- The delivery state machine and the notification aggregate state machine MUST both be
  declared explicitly in code as enums with an explicit legal-transition set. Illegal
  transitions MUST raise an error, never be silently applied.
- The notification's overall status MUST be a documented, deterministic rollup of its
  per-(recipient, channel) delivery states. The rollup rule MUST be stated in the architecture
  overview and covered by tests.
- Requirement 4.2 permits an alternative state model only "if it is documented and
  defensible". Therefore any deviation from the state model recorded in the architecture
  overview REQUIRES an ADR stating the alternative, its rationale, and its rollup semantics.
- Failure classification MUST be a closed enum covering at minimum the five categories named
  in req. 4.5 — transient provider failure, permanent provider rejection, invalid recipient,
  timeout, authentication/authorization error — plus an explicit `UNKNOWN` bucket. Every
  provider adapter MUST map every outcome it can produce into this enum; no unmapped
  pass-through.
- Retryability MUST be a property of the classification, declared in one place:
  - Retryable: transient provider failure, timeout, unknown.
  - Non-retryable (terminal for that delivery): permanent provider rejection, invalid
    recipient, authentication/authorization error.
  - Authentication/authorization failure is terminal for the attempt AND MUST raise an
    operational alert, because it indicates a service configuration fault rather than a
    recipient fault. It MUST NOT be silently absorbed by the retry budget.
- Retry MUST be bounded (req. 4.5): a maximum attempt count, exponential backoff with jitter,
  and a backoff ceiling. Exhausting the budget MUST move the delivery to a distinct terminal
  state that is visibly different from a first-attempt permanent failure.
- Interaction rule between expiration and retry: **expiration wins**. Once the optional
  expiration timestamp has passed, no further attempt may be made, any scheduled retry MUST be
  cancelled, and the delivery MUST enter a distinct `EXPIRED` terminal state rather than a
  failure state. Delivery after expiry is a correctness defect.
- Interaction rule between routing and delivery: a delivery attempt on a channel that is not
  present in the persisted routing decision for that notification is forbidden.

*Rationale*: Requirement 4.5 asks for distinguishing failure kinds; distinguishing them is
only meaningful if the distinction drives behaviour. Binding retryability to a closed enum
makes "did we handle this failure correctly" a table-driven, testable question.

### V. Auditability Without Sensitive Data (NON-NEGOTIABLE)

- An append-only audit event MUST be recorded for each significant action named in req. 4.9:
  notification accepted, notification rejected, routing decision made, delivery queued,
  delivery attempted, delivery succeeded, delivery failed, retry scheduled. Add
  delivery expired and retry budget exhausted, which the state machine also produces.
- Audit records MUST be immutable. The persistence layer MUST expose no update or delete path
  for audit rows; corrections are recorded as new compensating events.
- Every audit record MUST carry the event or correlation identifier from req. 4.1 so that a
  notification's full history is reconstructable by a single query.
- Audit payloads MUST use a field allowlist enforced by schema. The following are forbidden in
  audit records, logs, traces, metrics labels, and error messages:
  - message body or rendered content (reference it by content hash or template id only),
  - provider credentials, API keys, tokens, or authorization headers,
  - raw recipient contact values (email addresses, phone numbers) — store a masked or hashed
    form plus a recipient identifier.
- Secrets MUST come from environment or a secret manager. Secrets MUST NOT be committed,
  logged, persisted, or included in any API response. CI MUST run a secret scan.
- A dedicated test MUST assert that a full end-to-end run, including a failure and a retry,
  produces audit records and log output containing none of the forbidden values. This test is
  a merge blocker.

*Rationale*: Requirement 4.9 states both a completeness obligation and a minimization
obligation, which pull against each other. The governing rule is: audit records reference
sensitive values, they never contain them.

### VI. Test-First, Deterministic, Layered Verification (NON-NEGOTIABLE)

- A failing test MUST exist before the implementation that satisfies it. Pull requests whose
  history shows implementation without a preceding failing test are rejected.
- Required layers, all present before a feature is considered done:
  - **Unit / truth-table tests** for channel routing (req. 4.3), the retry policy and failure
    classification mapping (req. 4.5), and every state machine transition, including the
    illegal ones.
  - **Contract tests** for both APIs, executed against the committed OpenAPI document,
    covering the full required field sets and the rejection paths.
  - **End-to-end integration test** covering submit → route → persist → queue → attempt →
    transient failure → bounded retry → terminal state → status retrieval → audit history,
    asserting the exact state sequence and the audit trail.
  - **Adversarial tests**: duplicate submission, reclaim of a delivery stranded mid-attempt by a
    worker or provider crash, expired notification, zero eligible channels
    after routing, all five failure classifications, retry budget exhaustion, worker crash
    between attempt and state write, and concurrent workers on one delivery.
- Tests MUST be deterministic. Sleeps, real wall-clock waits, real network calls, and reliance
  on test execution order are forbidden. Time MUST be advanced through the injected clock;
  provider behaviour MUST be driven by programmable simulated adapters that can emit each
  failure classification on demand.
- Test doubles for providers MUST implement the same port as real adapters, so the failure
  taxonomy is exercised through the production code path.
- A flaky test is treated as a failing test. It MUST be fixed or removed in the same change
  that observes it; muting or retrying it in CI is forbidden.

*Rationale*: The deliverable is a runnable prototype judged on validation rigor. Determinism
is what makes a retry-and-timing-heavy system testable at all; without an injected clock the
bounded-retry requirement cannot be verified in reasonable time.

### VII. Modular Boundaries and Justified Simplicity

- The service MUST follow a ports-and-adapters (hexagonal) structure with at least these
  separated modules: API/transport, application/orchestration, domain (notification, routing,
  retry, state machines), persistence adapters, queue/worker adapters, channel provider
  adapters, and audit.
- The domain module MUST NOT import web frameworks, persistence frameworks, queue clients, or
  any provider SDK. This MUST be enforced by an automated architecture/dependency test that
  runs in CI, not by convention.
- Every delivery channel MUST be an adapter behind one common provider port. Adding a channel
  MUST require no change to routing, retry, state machine, or audit code. This is verified by
  adding a channel in a test fixture without touching those modules.
- Simplicity is the default. A new external dependency, a new abstraction layer, a new
  datastore, or a new deployable unit REQUIRES an ADR justifying it against the simpler
  alternative that was rejected. Speculative generality for unstated future requirements is
  forbidden (YAGNI).
- Any complexity that cannot be justified against a requirement in `Greenfield_Requirements.pdf`
  or a rule in this constitution MUST be removed before merge.

*Rationale*: The evaluation criteria explicitly name modular, testable, scalable code. A
dependency rule that is machine-checked is the only version of "modular" that survives
schedule pressure.

### VIII. Documented, Defensible, Engineer-Owned Decisions

- Every key decision MUST be recorded as a numbered ADR containing: context, the options
  considered, the decision, the consequences, and the requirement or principle it serves.
  ADRs are required for at minimum: technology stack, state model, routing policy
  representation, retry parameters, persistence and queue choice, and the deduplication and
  idempotency strategy including its boundary, window and caller-contract dependencies.
- The deliverables required by req. 5 MUST be kept current in the repository and MUST be
  verified at each release gate:
  - a working prototype runnable end-to-end,
  - an architecture overview covering components, tools, execution approach, control flow, and
    key decisions,
  - setup instructions,
  - a testing document stating approach, known limitations, and accepted trade-offs.
- Limitations and trade-offs MUST be stated explicitly rather than omitted. An unstated known
  limitation is a defect in the deliverable.
- AI assistance is permitted and expected as an accelerator, but the engineer owns execution
  and quality (req. 7). Therefore:
  - AI-generated code, tests, schemas, and documentation MUST be read and understood in full
    by the engineer before merge. Unreviewed generated output MUST NOT be merged.
  - "The assistant produced it" is never a rationale in an ADR, a PR description, or a review
    response. Every decision MUST stand on its own engineering argument.
  - AI-generated tests MUST be checked for the specific failure of asserting current behaviour
    rather than required behaviour.

*Rationale*: Requirement 4.2 permits design freedom only where the design is documented and
defensible, and section 7 assigns ownership to the engineer. Both convert documentation from a
courtesy into an acceptance condition.

## Architectural and Operational Constraints

### Technology Selection

The stack is deliberately unbound by this constitution and MUST be selected and justified in
`/speckit-plan` under Principle VIII. Any selected stack MUST be able to satisfy, without
workarounds: transactional persistence of notification, delivery, routing decision, and audit
in one commit; a durable outbox or equivalent; automated dependency/architecture testing; a
controllable clock in tests; and OpenAPI contract testing. A stack that cannot support these
is disqualified regardless of other merits.

### Data Model Invariants

- `Notification` is the aggregate root. `Delivery` is a first-class entity keyed by
  (notification identifier, recipient identifier, channel) with a unique constraint on that
  triple. `Attempt` is a child of `Delivery`, unique on (delivery identifier, attempt number).
  `RoutingDecision` and `AuditEvent` are immutable records.
- Per-(recipient, channel) delivery status is required by req. 4.2 and therefore MUST be a
  stored, queryable column — never derived on the fly from log or audit scanning.
- All timestamps MUST be stored in UTC with timezone-aware types and serialized as ISO-8601.
  Local-time storage is forbidden.
- Schema changes MUST ship as versioned, forward-only migrations that run automatically at
  startup or through a documented single command. Hand-applied schema changes are forbidden.

### Scheduling and Expiration

- The optional scheduling/expiration timestamp (req. 4.1) MUST be honoured: a notification
  scheduled for the future MUST NOT be attempted before that time, and an expired notification
  MUST NOT be attempted at all.
- Expiration MUST be evaluated immediately before every attempt, not only at scheduling time,
  because a delivery may sit in backoff across the expiry boundary.
- A notification submitted with an expiration already in the past MUST be rejected at the API
  boundary with a structured error, not accepted and immediately expired.

### Observability and Operations

- Logs MUST be structured (JSON) and MUST include, where applicable, correlation identifier,
  notification identifier, delivery identifier, channel, attempt number, and failure
  classification — subject to the redaction rules in Principle V.
- The service MUST expose counters for notifications accepted and rejected, deliveries by
  terminal state, attempts by failure classification, and retries scheduled; plus a latency
  measure for provider calls.
- The service MUST expose a liveness and a readiness endpoint. Readiness MUST reflect
  datastore and queue reachability.
- Every outbound provider call MUST have an explicit connect and read timeout. An unbounded
  provider call is a defect, because the timeout classification in req. 4.5 cannot otherwise
  be produced.
- Retry backoff MUST include jitter to avoid synchronized retry storms across recipients of
  the same event.

### Security Posture

- All configuration containing credentials MUST come from environment variables or a secret
  manager. The repository MUST contain no real credentials; only `.example` files.
- Input from source systems is untrusted. Recipient addresses, identifiers, and free-text
  fields MUST be validated and length-bounded before persistence.
- Provider adapters MUST fail closed: an unclassifiable provider response is `UNKNOWN`, which
  is retryable within the bounded budget, never an assumed success.
- Dependencies MUST be pinned, and CI MUST run a vulnerability audit. A newly introduced
  high-or-critical advisory blocks merge.

### Declared Operating Defaults

The requirements state no numeric targets. The following are constitutional defaults, adopted
so that behaviour is deterministic and testable. They are assumptions (see the Register) and
are changed only by amendment:

- Maximum delivery attempts: 5 (1 initial + 4 retries).
- Backoff: exponential from 1s, factor 2, ceiling 60s, jitter +/-20%.
- Provider call timeout: 5s connect, 10s read.
- Audit retention: 90 days minimum; deletion, when implemented, MUST be by retention policy
  only and MUST itself be audited.
- Coverage floor: 90% line coverage on domain modules, 80% overall.

## Development Workflow and Quality Gates

### Change Flow

- Work proceeds through the Spec Kit flow: `/speckit-specify` → `/speckit-plan` →
  `/speckit-tasks` → `/speckit-implement`, with `/speckit-analyze` before implementation on
  any change touching more than one module.
- All work happens on feature branches. Direct commits to `main` are forbidden.
- Each pull request MUST state which requirement sections and which constitutional principles
  it serves, and MUST call out any deviation explicitly.

### Definition of Done

A change is done only when all of the following hold:

1. The behaviour is covered by tests at every layer Principle VI requires for it.
2. The OpenAPI contract, if affected, is updated and its contract tests pass.
3. State machine, routing, or retry changes are reflected in the architecture overview.
4. Any key decision is captured in an ADR.
5. Audit events for new significant actions exist and pass the sensitive-data test.
6. The end-to-end prototype still runs from a clean clone with the documented command.

### CI Gates (all blocking)

Build; lint/format; unit tests; contract tests; integration tests; architecture/dependency
test (Principle VII); sensitive-data-in-audit-and-logs test (Principle V); coverage floors;
secret scan; dependency vulnerability audit; OpenAPI breaking-change diff against the previous
version. A red gate is never bypassed by merge override; it is fixed or the change is
reverted.

### Review Evidence

Reviewers MUST verify, and record in the review, that: contract and schema match the required
field lists; state transitions are legal-set enforced; each new failure path maps to the
closed classification enum; audit events exist for new significant actions; no sensitive value
is newly logged or persisted; and no new dependency or abstraction lacks an ADR.

### Safe Change Management

- Migrations are forward-only and MUST be tested against a database containing pre-existing
  rows, not only an empty one.
- Breaking API changes require a new major version path and a documented migration window;
  they are never applied in place.
- Retry parameters, routing policy, and channel enablement MUST be configurable without a code
  change, so that operational faults can be mitigated without a deploy.
- Any rollback path that would lose durably accepted notifications is forbidden.

## Governance

This constitution supersedes all other development practices, conventions, and prior habits
for this repository. Where this document and any other guidance conflict, this document wins.

**Amendment procedure**: Amendments are proposed as a pull request that modifies this file and
MUST include: the rule changed, the requirement or evidence motivating the change, the impact
on existing code and tests, and a migration plan where existing code would become
non-compliant. An amendment merges only with explicit approval from the project owner. Merging
an amendment that leaves code non-compliant REQUIRES a linked remediation task.

**Versioning policy**: Semantic versioning on this document.
- MAJOR — a principle is removed or redefined in a backward-incompatible way, or governance is
  materially restructured.
- MINOR — a new principle or section is added, or existing guidance is materially expanded.
- PATCH — clarification, wording, or typo fixes with no change in obligation.

**Compliance review**: Compliance is checked at every pull request via the Review Evidence
checklist and the blocking CI gates. Principles marked NON-NEGOTIABLE admit no exception: a
change that violates one is rejected, not waived. Non-negotiable principles are II, IV, V,
and VI. For all other principles, a deviation MUST be recorded as an ADR naming the principle,
the reason, the scope, and the expiry condition of the deviation; undocumented deviation is a
defect. Runtime agent guidance lives in `CLAUDE.md` at the repository root and MUST be kept
consistent with this document; on conflict, this document governs.

### Assumption and Ambiguity Register

These are the gaps, contradictions, and assumptions identified in the source requirements.
They are recorded rather than silently resolved. Each MUST be confirmed or corrected during
`/speckit-specify`; an assumption that reaches implementation unconfirmed MUST be listed in the
deliverables' limitations section (Principle VIII).

| # | Item | Status / Governing default adopted |
|---|------|------------------------------------|
| 1 | Source document numbering skips sections 4.4, 4.6, 4.7, 4.8 | TODO(SOURCE_REQUIREMENTS_GAPS): the document is an excerpt. Assumed out of scope; MUST be confirmed with the requirement owner. |
| 2 | Section 4.1 lists no message body/content field, yet 4.9 forbids storing "sensitive message content" | TODO(MESSAGE_CONTENT_MODEL): assumed a content or template reference exists and is sensitive. Treated as sensitive under Principle V regardless of resolution. |
| 3 | Recipient preferences (4.3) have no stated source of truth | Assumed a local, seeded preference store owned by this service for the prototype. No external preference service is integrated. |
| 4 | Routing policy (4.3) has no stated format or authority | Assumed a versioned, declarative, externally configurable policy evaluated deterministically. Policy version is persisted with every decision. |
| 5 | Conflict between requested channel, severity, and recipient preference is unresolved | Governing default: eligible = requested ∩ channel-enabled, minus preference opt-outs, plus severity-based escalation channels only where the policy explicitly declares them. Precedence MUST be stated in the architecture overview. |
| 6 | No authentication/authorization requirement for the service's own APIs | TODO(API_AUTHENTICATION): assumed caller authentication is required at the edge. Prototype uses a static bearer token; this is explicitly not a production posture and MUST be listed as a limitation. |
| 7 | No throughput, latency, volume, or retention targets | TODO(NFR_TARGETS): the Declared Operating Defaults are engineering assumptions, not confirmed targets. |
| 8 | Scheduling vs expiration precedence unstated | Governing default: expiration wins over every other consideration, including remaining retry budget (Principle IV). |
| 9 | Ordering, deduplication, and idempotency guarantees unstated | **DEFERRAL LIFTED 2026-09-09 (v2.1.0) by decision of the project owner.** Duplicate-submission semantics, at-least-once duplicate-execution handling and provider-call deduplication are now governed by Principle III, and are specified in feature 002 — the later deliverable this item anticipated. **Ordering remains unspecified**: no cross-notification ordering guarantee is made, and that part of this item stands. |
| 10 | Cancellation/withdrawal, dead-letter replay, rate limiting, multi-tenancy, and delivery receipts are not required by the source document | Explicitly out of scope for this constitution's version. Adding any of them requires a spec change and likely a MINOR amendment. |
| 11 | Real channel provider integrations | Out of scope for the prototype. Simulated adapters behind the production provider port are used, and this MUST be stated as a limitation (req. 5). |
| 12 | Technology stack | TODO(TECH_STACK): unbound here by design; chosen and justified in `/speckit-plan` against the Technology Selection constraints. |
| 13 | Ratification date | No prior adoption date existed; the initial ratification date is the date this document was first filled in. |

**Version**: 2.1.0 | **Ratified**: 2026-09-07 | **Last Amended**: 2026-09-09
