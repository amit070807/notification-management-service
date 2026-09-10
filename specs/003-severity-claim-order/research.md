# Research & Decisions: Severity-Ordered Delivery Claim

**Phase 0** · Spec: [spec.md](./spec.md) · Baseline: commit `68727a2` · 2026-09-10

ADR numbering continues the project sequence: spec 001 holds ADR-001–015, spec 002 holds ADR-016–024.

---

## ADR-025: Severity rank is an explicit field on the `Severity` enum

**Status**: Accepted.

**Context**: FR-202 requires the rank `CRITICAL > HIGH > MEDIUM > LOW` declared in **exactly one
place**, and explicitly forbids inferring it from the stored string or from enum declaration order.
Baseline fact B-03 is why: severity is stored as `text` and the enum publishes no rank, so
lexicographic order is `CRITICAL < HIGH < LOW < MEDIUM`.

**Options**:

1. **`ordinal()`** — the enum already happens to be declared `LOW, MEDIUM, HIGH, CRITICAL`, so
   `ordinal()` gives the right answer today.
2. **A separate `SeverityRank` lookup class** holding a map.
3. **An explicit `rank` field on the enum constant.**

**Decision**: option 3.

**Reasoning**: option 1 is what FR-202 forbids by name, and for a good reason — it makes the ordering a
side effect of the order in which someone typed the constants. Reordering the declaration for
readability, or inserting a new severity between two existing ones, would silently change delivery
order with no test naming the rank it broke.

Option 2 satisfies "one place" but creates a second thing to keep in step with the enum: add a
constant and the map is silently incomplete. A rank on the constant itself cannot go missing, because
the constructor requires it.

**Consequence**: adding a severity is a compile-time prompt to state its rank. The rank is the single
declaration that both the SQL ordering (ADR-027) and any Java comparison read from.

## ADR-026: Order by a join to `notification`, not by denormalising severity onto `delivery`

**Status**: Accepted. Resolves **G-61**.

**Context**: severity lives on `notification`; the claim query reads `delivery` alone (B-04). Ordering
by severity needs the value in the claim somehow.

**Options**:

1. **Join `notification` inside the claim CTE.** One source of truth; adds a join to the query every
   delivery passes through.
2. **Denormalise `severity` onto `delivery`.** No join in the hot path; needs a new column, a
   backfill migration, a change to the acceptance write path, and a second copy of a value that can
   now drift.

**Decision**: option 1, the join.

**Reasoning**: the spec forecloses option 2 in its own header — "no new state, no new endpoint, no new
audit type, **no schema column**". That is not an arbitrary constraint: this feature is one ordering
rule, and a denormalised column would make it a data-model change with a migration and a write-path
change, in a system whose acceptance transaction is governed by a NON-NEGOTIABLE principle.

G-61 notes that neither option can be chosen on **measured** grounds, because no source document states
a performance target (phase-1 G-11). That is still true and the decision does not pretend otherwise —
it rests on scope and on the single-source-of-truth argument, not on a number. The join cost is
measured and reported per spec 002 FR-107 with no threshold asserted.

**Consequence**: `delivery.notification_id` is already a foreign key with an index on the `notification`
primary key, so the join is a primary-key lookup per candidate row. B-06 records that the existing
`state_changed_at` sort is itself unindexed, so this feature inherits an unindexed sort rather than
introducing one.

## ADR-027: The SQL rank expression is generated from the enum, not written by hand

**Status**: Accepted.

**Context**: PostgreSQL cannot order by the rank without being told it, because the column is `text`
(B-03). The obvious implementation is a hand-written `CASE n.severity WHEN 'CRITICAL' THEN 4 … END`.
That would put the rank in a second place, which FR-202 forbids.

**Options**:

1. **Hand-written `CASE` in the SQL.** Simple, and a direct FR-202 violation — two declarations that
   can disagree, with the disagreement invisible until delivery order is wrong in production.
2. **Generate the `CASE` from `Severity.values()`** at repository construction.
3. **Sort in Java after claiming.** Rejected under ADR-028 — see below.

**Decision**: option 2.

**Reasoning**: the generated fragment cannot drift from the enum, because it is derived from it. A new
severity constant appears in the SQL automatically, with the rank its constructor required.

String interpolation into SQL is normally a defect, so the reason it is safe here is recorded rather
than assumed: the interpolated values are enum constant names and integers from a closed compile-time
set, never caller input. `SeverityOrderingSqlTest` asserts the generated fragment covers every constant and ends in a
`ELSE 0` fail-safe, so a partially-generated expression fails a test rather than silently sorting an
unlisted severity as null — whose position under `DESC` would depend on `NULLS FIRST/LAST` defaults.

**Consequence**: one method produces the fragment; the repository embeds it. `SeverityRankTest` covers
the rank pairwise across all four values (SC-202), which is the guard G-60 asks for.

## ADR-028: Claim order is preserved in SQL by a row number, not re-sorted in Java

**Status**: Accepted.

**Context**: FR-205 requires the batch to be **processed** in claim order, not merely selected in it.
B-05 is the reason it is a separate requirement: the claim is `UPDATE … FROM claimed … RETURNING`, and
SQL does not guarantee `RETURNING` preserves the CTE's `ORDER BY`.

**Options**:

1. **Re-sort the returned list in Java.** Needs severity on the `Delivery` record — a domain-model
   field carried solely to re-derive an order the database already knew.
2. **`ROW_NUMBER()` in the ordered CTE, and order the final projection by it.**
3. **Trust `RETURNING` order.** This is what B-05 says cannot be trusted; it would pass today and
   break on a plan change, which is the worst failure shape available.

**Decision**: option 2.

**Reasoning**: the ordering stays entirely in the query that computes it, and the domain model gains
nothing it does not need. Option 1 would also mean two places that know how to order deliveries — the
CTE and the Java comparator — which is the same duplication ADR-027 exists to avoid, one layer up.

**Consequence**: `ClaimOrderPreservedTest` asserts the order the worker observes, not the order the CTE
computed, since only the former is what FR-205 is about.

**Amended during implementation.** The intended shape — one CTE that both locks and numbers — does not
run: PostgreSQL rejects `FOR UPDATE` in any query containing a window function outright, with
*"FOR UPDATE is not allowed with window functions"*. Locking and numbering are therefore separate CTEs:

```text
locked     rows taken under the lock, ordered, limited; exposes the rank as a column
candidate  numbers that already-locked set with ROW_NUMBER()
claimed    UPDATE … FROM candidate … RETURNING
SELECT     ORDER BY candidate.ord
```

Four CTEs where the plan said three, and the split turned out to be an improvement rather than a
concession: because `locked` selects the rank as a column, both sorts read that column instead of
recomputing the `CASE`. The rank is evaluated once and the flag is bound once, where the original shape
would have interpolated the expression into two positions that had to be kept in step.

This is also the reason ADR-029's "one `ORDER BY`" is worded as one *sort term*: the term appears in two
syntactic positions, but as a column reference rather than as a duplicated expression.

## ADR-029: The flag is a term inside the single `ORDER BY`, not a second query

**Status**: Accepted.

**Context**: FR-206 requires flag gating with the flag off indistinguishable from B-01.

**Options**:

1. **Two SQL strings**, chosen by the flag.
2. **One query whose leading sort term collapses to a constant when the flag is off**:
   `ORDER BY CASE WHEN :severityOrder THEN <rank> ELSE 0 END DESC, d.state_changed_at`.

**Decision**: option 2.

**Reasoning**: this repeats a choice spec 002 already made for the reclaim flag (T047), and for the
same reason: two SQL strings drift. A fix applied to the enabled path and forgotten on the disabled one
is invisible, because the disabled path is the one nobody looks at, and the disabled path is the
**rollback**. Keeping one query means the flag cannot change anything except the sort term it names.

With the flag off, the leading term is the constant `0` for every row and the order falls through to
`d.state_changed_at` — B-01 exactly, which is what SC-204 asserts.

**Consequence**: the flag is bound **once**. Because the split in ADR-028 makes `locked` select the rank
as a column, both sorts reference that column and the expression exists in exactly one position — better
than the plan's version, which interpolated it twice and relied on a shared constant to keep the two in
step.

## ADR-030: Batch size becomes configuration

**Status**: Accepted.

**Context**: `DeliveryWorker.BATCH` is the constant `50`. Severity ordering is only observable when the
backlog exceeds one batch (FR-201), so SC-201 needs a batch size of one.

**Options**:

1. **Leave the constant and enqueue 51+ deliveries per test.** Slow, and it makes the assertion about
   the batch boundary rather than about severity.
2. **Make it a configuration property**, default 50.

**Decision**: option 2, `notification.worker.batch-size`, default `50`.

**Reasoning**: the default is unchanged, so no deployment behaviour moves. A test that has to build a
51-delivery backlog to observe an ordering rule is testing the batch size by accident, and would be the
kind of slow test that gets deleted later.

**Consequence**: one new property with an unchanged default. Not flag-gated — a default-preserving
configuration knob is not a behaviour change.

## ADR-031: Claim-ordering tests assert on the notification, not on a delivery id

**Status**: Accepted, during implementation.

**Context**: the first version of the test fixture assumed one notification produces one delivery, and
resolved a delivery id with `.single()`. It failed: the routing policy sets `escalateAtSeverity: CRITICAL`
on SMS (B-02), so a `CRITICAL` submission produces an EMAIL delivery **and** an escalated SMS one even
though only EMAIL was requested.

**Options**:

1. **Suppress the escalation in the fixture**, by testing at severities below `CRITICAL` or by loading a
   test-only routing policy.
2. **Name the EMAIL delivery specifically** and assert on that.
3. **Assert on the notification** a claimed delivery belongs to.

**Decision**: option 3.

**Reasoning**: option 1 would have removed `CRITICAL` from the tests of a feature whose entire purpose is
that `CRITICAL` goes first — the most important row of the truth table, dropped to make the fixture
simpler. Option 2 is subtly wrong: it asserts *which channel* the claim returned first, and FR-201 says
nothing about that. Two deliveries of one notification tie on severity and on `state_changed_at`, so
their relative order is whatever the plan produced. A test asserting it would be asserting the query
plan.

Severity is a property of the notification, so the notification is the right granularity for a severity
assertion.

**Consequence**: `SeverityClaimOrderSupport` exposes `claimNotifications(int)` alongside `claim(int)`, and
`deliveriesOf` returns a set rather than a single id. The escalation is documented in the fixture rather
than worked around, so the next person meeting two deliveries where they expected one finds the reason
instead of the surprise.

**Related finding**: the same fixture initially asserted an unfiltered count from a claim of 50, which
takes whatever else the shared Testcontainers instance has due. It passed alone and failed in the suite.
Claim-wide assertions cannot be isolated on a shared database, so `ClaimEligibilityUnchangedTest` filters
to its own submissions.

## Rejected outright

- **A starvation cap.** FR-207 forbids it. Decision D-15 accepted unbounded starvation, and adding an
  age override would silently reverse an owner decision.
- **Ordering by `priority`.** FR-208 keeps `priority` behaviour-free. Using it here would resolve
  phase-1 G-15 by accident, in a feature that is not about it.
- **Preemption of an in-flight attempt.** Out of scope by the spec. Claim order governs what is picked
  up next, never what is already running.
- **A starvation metric.** G-64 records the observability hazard and accepts it. Adding a gauge is the
  right fix and is more than a one-rule feature should carry.
