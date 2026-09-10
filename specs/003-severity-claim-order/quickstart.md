# Quickstart & Validation: Severity-Ordered Delivery Claim

**Phase 1** · Spec: [spec.md](./spec.md) · Plan: [plan.md](./plan.md) ·
Data model: [data-model.md](./data-model.md)

**This is a delta.** Setup is unchanged — see the [README](../../README.md). Docker Engine 25+ is
required (the build negotiates API 1.44).

---

## Scenario 0 — the regression check, run first

**SC-204, FR-206.** With the flag off, the claim must be indistinguishable from the phase-2 baseline.

```bash
./gradlew test
```

Expect the full existing suite to pass **unmodified**. This feature changes a query every delivery
passes through, so a silent eligibility change is the failure that matters most and is the one least
likely to announce itself. Run it before anything else.

No existing test should need editing. If one does, that is a finding to report, not a licence to edit
it — unlike spec 002, this feature reverses no previous behaviour, so there is no justified change.

## Scenario 1 — a younger CRITICAL is claimed before an older LOW

**SC-201, FR-201.** The feature, in one assertion.

Enable the flag and set the batch size to one:

```yaml
notification:
  features:
    severity-claim-order: true
  worker:
    batch-size: 1
```

Enqueue a `LOW` delivery, advance the clock, enqueue a `CRITICAL` one, then run the worker once.

Expect the **`CRITICAL`** delivery attempted, despite being younger. Batch size one is what makes the
ordering observable: with the default 50 both would be claimed together and the assertion would be about
processing order instead (scenario 4).

## Scenario 2 — the pairwise truth table

**SC-202, G-60.** The guard against the trap this feature is most likely to ship.

```bash
./gradlew test --tests 'com.notification.unit.SeverityRankTest'
```

Expect **all four severities compared pairwise** — six ordered pairs, not just `CRITICAL` against `LOW`.

Why this scenario exists at all: severity is stored as `text`, so `ORDER BY severity ASC` yields
`CRITICAL, HIGH, LOW, MEDIUM`. The first two are right and the last two are **inverted**. An
implementation that simply sorted on the stored string would pass scenario 1, pass any test phrased as
"the critical one goes first", and quietly deliver `LOW` before `MEDIUM` forever.

Also assert the rank is **distinct** per constant. A duplicated rank makes two severities tie and fall
through to age, and every extremes-only test still passes.

## Scenario 3 — equal severity keeps oldest-first

**SC-203, FR-203.** Severity refines the existing order; it does not replace it.

Enqueue two `HIGH` deliveries at different clock times, batch size one, run the worker.

Expect the **older** one attempted. If severity had replaced the age order rather than leading it, this
would be arbitrary.

## Scenario 4 — the worker processes in the claimed order

**SC-205, FR-205, B-05.** A separate scenario from 1 on purpose.

With the default batch size, enqueue `LOW`, `CRITICAL`, `MEDIUM`, `HIGH` and run the worker once so all
four are claimed in a single batch.

Expect them **attempted** in `CRITICAL, HIGH, MEDIUM, LOW` order — asserted from what the worker
observed, not from what the query selected.

Why this is not covered by scenario 1: the claim is `UPDATE … FROM candidate … RETURNING`, and SQL does
not guarantee `RETURNING` preserves the CTE's `ORDER BY`. Trusting it would pass today and break on a
plan change — a regression with no code change to blame it on.

## Scenario 5 — reclaimed deliveries are ordered, not privileged

**SC-206, FR-207b, D-16.**

Strand a `LOW` delivery by crashing its attempt (`ScriptedChannelProvider.thenThrow()`), advance past
the lease, then enqueue a fresh `CRITICAL` delivery. Batch size one, run the worker.

Expect the **`CRITICAL`** delivery claimed first. The reclaimed `LOW` one waits.

This is the owner's decision D-16 and it has a real cost: **recovery is not expedited.** A crashed
low-severity delivery can sit behind new incident traffic. It is recorded here so a reader meeting this
behaviour in production recognises a decision rather than a bug.

Note what the reclaim fixture makes true: a stranded delivery **has already had one provider call**. Any
assertion that no call was made is asserting the fixture, not the behaviour.

## Scenario 6 — starvation is intended, and the flag is its only mitigation

**SC-207, FR-207, FR-207a, D-15.**

Keep a continuous stream of `CRITICAL` deliveries due, with one `LOW` delivery also due, batch size one.
Run the worker repeatedly.

Expect the `LOW` delivery to remain **unclaimed indefinitely** — and expect that to be a *passing*
assertion. Then turn the flag off and run the worker: the `LOW` delivery is claimed.

Asserting starvation as correct behaviour looks perverse and is the point. Decision D-15 accepted
unbounded starvation, so a future change that adds an age-based override would be reversing an owner
decision. This test is what makes that reversal fail loudly instead of looking like a bug fix.

**Operator note, required by FR-207**: an aged `LOW` delivery sitting unclaimed while higher-severity
work flows is **correct**. It is not a stalled worker. Gap **G-64** records that the system gives no
signal distinguishing the two — claim order is invisible in the API (G-63) and no starvation metric is in
scope. If a `LOW` backlog must drain, turn `severity-claim-order` off; that is the documented and only
mitigation.

## Scenario 7 — eligibility is unchanged

**FR-204.** The join must reorder without filtering.

Claim the same backlog twice, flag on and flag off, and compare the **sets** of claimed delivery ids.

Expect identical sets in a different order. `delivery.notification_id` is `NOT NULL` with a foreign key,
so an inner join cannot drop a row — but a join that silently narrowed the result would break FR-204
while every ordering test above still passed, so it is asserted rather than reasoned about.

## Scenario 8 — performance impact is measured

**Spec 002 FR-107, G-61.**

```bash
./gradlew perfTest
```

The join added by ADR-026 is measured and recorded. **No threshold is asserted** — no source document
states one (phase-1 G-11, G-61), and inventing a verdict would invent a requirement.

B-06 is worth recalling when reading the number: the existing `state_changed_at` sort is already
unindexed, so this feature inherits an unindexed sort rather than introducing one.

---

## Running everything

```bash
./gradlew clean check archTest privacyTest
```

Coverage floors are unchanged and must not fall: 90% domain, 80% overall. The new domain code is a rank
and a string generator, so the domain floor is what thin tests would break first.

