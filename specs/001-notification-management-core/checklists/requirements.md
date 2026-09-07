# Specification Quality Checklist: Notification Management Core

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-07
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

## Provenance Discipline (project-specific, per user instruction)

- [x] Every requirement carries exactly one provenance tag ([E]/[C]/[D]/[A]/[X])
- [x] Every [E] requirement cites a source section
- [x] Every [C] requirement names the requirement it derives from
- [x] Every [D] behavior names the alternative that was rejected
- [x] Every [A] assumption maps to a Gap Register entry
- [x] Every gap names the source requirement that creates it
- [x] No implementation preference has been recorded as a functional requirement
- [x] Deliverable obligations (source §5) are kept separate from system requirements

## Validation Notes

### Iteration 4 — 2026-09-07 (after Q3 resolution) — **ALL ITEMS PASS**

**Passing**: 20 of 20. The last open checklist item is now closed: no clarification markers
remain. **Q3 resolved by the requirement owner: option A** — two independently optional
timestamps, a not-before time and an expiry time.

**Applied**:

- FR-003 retained verbatim as §4.1's single-field source text, with FR-003a–FR-003e added as the
  interpretation. Tagged `[A ← D3]`, never `[E]`, so a reviewer comparing against §4.1 sees the
  two-field model is an interpretation.
- FR-003c: all four combinations of the two optional fields are valid inputs.
- FR-003d: not-before at or after expiry is rejected — a window that can never open. This check
  only became reachable once D3 split the field.
- FR-003e: already-passed expiry is rejected at submission rather than accepted and instantly
  expired, which would leave the expired terminal state ambiguous between "expired in flight"
  and "expired on arrival".
- FR-031 and FR-032 re-anchored to FR-003a and FR-003b respectively; they were previously two
  candidate readings of one field, and are now two behaviors.
- FR-033 widened: both timestamps are evaluated immediately before each attempt, not only at
  first processing.
- D3 recorded under Resolved Decisions. G-03 marked RESOLVED; G-29 (not-before is a floor, not a
  punctuality promise) and G-30 (the system's clock decides; caller skew shifts caller intent,
  not system behavior) opened with working answers.
- SC-014 added, exercising all four present/absent combinations. Four edge cases updated or
  added.
- "Open Questions Blocking Full Specification" replaced with an "Open Questions — None" statement
  pointing at D1, D2, D3 and at G-26 as the single knowingly-unmet source requirement.

**Cost of being wrong on Q3**: low and one-directional. Option B (expiry only) is a strict subset
of what was built, so the whole of the waste would be an unused not-before field.

### Iteration 3 — 2026-09-07 (after Q2 resolution)

**Passing**: 19 of 20 items. **Q2 resolved by the requirement owner**: routing draws on requested
channels, severity, and configured routing policy — three of §4.3's four factors, excluding
recipient preferences.

**Root cause confirmed before applying**: §4.3 names recipient preferences as a routing factor,
but §4.1's submission fields do not carry them and no section of §3–§5 defines a preference store,
owner, shape, or lifecycle. §4.3 requires an input the source never makes obtainable (G-05).
The exclusion is therefore justified by the source's own incompleteness, not only by owner
preference — implementing the factor would have required inventing a data source and a preference
model and presenting the result as a requirement.

**Applied**:

- FR-019 retained verbatim as the full §4.3 requirement, marked NOT FULLY IMPLEMENTED, so the
  unmet portion stays visible in the requirement sequence.
- FR-019a added as the owner-scoped three-factor rule, with an explicit prohibition on
  substituting any proxy for preferences under another name.
- FR-020a added: policy is the deciding authority, severity and requested channel are inputs.
- FR-025 withdrawn, its number retained rather than reused so the deferral stays legible.
- D2 recorded under Resolved Decisions, stating the decision, its source-side justification, its
  cost, and the guard against silent substitution.
- Gap Register: G-04 resolved and largely dissolved; G-05 reclassified from "Missing" to
  **internal inconsistency** and resolved by deferral; G-26 (unmet explicit requirement), G-27
  (precedence among the three), G-28 (deferral vs cancellation) opened.
- DO-006 added: any unmet explicit source requirement must be named in DO-002 and DO-004, and the
  prototype must not be presented as satisfying §4.3.
- SC-012 rescoped to three factors; SC-013 added to prove selection is independent of preference
  data, guarding against a proxy creeping in.
- US3 rewritten to three factors, with scenario 3 re-anchored on policy version change instead of
  preference change, and scenario 6 added for preference-independence.

**Deviation ledger** — the one item where this specification knowingly does not meet the source:

| ID | Unmet requirement | Why | Reportable in |
|----|-------------------|-----|---------------|
| G-26 | §4.3 recipient-preference routing factor | No preference data is supplied by §4.1 and no preference source is defined anywhere (G-05) | DO-002, DO-004 (per DO-006) |

**Still open — 1 of the original 3 clarifications**: Q3 (G-03, scheduling versus expiration).

### Iteration 2 — 2026-09-07 (after Q1 resolution)

**Passing**: 19 of 20 items. **Q1 resolved by the requirement owner: option A.**

**Applied**: A notification carries an opaque content payload supplied by the source system,
treated as sensitive throughout and referenced indirectly in audit. Changes made:

- Added FR-055–FR-059 in a new "Message content" subsection, explicitly labelled as flowing
  from an owner decision rather than from source text.
- Upgraded FR-051 and FR-054 from `[A]` to `[C]` — the audit field allowlist is now derivable
  from a known content model instead of guessed.
- G-02 marked **RESOLVED** in the Gap Register; decision recorded as D1 under a new "Resolved
  Decisions" heading with its consequences and its authority.
- Three residual gaps opened by the decision and recorded rather than absorbed: G-23 (content
  mandatory — assumed yes, FR-058), G-24 (payload size bound — assumed declared, FR-059), G-25
  (retention after terminal state — unresolved, non-blocking, ties to G-11).
- Confirmed out of scope as a consequence: templating/rendering, and any inspection or
  transformation of payloads.
- SC-009 sharpened to a marker-string search across audit, status, and operational output.
- Edge cases added for missing, empty, and oversized payloads, and for provider error text
  potentially echoing content.

**Still open — 2 of the original 3 clarifications**:

| Q | Gap | Why it cannot be defaulted |
|---|-----|----------------------------|
| Q2 | G-04 — no precedence among the four routing factors | Determines whether a recipient opt-out can be overridden by severity — a consent decision, not an engineering one. |
| Q3 | G-03 — "scheduling/expiration timestamp" is one field naming two opposite constraints | Determines whether FR-031 exists at all. |

### Iteration 1 — 2026-09-07

**Passing**: 19 of 20 items. Three questions (Q1, Q2, Q3) raised rather than decided, each
having no defensible default and each changing specification content rather than configuration.
Recorded inline in spec.md with answer options and implications, cross-referenced from the Gap
Register.

**Deliberately not raised as clarifications** (defaults applied and recorded as assumptions
instead, because reasonable industry defaults exist): caller authentication (G-10), retention
(G-11), recipient identity shape (G-17), channel set (G-18), ordering (G-19), creation
timestamp authority (G-21), simulated providers (G-22).

**Checked and confirmed clean**: no functional requirement names a technology, storage
mechanism, transport, or internal structure. Statements that could have leaked implementation
were restated as observable behavior — notably FR-015 ("an accepted notification MUST be
retrievable from the moment of acceptance") rather than any durability mechanism, and FR-023
("routing MUST be repeatable") rather than any purity or function-shape constraint.

**Constitution alignment**: idempotency is absent from this specification by design.
Constitution v2.0.0 register item 9 defers it to a later deliverable, so G-12 is recorded as
out of scope rather than as a requirement gap to be filled.

## Notes

- Items marked incomplete require spec updates before `/speckit-plan`.
- **Q1 is answered.** The blocking concern from iteration 1 is cleared: the content model
  exists, so the data model, the audit allowlist (FR-054), and the scope of DO-001 are all
  determinable.
- **All 20 checklist items pass. The specification is ready for `/speckit-plan`.**
- Carry G-26 forward into planning as a first-class item, not a footnote. It is the only place
  this specification knowingly falls short of the source, and DO-006 requires it to be visible in
  the architecture overview and the limitations statement.
- 30 Gap Register entries remain recorded. Those still unresolved (notably G-01 missing source
  sections, G-11 absent targets, G-25 content retention) are non-blocking and carry working
  answers, but DO-005 requires every one that reaches implementation unanswered to appear in the
  limitations statement.
