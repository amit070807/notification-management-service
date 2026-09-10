# Data Model: Severity-Ordered Delivery Claim

**Phase 1** · Spec: [spec.md](./spec.md) · Plan: [plan.md](./plan.md)

**No schema change.** No table, column, index or migration. This document describes a rank, an
ordering contract, and the query that applies them — nothing that is stored.

---

## 1. Severity rank

The single declaration required by FR-202. Higher rank is claimed first.

| Constant | Rank | Stored `text` value |
|---|---|---|
| `CRITICAL` | 4 | `CRITICAL` |
| `HIGH` | 3 | `HIGH` |
| `MEDIUM` | 2 | `MEDIUM` |
| `LOW` | 1 | `LOW` |

Rules the implementation must satisfy:

- **Total.** Every constant has a rank. Enforced by the constructor, not by a lookup that could be
  incomplete (ADR-025).
- **Distinct.** No two constants share a rank, so the ordering has no accidental ties. Asserted, because
  a duplicated rank would make ordering depend on `state_changed_at` for two severities that are
  supposed to be distinguishable, and every "CRITICAL first" test would still pass.
- **Not `ordinal()`.** FR-202 forbids it. The declaration order happens to agree today, which is exactly
  what makes it dangerous: a reordering for readability would silently change delivery order.
- **Not lexicographic.** B-03: the stored order is `CRITICAL < HIGH < LOW < MEDIUM`. See §4.

Rank values are contiguous ascending from 1, but nothing depends on that. Only the relative order is a
requirement; the specific integers are an implementation detail, and no test asserts them as such.

## 2. Ordering contract

The order in which eligible deliveries are claimed and processed:

```text
1. severity rank         DESC     (only when notification.features.severity-claim-order = true)
2. delivery.state_changed_at ASC  (always — the B-01 order)
```

| Requirement | How this satisfies it |
|---|---|
| FR-201 | Rank leads the sort, so higher severity precedes lower when the backlog exceeds one batch |
| FR-203 | `state_changed_at` remains the second key, so equal severity keeps oldest-first. Severity **refines** the order rather than replacing it |
| FR-204 | The sort keys are the only change. The eligibility `WHERE` clause is copied unchanged, so the *set* of claimable deliveries is identical |
| FR-206 | With the flag off, key 1 evaluates to the constant `0` for every row and the order falls through to key 2 — B-01 exactly |
| FR-207b / D-16 | The sort applies to the whole eligible set, including the reclaim branch of the predicate. A reclaimed delivery is ordered, not privileged |

**The ordering is total** given a row set, because rank is distinct per severity and
`(severity, state_changed_at, id)` is unique enough in practice; ties on both keys fall back to whatever
the plan produces and no requirement distinguishes them. Tests must therefore assert on delivery
identity, never on "the second row", when two deliveries could tie.

### What D-16 makes true, stated so it is not mistaken for a defect

A reclaimed `LOW` delivery whose worker crashed can be claimed **after** a freshly-queued `CRITICAL`
one. Recovery is not expedited. Within equal severity a reclaimed delivery still sorts early — its
`state_changed_at` is old — so FR-203 gives it natural precedence without a rule of its own.

## 3. Entities

No entity changes. For reference, the two the query touches:

| Entity | Field used | Role |
|---|---|---|
| `delivery` | `state_changed_at` | Second sort key. Already indexed? **No** — B-06: `idx_delivery_claimable (state, next_attempt_at)` does not cover it, so this feature inherits an unindexed sort |
| `delivery` | `notification_id` | `NOT NULL`, foreign key. The join key |
| `notification` | `severity` (`text`) | First sort key, via the generated rank expression |

`Delivery` gains **no** severity field. ADR-028 keeps the order in SQL via `ROW_NUMBER()` precisely so
the domain record does not have to carry a value it only needs to re-derive an order the database
already computed.

## 4. Why the rank cannot live in the SQL

`notification.severity` is `text` (B-03). Ordering on it directly gives:

| SQL | Resulting order | Correct? |
|---|---|---|
| `ORDER BY n.severity ASC` | `CRITICAL, HIGH, LOW, MEDIUM` | First two right, last two **inverted** |
| `ORDER BY n.severity DESC` | `MEDIUM, LOW, HIGH, CRITICAL` | Near-inverse of intended |

This is gap **G-60**, and it is the trap this feature is most likely to ship: `ASC` puts `CRITICAL`
first, so a test that only checks "the CRITICAL one came first" passes against a broken implementation
that inverts `MEDIUM` and `LOW`.

The fix is a `CASE` mapping the stored text to the rank — **generated from the enum** (ADR-027), not
hand-written, so it cannot disagree with §1:

```sql
CASE n.severity
    WHEN 'CRITICAL' THEN 4
    WHEN 'HIGH'     THEN 3
    WHEN 'MEDIUM'   THEN 2
    WHEN 'LOW'      THEN 1
    ELSE 0
END
```

`ELSE 0` is a fail-safe, not dead code: a severity string in the table that no enum constant matches
sorts **last** rather than sorting as `NULL`, whose position under `DESC` would depend on
`NULLS FIRST/LAST` defaults. It should be unreachable, since the column is written from the enum.

Interpolating these strings into SQL is safe for a reason that must not be assumed: they are enum
constant names from a closed compile-time set, never caller input. A test asserts the generated fragment
names every constant, so a partial generation fails a test instead of silently ranking a severity `0`.

## 5. Query shape

```text
WITH candidate AS (
    SELECT d.id,
           ROW_NUMBER() OVER (ORDER BY <rank term> DESC, d.state_changed_at) AS ord
    FROM delivery d
    JOIN notification n ON n.id = d.notification_id
    WHERE <B-01 eligibility predicate, verbatim — including the reclaim branch>
    ORDER BY <rank term> DESC, d.state_changed_at
    LIMIT :batchSize
    FOR UPDATE OF d SKIP LOCKED
),
claimed AS (
    UPDATE delivery d SET claimed_until = :leaseUntil
    FROM candidate WHERE d.id = candidate.id
    RETURNING d.*, (SELECT recipient_ref FROM recipient WHERE id = d.recipient_id) AS recipient_ref
)
SELECT claimed.* FROM claimed
JOIN candidate ON candidate.id = claimed.id
ORDER BY candidate.ord
```

where `<rank term>` is `CASE WHEN :severityOrderEnabled THEN <generated CASE> ELSE 0 END`.

Three invariants, each with a failure mode worth naming:

| Invariant | If broken |
|---|---|
| **`FOR UPDATE OF d`**, never bare `FOR UPDATE` | A bare lock would also lock the joined `notification` row, contending with the acceptance transaction. This is the one new locking hazard the join introduces, and it is invisible until two workers meet a submitter |
| The `WHERE` clause is byte-identical to B-01 | FR-204 broken: eligibility changes. Ordering tests would still pass while some delivery silently stopped being claimable |
| The final `ORDER BY candidate.ord` is present | FR-205 broken: selection order is right, processing order is arbitrary. Passes today, breaks on a plan change (B-05) |

## 6. Configuration

| Property | Default | Purpose |
|---|---|---|
| `notification.features.severity-claim-order` | `false` | FR-206. The only mitigation for the starvation D-15 accepted (FR-207a) |
| `notification.worker.batch-size` | `50` | ADR-030. Unchanged default; makes SC-201 assertable without a 51-row backlog |

Neither changes behaviour at its default. The flag is off, and the batch size is the constant it
replaces.
