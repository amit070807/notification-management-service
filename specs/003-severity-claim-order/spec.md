# Feature Specification: Severity-Ordered Delivery Claim

**Feature Branch**: `feature/003-severity-claim-order` *(not yet created)*

**Created**: 2026-09-10

**Status**: Ready for `/speckit-plan`. **0 open clarifications** — U-1 and U-2 resolved by the project owner on 2026-09-10 and recorded as D-15 and D-16 below.

**Input**: Project owner request — "a feature on severity, very simple, minimal requirement."
Scope chosen from four candidates: severity is to influence **the order in which the worker claims
due deliveries**.

**Baseline**: the delivered phase-2 system —
[spec 002](../002-push-dedup-refactor/spec.md) · commit `68727a2`, 308 tests, all gates green.

**Deliberately small.** One ordering rule, one place. No new state, no new endpoint, no new audit
type, no schema column. If this specification grows past one page it has stopped being the feature
that was asked for.

## Provenance Legend

Unchanged from [spec 002](../002-push-dedup-refactor/spec.md#provenance-legend): **[E]** explicit ·
**[C]** direct consequence · **[D]** design-derived · **[A]** assumption · **[X]** out of scope ·
**[B]** baseline fact. `B-` numbers below are local to this specification; `G-`, `FR-` and `SC-`
numbers continue the project-wide sequences.

## Baseline: what already exists

| # | Baseline fact | Evidence |
|---|---------------|----------|
| **B-01** | The worker claims due deliveries **oldest-state-change-first**, in batches of 50, under `FOR UPDATE SKIP LOCKED`. Severity plays no part | `JdbcDeliveryRepository.claimDue` (`ORDER BY d.state_changed_at`); `DeliveryWorker.BATCH` |
| **B-02** | Severity today drives **routing only** — a per-channel `minimumSeverity` floor and `escalateAtSeverity` | `ChannelRouter.evaluate`, `routing-policy.yaml` |
| **B-03** | `notification.severity` is stored as **`text`**, and the `Severity` enum publishes no rank. Ordering on the stored value is therefore lexicographic: `CRITICAL < HIGH < LOW < MEDIUM`. `ORDER BY severity DESC` yields `MEDIUM, LOW, HIGH, CRITICAL` — the near-inverse of the intended order, and `ASC` gets the top two right while inverting `MEDIUM` and `LOW` | `V1__initial_schema.sql`; `Severity.java` |
| **B-04** | The claim query reads **`delivery` alone**. Severity lives on `notification`; there is no join in the claim, and no severity column on `delivery` | `JdbcDeliveryRepository.claimDue` |
| **B-05** | The claim is `UPDATE … FROM claimed … RETURNING`. SQL does not guarantee that `RETURNING` preserves the CTE's `ORDER BY`, so **claim order and processing order are separate questions** | `claimDue`; `DeliveryWorker.runOnce` iterates the returned list |
| **B-06** | `idx_delivery_claimable (state, next_attempt_at)` does not cover the existing `state_changed_at` sort either, so this feature inherits an already-unindexed sort rather than introducing one | `V1__initial_schema.sql` |
| **B-07** | `priority` is captured, stored and returned but drives no behaviour | Phase-1 G-15 |
| **B-08** | Feature flags exist and default to false, and "flag off must equal the previous baseline" is an established requirement | `FeatureFlags`, spec 002 FR-104/FR-105 |

## User Scenario

**As** an operator, **when** a backlog of due deliveries exists, **I want** `CRITICAL` notifications
attempted before `LOW` ones, **so that** a queue built up during an incident does not deliver the
alerts last.

**Independent test**: enqueue a `LOW` delivery, then a `CRITICAL` one, then run the worker with a
batch size of one. The `CRITICAL` delivery is attempted first, despite being younger.

## Requirements

| # | Requirement |
|---|-------------|
| **FR-201 [E]** | When more due deliveries exist than one batch can claim, deliveries of higher severity MUST be claimed before deliveries of lower severity |
| **FR-202 [C]** | Severity rank MUST be `CRITICAL > HIGH > MEDIUM > LOW`, **declared in exactly one place** and not inferred from the stored string (B-03) or from enum declaration order |
| **FR-203 [C]** | Among deliveries of equal severity, the existing oldest-state-change-first order MUST be preserved. Severity refines the existing order; it does not replace it |
| **FR-204 [C]** | Ordering MUST NOT change **which** deliveries are eligible — only the order in which eligible ones are taken. Due-time, lease, expiry and reclaim eligibility are untouched |
| **FR-205 [C]** | Deliveries within a claimed batch MUST be **processed** in the claimed order, not merely selected in it (B-05) |
| **FR-206 [D]** | The behaviour MUST be flag-gated, defaulting off, and with the flag off the claim MUST be indistinguishable from B-01. *Alternative rejected*: shipping unflagged. This changes a query every delivery passes through, and spec 002 established that such changes carry a rollback switch (B-08) |
| **FR-207 [E]** | Low-severity deliveries MAY be starved without bound. Under a sustained stream of higher-severity traffic a `LOW` delivery MAY remain eligible and unclaimed indefinitely. The system MUST NOT introduce an age-based override, a starvation timer, or any other mechanism that lets age outrank severity (D-15). *Consequence, not an oversight*: a `LOW` notification that never arrives during an incident is the accepted trade. This MUST be stated in operator documentation, because an operator seeing an aged `LOW` delivery sitting unclaimed needs to recognise correct behaviour rather than diagnose a stall |
| **FR-207a [C]** | Because starvation is unbounded, the flag is the only mitigation. Turning it off MUST restore the age-only order and release any starved backlog (FR-206) |
| **FR-207b [E]** | Reclaimed deliveries (spec 002 FR-159, arriving `IN_PROGRESS` with an expired lease) MUST participate in severity ordering on the same terms as any other due delivery. Recovery work MUST NOT hold absolute priority (D-16). The new ordering therefore applies to the whole claim, not to one branch of its predicate |
| **FR-208 [X]** | `priority` remains behaviour-free (B-07). It is a separate field with a separate register entry (G-15); conflating the two would resolve G-15 by accident |
| **FR-209 [X]** | No change to routing, the state machine, retry, expiry, audit or the API contract. Claim order is not observable in any response |

## Decisions

Both open clarifications were answered by the project owner on 2026-09-10. Neither is an assumption.

| # | Decision | Consequence |
|---|----------|-------------|
| **D-15** | **U-1 answer (a): unbounded starvation is accepted.** Severity means what it says. No age-based override, no starvation cap | Keeps the feature to one ordering rule, which is what was asked for. Costs an operational hazard that must be documented rather than mitigated: the queue can hold an eligible `LOW` delivery forever. Rejected alternative (b) — age outranks severity past a threshold — would have added a second ordering rule, a config value and a third test, and would have made the ordering non-total and therefore harder to assert |
| **D-16** | **U-2 answer: reclaimed deliveries participate in severity ordering.** They are ordinary due work, not privileged recovery work | The `ORDER BY` applies to the whole claim rather than one branch, which keeps FR-202's "exactly one place" honest — a second ordering path for reclaims would be a second place. Two things follow and are intended: a new `CRITICAL` delivery can be claimed ahead of a `LOW` reclaimed one, so recovery is not expedited; and because a reclaimed delivery's `state_changed_at` is old, within equal severity it still sorts early by FR-203 without needing a rule of its own |

## Gap Register

| # | Gap | Kind | Disposition |
|---|-----|------|-------------|
| **G-60** | **Lexicographic severity ordering is a near-miss trap.** `ORDER BY severity ASC` orders `CRITICAL, HIGH` correctly and `MEDIUM, LOW` backwards, so a wrong implementation passes any test that only checks `CRITICAL` first | **Defect risk created by this feature** | Addressed by FR-202 and SC-202: the truth table must cover all four values pairwise, not just the extremes |
| **G-61** | Severity is not on `delivery` (B-04), so ordering by it requires either a join in the claim or denormalisation onto `delivery`. Both have costs; the source states no performance target (phase-1 G-11), so neither can be chosen on measured grounds | **Design question deferred to `/speckit-plan`** | Open. Measure per spec 002 FR-107 and record the result; assert no threshold |
| **G-62** | No source document requests severity-ordered delivery. This is a project-owner request, not a requirement traceable to the brownfield or greenfield documents | **Provenance** | Recorded. FR-201 is tagged `[E]` against the owner's instruction, which is the authority here, not the source documents |
| **G-63** | Claim order is invisible in the API. An operator cannot confirm from a status response that severity ordering happened | **Observability limit** | Accepted for a feature this size. SC-203 tests it at the repository boundary instead |
| **G-64** | **Unbounded starvation is now a deliberate property, and it is invisible.** With claim order unobservable (G-63) and no starvation metric in scope, an aged eligible `LOW` delivery is indistinguishable from a stalled worker. The operator has no signal that separates the two | **Operational hazard accepted by D-15** | Documented, not mitigated. FR-207 requires it stated in operator documentation; a starvation metric or an age gauge would be the fix and is out of scope for a one-rule feature. Revisit if D-15 is ever reversed |

## Success Criteria

| # | Criterion |
|---|-----------|
| **SC-201** | With a batch size of one, a younger `CRITICAL` delivery is claimed before an older `LOW` one |
| **SC-202** | A truth table covers all four severities **pairwise** — the guard against G-60 |
| **SC-203** | Two deliveries of equal severity are claimed oldest-first, unchanged from B-01 |
| **SC-204** | With the flag off, the claim behaviour is byte-for-byte the phase-2 baseline, and the existing suite passes unmodified |
| **SC-205** | The processing order observed by the worker matches the claim order (FR-205) |
| **SC-206** | A `CRITICAL` delivery is claimed ahead of a `LOW` **reclaimed** delivery whose lease has expired, confirming reclaims are ordered rather than privileged (FR-207b, D-16) |
| **SC-207** | Under a continuous stream of `CRITICAL` work a `LOW` delivery remains unclaimed, and turning the flag off claims it. Starvation is asserted as **intended behaviour**, and its only mitigation asserted alongside it (FR-207, FR-207a) |

## Out of Scope

Preemption of an in-flight attempt · per-severity retry budgets, expiry or rate limits · severity on
metrics labels · exposing claim order in any response · resolving G-15 (`priority`) · any change to
what `severity` means to routing.
