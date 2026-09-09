# Phase 1 Data Model: Delta against the phase-1 model

**Date**: 2026-09-09 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

**This document is a delta.** The phase-1 model
([001 data-model.md](../001-notification-management-core/data-model.md)) remains authoritative for
everything not listed here. Restating it would obscure what this phase actually changes, which is
the one thing a brownfield reviewer needs to see.

---

## Summary of changes

| Object | Change | Requirement |
|--------|--------|-------------|
| `Channel` enum | `+ PUSH` | FR-110, FR-111 |
| `delivery` | New legal transition `IN_PROGRESS → QUEUED`; reclaim index | FR-159 |
| `ChannelProviderPort` | `+ idempotency key` parameter | FR-161, ADR-019 |
| `notification_suppression` | **NEW table** | FR-140, FR-143 |
| `audit_event` | 3 new event types | FR-143, FR-150 |
| `RetryPolicy` | Selected per channel rather than globally | FR-132 |
| **Unchanged** | `notification`, `notification_content`, `recipient`, `routing_decision`, `routing_channel_outcome`, `delivery_attempt`, `outbox` | — |

`recipient` is explicitly unchanged: still `id`, `notification_id`, `recipient_ref` and nothing
else. Push needs a device token and none is supplied (G-49); adding a column here would invent the
destination data both source documents omit.

---

## 1. Channel — `+ PUSH`

Two-value closed enum becomes three. **No structural change**: the column is `text`, every channel
flows through the same tables, and no table gains a channel-specific column.

**Contract consequence**: the published `Channel` enum gains a value. Additive within a major
version per Principle I, but recorded as **G-46** — a *strict* consumer of a closed enum can break
on an unrecognised value even though the change is additive by the constitution's definition.

---

## 2. `delivery` — reclaim of stranded rows

**The defect** (B-13): `claimDue` claims only `QUEUED` and `RETRY_SCHEDULED`. A worker or provider
crash after the move to `IN_PROGRESS` leaves the row unclaimable, and nothing sweeps stale leases.
The delivery never terminates, and rollup rule 2b then reports the notification as `IN_PROGRESS`
indefinitely — status lying about work that will never complete.

**New legal transition**, declared in the transition table like any other, not as an exception:

| From | To | Trigger |
|------|-----|--------|
| `IN_PROGRESS` | `QUEUED` | lease expired; the worker that held it is gone (FR-159) |

**Invariants on reclaim**

- A row is reclaimable only when `claimed_until` has passed. `claimed_until` **must exceed the
  provider read timeout**, or a merely slow call is reclaimed while still in flight — see the risk
  note below.
- `attempt_count` is **not** reset. A reclaimed delivery consumes its budget like any other attempt
  (FR-159a); otherwise a repeatedly crashing provider produces unbounded attempts.
- Expiry outranks reclaim (FR-159b). A stranded delivery whose notification has since expired
  reaches `EXPIRED` rather than being re-attempted — consistent with FR-034.

**Migration** `V6__delivery_reclaim.sql`: extends the claimable-state index to cover `IN_PROGRESS`
rows with an expired lease. No column change; no data migration; forward-only.

> **Risk this creates, deliberately accepted** (G-58): reclaiming `IN_PROGRESS` means a delivery
> whose provider call is merely *slow* — longer than the lease — can be reclaimed while still in
> flight. That trades a silent stall for a possible duplicate call, which is exactly why the
> idempotency key below is not optional and why the two must ship together. The lease must also be
> configured above the provider read timeout.

---

## 3. `ChannelProviderPort` — the D12 agreement in the signature

```
send(RecipientRef recipient, ContentRef content, IdempotencyKey key) → DeliveryOutcome
```

**Key derivation** (ADR-019): from `(notification id, recipient id, channel, attempt number)`.

All four are written **before** the provider call — the delivery row at acceptance, the attempt row
at the start of the attempt — so the key is reproducible after a crash that recorded nothing
further. A *stored* key would need writing immediately before the call, which is the precise window
the key exists to survive.

**Responsibility split** (spec D12), stated here because the data model is where it becomes real:

| Obligation | Owner |
|---|---|
| Produce a key stable across re-attempts | this service |
| Recognise the key; do not process the same send twice | **the provider** |

The parameter is on the port rather than passed informally, so a new adapter cannot silently omit
it. This service supplies the key and **assumes** the provider honours it (FR-162, tagged `[A]`).
Against a provider that ignores it, a reclaimed delivery can duplicate and this service cannot
detect it — G-59, and a DO-004 limitation beside G-22.

---

## 4. `notification_suppression` — NEW

Records a submission suppressed as a duplicate. Separate from `notification` because a suppressed
submission **does not become a notification** — the same reasoning as phase-1 FR-007, where a
rejection creates no notification row.

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `uuid` | PK | — |
| `source_system` | `text` | NOT NULL | FR-141 |
| `correlation_id` | `text` | NOT NULL | FR-141 |
| `suppressed_at` | `timestamptz` | NOT NULL | FR-143 |
| `original_notification_id` | `uuid` | NOT NULL, FK → `notification` | FR-144 |
| `client_notification_id` | `text` | NOT NULL | FR-144 |

**Boundary query** (FR-141, FR-141b, FR-141c) — a submission is a duplicate when a notification
exists with the same `(source_system, correlation_id)`, received within the window (**24 hours by
default, configurable** — D14), **and not in a terminally unsuccessful state**. The last clause is FR-141c: suppressing after a permanent failure
would convert a delivery problem into unrecoverable data loss.

**Index**: `(source_system, correlation_id, received_at)` on `notification`. Note this is an index,
**not a unique constraint** — the deliberate absence documented in phase-1 `V1__initial_schema.sql`
stands. Uniqueness is a caller contract (G-55), not a database guarantee, and enforcing it here
would reject rather than suppress, which is different behaviour.

**Transactional placement**: the boundary check runs **inside the acceptance transaction**
(Principle II). A suppression decided outside it could suppress and then fail to record.

**Migration** `V5__deduplication.sql`: creates the table and index. Forward-only. Inert while the
flag is off, since nothing queries it.

---

## 5. `audit_event` — three new types

| Event type | When | Trace |
|---|---|---|
| `NOTIFICATION_SUPPRESSED` | a submission is suppressed as a duplicate | FR-143 |
| `RETRY_EXECUTED` | a scheduled retry actually runs | FR-150 |
| `DELIVERY_RECLAIMED` | a stranded delivery is recovered | FR-159 |

Thirteen types total. All use the sealed allowlist, so none can carry the content payload.

**`RETRY_EXECUTED` carries the scheduling reference** it followed (FR-150a). Today both records
exist but nothing pairs them; with several retries, a reader cannot tell which execution followed
which scheduling.

**Privacy check, done rather than assumed**: a suppression record names the deduplication key,
which contains the caller-supplied correlation identifier. That identifier is *already* stored
unmasked in `audit_event.correlation_id` — it is the audit's own index (phase-1 FR-048). So this
adds no new exposure. The recipient reference stays masked as before.

---

## 6. `RetryPolicy` — per channel

Currently one bean. Becomes a per-channel selection with a default, so push can back off more
patiently than email (FR-132, ADR-018 — the substantive part of "handling" rate limiting).

**Invariant**: every per-channel policy is still bounded. A provider may have a different schedule;
it may not opt out of the bound (FR-132 `[C]`). `RetryPolicy`'s existing constructor guard —
`maxAttempts >= 1` — already enforces this per instance.

No schema change: retry parameters are configuration, not data.

---

## What deliberately does not change

| Not changed | Why |
|---|---|
| `recipient` gains no address or token column | G-49: push needs a device token and no source supplies one. Adding a column would invent the destination data both documents omit — the same error as the phase-1 recipient-address assumption |
| `RoutingRequest` stays three-factor | G-26 stands. `RoutingPreferenceIndependenceTest` must still pass; a fourth factor would erode the deferral |
| No unique constraint on `client_notification_id` | Phase-1 D4's deliberate absence stands. Deduplication is keyed on `(source, correlation)`, not on this field |
| `notification_content` unchanged | G-25 unchanged: retention after terminal state is still undecided |
| No new datastore | Principle VII, YAGNI |

## Open items affecting this model

- **U-7** — the G-55 mitigation, which decides whether identifier reuse is rejected, warned, or
  silently suppressed.
- **U-8** — how a suppressed submission appears in the response, which is a contract change either
  way.
