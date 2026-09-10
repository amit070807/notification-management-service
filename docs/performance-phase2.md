# Performance impact of the phase-2 enhancements

## What this document does and does not do

It reports measurements. It renders no verdict.

No source document for either phase states a performance threshold — not a latency target, not a
throughput floor, not a regression budget. Inventing one here and then declaring the enhancements to
have passed it would manufacture a requirement and dress the manufacture up as evidence. Spec gap
G-40 records the absence; this document is the response to it.

A reader who has a threshold in mind can apply it to the numbers below. That is the reader's
judgement to make, and it needs the numbers, not a conclusion.

## Environment

| | |
|---|---|
| Machine | Apple M1 Pro, macOS 26.6.2 |
| JDK | OpenJDK 21.0.12.1 LTS |
| Database | `postgres:16-alpine`, Testcontainers, same host |
| Harness | `./gradlew perfTest` — `EnhancementCostTest` (phase 2) and `ClaimOrderCostTest` (feature 003), both under `src/test/java/com/notification/performance/` |
| Rows in `delivery` at measurement time | 880 |

A single developer machine with the database in a local container. Latency here is dominated by
process-local round trips that a real deployment would not have, and a real deployment has network
latency this does not. The figures are useful for comparing paths against each other on one machine.
They are not a prediction of production latency, and nothing below should be read as one.

## The measurement that carries signal

The three enhancements add exactly two queries to the hot paths: one indexed lookup per submission
for the deduplication boundary, and one additional `OR` branch in the delivery worker's claim.

Measured in a single Spring context, 500 samples after 100 warmup iterations:

| Added query | p50 | p95 |
|---|---|---|
| Deduplication boundary lookup | 0.297 ms | 0.555 ms |
| `claimDue` predicate **with** the reclaim branch | 0.351 ms | 0.710 ms |
| `claimDue` predicate **without** the reclaim branch | 0.340 ms | 0.653 ms |

Reading these:

- **Deduplication costs one indexed read per submission**, ~0.3 ms at p50 on this machine. The index
  `notification (source_system, correlation_id, received_at)` from `V5__deduplication.sql` is what
  keeps it a read of one row rather than a scan; without it this figure would grow with table size.
- **The reclaim branch costs 0.011 ms at p50** — a 3% difference on a sub-millisecond query, which is
  within the run-to-run variance of the measurement itself. The honest statement is that the added
  branch has no cost this harness can resolve.
- **The idempotency key costs no query at all.** It is a SHA-256 over four values already in memory
  (ADR-019), computed once per attempt. It appears in no table and requires no read, which is why
  there is no row for it here.

## The measurement that does not carry signal, and why it is reported anyway

End-to-end wall time per submission and per delivery attempt, 200 samples after 20 warmup, measured
in two separate Spring contexts because a feature flag is fixed per context:

| Run | Flags off, p50 | Flags on, p50 |
|---|---|---|
| Submission, first run | 10.06 ms | 17.18 ms |
| Submission, second run | 13.03 ms | 8.52 ms |
| Delivery attempt, first run | 9.79 ms | 7.50 ms |
| Delivery attempt, second run | 5.44 ms | 5.16 ms |

**These numbers cannot be used to attribute cost to the enhancements, and the table is here to show
why rather than to be read as a result.** Three of the four comparisons make the flags-on path look
*faster*, which no code change in this feature could cause. The flags-off submission figure moved
from 10.06 ms to 13.03 ms between two runs of identical code — a 3 ms swing on a path whose measured
addition is 0.3 ms.

The cause is that the two flag states cannot share a context, so each comparison also compares two
connection pools, two JIT warmup histories, and two different table sizes (the second class runs
against the rows the first one left). The noise floor of that comparison is roughly an order of
magnitude larger than the effect being measured.

Reporting only the query-level table would have been cleaner and less honest. A reader deciding
whether to trust the 0.3 ms figure needs to know that the obvious end-to-end check does not confirm
it and cannot.

## Feature 003: the severity-ordering join

Measured `2026-09-10`, same machine and container as above, 300 samples after 50 warmup, 250 due
deliveries in the table, flag **on** in a single Spring context.

| Query | p50 | p95 |
|---|---|---|
| Claim as shipped — joins `notification`, sorts on the generated rank, locks and updates | 1.975 ms | 2.487 ms |
| Join and rank expression alone, no lock and no write | 0.487 ms | 0.649 ms |
| Bare age-only `SELECT`, no join, no lock and no write | 0.548 ms | 0.830 ms |

**Read rows 2 and 3 together; do not compare row 1 against row 3.** That comparison is invalid and the
table is arranged to make the invalidity visible rather than to hide it: row 1 takes locks and performs
an `UPDATE … RETURNING`, and rows 2 and 3 do neither. The 1.4 ms between them is the write, which
existed before this feature and is unchanged by it.

The comparison that answers G-61 is rows 2 against 3, which differ only by the join and the rank `CASE`:

- **0.487 ms with the join and rank, against 0.548 ms without either.** The join is not measurably more
  expensive than the bare scan, and on this run it measured slightly *cheaper* — which is the noise
  floor talking, not a speedup. The honest statement is that the join has no cost this harness can
  resolve at this table size.

Why that is unsurprising rather than suspicious: `delivery.notification_id` is a foreign key and the
join is a primary-key lookup per candidate row, which is among the cheapest joins available. And
baseline fact **B-06** matters here — `idx_delivery_claimable (state, next_attempt_at)` does not cover
the `state_changed_at` sort either, so this feature inherits an already-unindexed sort rather than
introducing one. Both queries pay that same sort.

**No threshold is asserted**, for the reason stated at the top of this document and recorded twice in
the spec: G-61 says the join-versus-denormalise choice could not be made on measured grounds because no
source document states a target. Measuring afterwards does not retroactively create one. ADR-026 chose
the join on scope and single-source-of-truth grounds, and these numbers neither vindicate nor undermine
that — they establish that the decision was not paid for in latency at this scale.

**What is not measured**: behaviour at a table size where the unindexed sort dominates. Nothing here
says how either query behaves with a million due deliveries, and no source document states a volume to
test against (phase-1 G-11).

### Reproducing

```bash
./gradlew perfTest --tests 'com.notification.performance.ClaimOrderCostTest'
```

Both queries are issued **in the same Spring context against the same rows**, deliberately. The
end-to-end table above shows what happens when a comparison spans two contexts: it produced the
impossible result that enabling a feature made it faster. Measuring this feature differently was the
lesson that table taught.

## Cost that is not latency

| Enhancement | Ongoing cost |
|---|---|
| Deduplication | One row in `notification_suppression` per suppressed submission, and one index on `notification`. The index is maintained on every insert, so submissions pay for it whether or not the flag is on. |
| Deduplication | **Negative** delivery cost when it fires: a suppressed submission creates no notification, no routing decision, and no deliveries. Enabling it reduces total work for any caller that actually resubmits. |
| Reclaim | One partial index on `delivery (claimed_until) WHERE state = 'IN_PROGRESS'`, from `V6__delivery_reclaim.sql`. Partial, so it indexes only rows mid-attempt — a small fraction of the table. |
| Reclaim | Recovers work that was previously lost outright (B-13). The attempts it costs are attempts that should have happened. |
| Idempotency key | One SHA-256 per attempt over four short values. No storage, no query. |
| Push channel | One more delivery row per recipient when the routing policy selects it, identical in cost to an existing channel. |
| Retry audit | One additional audit row per retry execution. |
| Severity claim order | One primary-key join per candidate row in the claim, plus a `CASE` over four branches. No measurable latency cost at 250 due deliveries; no index added, and none removed. |
| Severity claim order | **Negative** cost in one direction and unbounded in another: a `CRITICAL` notification is delivered sooner, and a `LOW` one may never be delivered at all. That is decision D-15, not a performance property, and no measurement expresses it. |

## Reproducing

```bash
./gradlew perfTest
```

The task is deliberately outside `check`. A timing measurement inside a merge gate fails for reasons
unrelated to correctness — it measures the machine — and Principle VI does not permit a gate that can
go red without a defect. The harness asserts nothing at all, for the reason stated at the top.
