# Specification Quality Checklist: Push Channel, Deduplication and Provider Refactoring

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-09
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Provenance Discipline (project-specific)

- [x] Every requirement carries exactly one provenance tag
- [x] Every [E] requirement cites the source text it derives from
- [x] Every [C] requirement names what it derives from
- [x] Every [D] behaviour names the rejected alternative
- [x] Every [A] assumption maps to a Gap Register entry
- [x] Every gap names the source requirement that creates it

## Brownfield Discipline (new this phase)

- [x] Existing system state is stated as baseline facts, distinct from requirements
- [x] Requirements that restate what already exists are marked as such, not claimed as new work
- [x] Contradictions between the source document and the delivered system are recorded, not reconciled silently
- [x] Governance actions are separated from engineering work
- [x] Phase-1 gaps that survive into phase 2 are identified rather than re-derived

## Validation Notes

### Iteration 4 — 2026-09-09 (resilience scoped out) — **ALL CLARIFICATIONS RESOLVED**

**Passing**: 23 of 23. No clarification markers remain.

**Decided (D10)**: graceful degradation is out of scope. §4.5 defines this system's failure
handling as bounded retry across the five named classifications, and that is delivered. Resilience
— circuit breaking, load shedding, channel fallback — is a separate concern the requirements do
not ask for.

**Recorded as a scoping decision, not a correction.** Unlike the priority removal (D8), which
corrected a misreading of a "factors such as" list, "graceful degradation" genuinely appears in
the source as an Option 3 key consideration. It is being scoped out deliberately. Anyone auditing
this spec against the document will find the phrase and should find the decision that excludes it.

**Side benefit**: phase-1 FR-035 survives untouched. The conflict is avoided rather than resolved,
which is the cheaper outcome — resolving it toward channel fallback would have meant changing a
delivered guarantee and making a delivery's channel no longer predictable from its routing decision.

**Accepted consequence, now in Out of Scope**: a provider outage lasting beyond the retry window
exhausts the deliveries in flight against it, and those are terminal — dead-letter replay is out of
scope by constitution register item 10.

**New observation (G-57)**: with degradation out and the provider abstraction already delivered
(G-50), Option 3's remaining new work is narrow — per-provider error mapping and per-provider retry
strategies, both of which Option 1 needs anyway. Option 3 may be largely satisfied as a by-product
of adding push rather than as separate work. Worth confirming before planning.

### Iteration 3 — 2026-09-09 (deduplication boundary decided)

**Passing**: 22 of 23. One clarification remains (was two).

**Decided (D9)**: the deduplication boundary is `(source system, event/correlation identifier)`,
with suppression evaluated per notification. FR-141 is now concrete, and FR-141a/b/c added.

**Two risks the decision creates**, both recorded rather than absorbed:

- **G-55 — the boundary rests on a contract the service cannot enforce.** Neither source document
  requires the event identifier to be unique, and phase 1 does not constrain it: `correlation_id`
  is indexed but *not* unique, and the published contract describes it only as keying audit
  history. If a caller reuses an identifier, notifications are silently suppressed and never
  delivered — and nothing surfaces it. That is the most damaging failure mode this feature has.
- **G-56 — it changes what an existing field means for existing callers.** Anyone integrated
  against phase 1 was never told the identifier must be unique. Enabling deduplication against
  such a caller silently drops their traffic. This is precisely what the migration path (FR-103)
  and the feature flag (FR-104) exist for, and the migration path must require callers to audit
  their identifier usage before the flag goes on.

**Recorded as assumptions rather than raised as questions** (each has a defensible default):
window duration, in the same class as the phase-1 retry bounds; and FR-141c, that a terminally
failed notification does not suppress a resubmission — suppressing after a permanent failure would
make the failure unrecoverable by the caller.

### Iteration 2 — 2026-09-09 (priority removed)

**Passing**: 22 of 23. Two clarifications remain (was three).

**Change**: the priority user story is removed (D8). The brownfield §4.3 reads "severity **and
priority levels**" where greenfield read "severity", and iteration 1 treated that as a deliberate
correction. It is not: the phrase sits inside a "factors **such as**" list, in a section headed
"**Applicable** Functional Requirements" that supplies context for the three options rather than
issuing requirements, and no option names priority in its key considerations.

Treating an incidental restatement as a mandate would have invented a requirement — the exact
failure this discipline exists to prevent, and the same class of error as the phase-1 recipient
address assumption.

**Removed**: US2 (priority routing), FR-120–FR-124, SC-106, Q1. Stories renumbered.
**Kept**: the wording difference itself, recorded in G-35 so it is not lost.
**Unchanged**: phase-1 G-15 stays open — `priority` remains a mandatory field driving no behaviour.

**New consistency question (G-54)**: if §4.3's delta is context rather than requirement, the §4.9
deltas sit in the same section and may be too. US4 currently treats them as corrections. Raised
rather than assumed either way.

### Iteration 1 — 2026-09-09

**Passing**: 22 of 23. The one open item is the three deliberate clarifications.

**Scope**: all three enhancement options (D6), with deduplication at both submission and delivery
levels (D7), plus the cross-cutting brownfield obligations and the two §4.3/§4.9 corrections.

**Findings that shaped the spec**, each recorded rather than smoothed over:

| Finding | Effect |
|---|---|
| **Option 2's premise is false** (G-33). It says it builds "upon the existing idempotency mechanism"; none exists. Constitution v2.0.0 register item 9 deferred it and phase-1 D4 chose the opposite | US4 blocked on a constitutional amendment. Governance action, not engineering |
| **The worker can double-send** (B-13, G-53). At-least-once processing with no provider-call key: a lease expiring between a successful send and the state write produces a real duplicate today | Discovered while scoping Option 2's second half. A defect in delivered code, invisible except under lease expiry or process death |
| **§4.3 wording differs between documents** — "severity **and priority levels**" vs "severity" | Initially read as a correction; **withdrawn in iteration 2 (D8)**. Context, not requirement. G-15 stays open |
| **§4.9 changed too**: "retry scheduled **and executed**", "routing decision made **and channel selected**" | Audit vocabulary corrections, ambiguity recorded as G-44/G-45 |
| **Option 3 is partly already delivered** (G-50). `ChannelProviderPort` exists with an enforcing test | Requirements marked [B] where they restate existing behaviour, so the real remaining work is visible |
| **Option 1 contains an internal tension** (G-51): a "channel-agnostic abstraction layer" and "extend the routing logic to support new channel selection criteria" | Assumed resolvable by putting criteria in policy data rather than routing logic |
| **G-26 and G-32 survive unchanged.** §4.3 names recipient preferences again and still supplies no source; push sharpens the destination-data gap (G-49) | This is the **second** document to name the preference factor without supplying it |

**Sequencing recorded as a decision, not left implicit**: US1 (push) precedes US3 (refactoring).
Option 3 asks to identify common patterns across channel implementations, but the two existing
channels are near-identical simulated adapters — adding push is what creates the divergence worth
consolidating.

**Correction made during drafting**: the delivery half of deduplication was initially assessed as
not requiring the constitutional amendment. That was wrong — register item 9 explicitly names
"at-least-once duplicate-execution handling, and provider-call deduplication", and v2.0.0 struck
the per-attempt provider idempotency key from Principle III. One amendment covers both halves.
Recorded in D7 rather than quietly fixed.

**Open — 3 clarifications**:

All questions resolved — see D6 through D10 in spec.md.

**Deliberately not raised as clarifications**, defaults recorded as assumptions instead: push
credential source (G-37), feature-flag granularity (G-39), "configured channels" (G-36),
"uncontrolled" duplication (G-43), audit event granularity (G-44, G-45), rollback asymmetry
(G-48), document precedence (G-52).

## Notes

- **No clarifications remain.** The spec is ready for `/speckit-plan`.
- **US4 additionally requires a constitutional amendment** — a governance action independent of
  the clarifications. It can be pursued in parallel but blocks implementation of both
  deduplication halves.
- G-53 is a defect in delivered code, not a gap in the source. It is worth fixing whether or not
  the rest of Option 2 proceeds.
