# Phase 1 Data Model: Notification Management Core

**Date**: 2026-09-07 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Every entity, field and rule below traces to a requirement or to a recorded decision. Storage is
PostgreSQL 16 accessed via `JdbcClient` (ADR-004); all timestamps are `timestamptz` in UTC per the
constitution's Data Model Invariants.

## Entity Overview

```text
Notification (aggregate root, server-issued id)
├── Recipient            1..*        opaque reference only (FR-005, D5)
├── RoutingDecision      1           immutable (FR-021, FR-022)
│   └── RoutingChannelOutcome  1..*  selected/excluded + reason
├── Delivery             0..*        one per (recipient, channel) (FR-014)
│   └── DeliveryAttempt  0..*        one per try (FR-043)
├── AuditEvent           1..*        append-only (FR-046, FR-050)
└── OutboxEntry          0..*        transactional handoff (Principle II)
```

## Tables

### `notification`

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `uuid` | **PK**, server-issued | FR-008, D4 |
| `client_notification_id` | `text` | NOT NULL, **not unique**, indexed | FR-002, FR-008a, D4 |
| `source_system` | `text` | NOT NULL | FR-002 |
| `correlation_id` | `text` | NOT NULL, indexed | FR-002, FR-048 |
| `notification_type` | `text` | NOT NULL, closed set | FR-002, FR-009 |
| `severity` | `text` | NOT NULL, closed set | FR-002, FR-009 |
| `priority` | `text` | NOT NULL, closed set | FR-002, FR-009, G-15 |
| `content_ref` | `uuid` | NOT NULL, FK → `notification_content` | FR-055, FR-056 |
| `created_at_client` | `timestamptz` | NOT NULL, as supplied | FR-002, G-21 |
| `received_at` | `timestamptz` | NOT NULL, system clock | G-21 |
| `not_before_at` | `timestamptz` | NULL allowed | FR-003a, D3 |
| `expires_at` | `timestamptz` | NULL allowed | FR-003b, D3 |
| `state` | `text` | NOT NULL, `NotificationState` | FR-016, FR-017 |
| `state_changed_at` | `timestamptz` | NOT NULL | FR-013 |

**Invariants**
- `client_notification_id` carries **no** unique constraint — duplicates are accepted as
  independent notifications (D4, FR-008b). This is the one place where an obvious constraint is
  deliberately absent; a comment in the migration must say so, or a future maintainer will "fix"
  it and silently implement deduplication.
- `CHECK (not_before_at IS NULL OR expires_at IS NULL OR not_before_at < expires_at)` — FR-003d.
- `expires_at` in the past at insert is rejected at the API boundary, not by the database
  (FR-003e), because rejection must name the offending field.
- `priority` is stored and returned but drives no behavior (G-15).

### `notification_content`

Separated from `notification` so that the sensitive payload has its own table, its own grant, and
can be purged independently if G-25 is ever resolved toward minimisation.

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `uuid` | PK | FR-055 |
| `payload` | `bytea` | NOT NULL, opaque, never parsed | FR-055 |
| `payload_size_bytes` | `int` | NOT NULL, bounded at the boundary | FR-059 |
| `payload_ref` | `text` | NOT NULL, non-reversible derived reference | FR-051 |

**Invariants**
- Never joined into any status query (FR-056) and never referenced by an audit payload except
  through `payload_ref` (FR-051).
- No column here may appear in a log, metric label, or error message (Principle V).

### `recipient`

A recipient is an **opaque reference and nothing else** (spec D5). The source defines no
recipient field, so none is stored. There is deliberately no address table.

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `uuid` | PK | — |
| `notification_id` | `uuid` | NOT NULL, FK | FR-005 |
| `recipient_ref` | `text` | NOT NULL — opaque, uninterpreted | D5, G-17 |

**Invariants**
- The service stores no contact value, endpoint, token, or per-channel address, because the
  source supplies none (D5). A migration adding such a column is a scope change, not a
  refinement, and should be challenged in review.
- `recipient_ref` is assumed to carry personal data: masked in audit records, logs and metric
  labels (FR-052); echoed unmasked only in status responses, to the system that supplied it.
- **G-32 is visible here**: attempting delivery needs a destination and this table holds none.
  The reference is handed to the channel provider as-is. How a real provider would resolve it is
  undefined by the source and is not invented; the prototype's providers are simulated (G-22).

### `routing_decision` and `routing_channel_outcome`

| `routing_decision` | Type | Constraints | Trace |
|--------------------|------|-------------|-------|
| `id` | `uuid` | PK | FR-021 |
| `notification_id` | `uuid` | NOT NULL, FK, UNIQUE | FR-021 |
| `policy_version` | `text` | NOT NULL | FR-026 |
| `decided_at` | `timestamptz` | NOT NULL | FR-013 |

| `routing_channel_outcome` | Type | Constraints | Trace |
|---------------------------|------|-------------|-------|
| `routing_decision_id` | `uuid` | NOT NULL, FK | FR-022 |
| `recipient_id` | `uuid` | NOT NULL, FK | FR-022 |
| `channel` | `text` | NOT NULL | FR-022 |
| `selected` | `boolean` | NOT NULL | FR-022 |
| `reason_code` | `text` | NOT NULL, closed set | FR-022 |

**Invariants — immutable.** No UPDATE or DELETE path exists on either table (FR-021, FR-023). A
later policy change cannot alter a recorded decision. `reason_code` values are
`REQUESTED_AND_ALLOWED`, `NOT_REQUESTED`, `POLICY_EXCLUDED`, `SEVERITY_ESCALATED`,
`CHANNEL_DISABLED`. There is deliberately no address-related reason code: after D5 the system
holds no address, so it cannot and must not route on the basis of one.

**Explicitly absent**: any column expressing a recipient preference. §4.3's fourth factor is out
of scope (G-26) and FR-019a forbids a proxy for it under another name — including a
preference-shaped `reason_code`.

### `delivery`

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `uuid` | PK | FR-014 |
| `notification_id` | `uuid` | NOT NULL, FK | FR-014 |
| `recipient_id` | `uuid` | NOT NULL, FK | FR-012 |
| `channel` | `text` | NOT NULL | FR-012 |
| `state` | `text` | NOT NULL, `DeliveryState` | FR-014, FR-017 |
| `attempt_count` | `int` | NOT NULL, DEFAULT 0, `<= max_attempts` | FR-038 |
| `next_attempt_at` | `timestamptz` | NULL when not scheduled | FR-036 |
| `last_failure_classification` | `text` | NULL, `FailureClassification` | FR-037 |
| `claimed_until` | `timestamptz` | NULL — worker lease | Principle II |
| `state_changed_at` | `timestamptz` | NOT NULL | FR-013 |

**Invariants**
- `UNIQUE (notification_id, recipient_id, channel)` — constitution Data Model Invariants, FR-014.
- A row exists only if the corresponding `routing_channel_outcome.selected = true` (FR-035).
- Per-pair state is a stored column, never derived by scanning audit or logs (FR-014).

### `delivery_attempt`

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `uuid` | PK | FR-043 |
| `delivery_id` | `uuid` | NOT NULL, FK | FR-043 |
| `attempt_number` | `int` | NOT NULL | FR-043 |
| `started_at` / `finished_at` | `timestamptz` | NOT NULL / NULL | FR-013 |
| `outcome` | `text` | NOT NULL — `SUCCESS` or `FAILURE` | FR-030 |
| `failure_classification` | `text` | NULL, `FailureClassification` | FR-037 |
| `diagnostic` | `text` | NULL, **bounded, non-content** | Principle V |

**Invariants**
- `UNIQUE (delivery_id, attempt_number)` — constitution Data Model Invariants.
- `diagnostic` must never carry a provider's raw response body, which may echo submitted content
  (plan post-design risk). Adapters truncate and sanitise; the privacy test asserts it.

### `audit_event`

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `uuid` | PK | FR-046 |
| `notification_id` | `uuid` | NOT NULL, FK, indexed | FR-048 |
| `correlation_id` | `text` | NOT NULL, indexed | FR-048 |
| `event_type` | `text` | NOT NULL, closed set | FR-046 |
| `occurred_at` | `timestamptz` | NOT NULL | FR-049 |
| `payload` | `jsonb` | NOT NULL, **typed allowlist per event type** | FR-054 |

**Event types** (10 = the 8 required by §4.9 plus the 2 added by FR-053):
`NOTIFICATION_ACCEPTED`, `NOTIFICATION_REJECTED`, `ROUTING_DECISION_MADE`, `DELIVERY_QUEUED`,
`DELIVERY_ATTEMPTED`, `DELIVERY_SUCCEEDED`, `DELIVERY_FAILED`, `RETRY_SCHEDULED`,
`DELIVERY_EXPIRED`, `RETRY_BUDGET_EXHAUSTED`.

**Invariants**
- Append-only. The repository interface exposes no update or delete method, and the application's
  DB role is granted `INSERT, SELECT` only (FR-050, Principle V).
- `payload` is serialised from a sealed set of typed records, one per event type — free-form maps
  are not accepted. The content payload is therefore structurally excluded rather than merely
  forbidden (FR-054, FR-056).
- Forbidden anywhere in this table: content payload, credentials, and unmasked recipient
  references (FR-047, FR-051, FR-052). After D5 no contact values exist to forbid.

### `outbox`

| Column | Type | Constraints | Trace |
|--------|------|-------------|-------|
| `id` | `bigserial` | PK | Principle II |
| `notification_id` | `uuid` | NOT NULL, FK | Principle II |
| `created_at` | `timestamptz` | NOT NULL | — |
| `processed_at` | `timestamptz` | NULL until drained | — |
| `claimed_until` | `timestamptz` | NULL — poller lease | Principle II |

**Invariant**: written in the same transaction as the notification it references, so the handoff
cannot lose or phantom-create work (Principle II). Claimed with `FOR UPDATE SKIP LOCKED`.

## State Machines

### `DeliveryState` — legal transitions

| From | To | Trigger |
|------|-----|--------|
| `PENDING` | `QUEUED` | outbox drained |
| `PENDING` | `UNDELIVERABLE` | routing selected no channel (FR-024) |
| `PENDING`, `QUEUED`, `RETRY_SCHEDULED` | `EXPIRED` | `expires_at` passed (FR-032, FR-034) |
| `QUEUED` | `IN_PROGRESS` | worker claimed |
| `QUEUED` | `IN_PROGRESS` blocked | `not_before_at` not reached (FR-031) |
| `IN_PROGRESS` | `DELIVERED` | attempt succeeded |
| `IN_PROGRESS` | `RETRY_SCHEDULED` | retryable failure, budget remains (FR-039, FR-040) |
| `IN_PROGRESS` | `FAILED` | non-retryable failure (FR-040) |
| `IN_PROGRESS` | `EXHAUSTED` | retryable failure, budget spent (FR-038, FR-044) |
| `IN_PROGRESS` | `EXPIRED` | expiry detected in the pre-attempt check (FR-033) |
| `RETRY_SCHEDULED` | `IN_PROGRESS` | backoff elapsed and not expired |

**Terminal**: `DELIVERED`, `FAILED`, `EXHAUSTED`, `EXPIRED`, `UNDELIVERABLE`. Any transition not
listed is illegal and throws (Principle IV).

### `NotificationState` rollup — deterministic and documented (FR-016)

Evaluated over the notification's deliveries, first matching rule wins:

1. No deliveries exist and routing selected nothing → `EXPIRED` if expired, else `ACCEPTED`.
2. Any delivery non-terminal → `IN_PROGRESS`.
3. All terminal and all `DELIVERED` → `COMPLETED`.
4. All terminal and all `EXPIRED` → `EXPIRED`.
5. All terminal, at least one `DELIVERED`, at least one not → `PARTIALLY_FAILED`.
6. All terminal, none `DELIVERED` → `FAILED`.

Rule 4 sits above 6 deliberately: a wholly expired notification is not a failure (FR-034).

### `FailureClassification` — closed, with retryability in one place (FR-039, FR-040)

| Classification | Retryable | Note |
|----------------|-----------|------|
| `TRANSIENT_PROVIDER_FAILURE` | yes | §4.5 |
| `TIMEOUT` | yes | §4.5; requires the mandated provider timeouts to exist |
| `UNKNOWN` | yes | FR-041 — fails closed, never treated as success |
| `PERMANENT_PROVIDER_REJECTION` | no | §4.5 |
| `INVALID_RECIPIENT` | no | §4.5; reported by the provider when it cannot resolve or accept the recipient reference (D5) |
| `AUTH_ERROR` | no | §4.5; additionally raises an operational signal (FR-042) |

## Validation Rules at the Boundary

Rejections name every offending field (FR-004) and are audited as `NOTIFICATION_REJECTED`
(FR-006, FR-007 — no deliveries created).

| Rule | Trace |
|------|-------|
| All FR-002 fields present | FR-004 |
| `recipients` non-empty | FR-005 |
| `type`, `severity`, `priority`, `channels` in closed sets | FR-009 |
| Content payload present and non-empty | FR-058 |
| Content payload within declared maximum size | FR-059 |
| `not_before_at < expires_at` when both present | FR-003d |
| `expires_at` not already in the past | FR-003e |

## Open items affecting this model

- **U-4** — the masking scheme applied to `recipient_ref` in audit records and logs. Narrowed by
  D5: no contact values remain to protect, only the reference itself.
- **G-32** — this model holds no destination data anywhere, because the source supplies none.
  Recorded as a limitation rather than filled in with an invented address table.
- **G-25** — whether `notification_content` is purged after a terminal state. Currently retained
  for the notification's lifetime; the separate table exists so this can change without touching
  the aggregate.
