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
| Harness | `./gradlew perfTest` — `src/test/java/com/notification/performance/EnhancementCostTest.java` |
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

## Reproducing

```bash
./gradlew perfTest
```

The task is deliberately outside `check`. A timing measurement inside a merge gate fails for reasons
unrelated to correctness — it measures the machine — and Principle VI does not permit a gate that can
go red without a defect. The harness asserts nothing at all, for the reason stated at the top.
