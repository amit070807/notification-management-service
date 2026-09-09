# Quickstart & Validation Guide: Brownfield Phase 2

**Date**: 2026-09-09 | **Plan**: [plan.md](./plan.md) | **Contract delta**: [contract-delta.md](./contracts/contract-delta.md)

**This is a delta.** Setup is unchanged — see
[README](../../README.md) and the
[phase-1 quickstart](../001-notification-management-core/quickstart.md). What follows is what phase
2 adds, plus the regression check that matters more than any of it.

---

## Scenario 0 — the regression check (run this first)

**SC-101, SC-102, FR-105.** With every flag off, the system must be indistinguishable from the
phase-1 baseline.

```bash
./gradlew test          # phase-1 suite, unmodified, against the flagged-off build
```

Expect **all 196 phase-1 tests to pass unchanged**. Any modification to an existing test must be
individually justified against a requirement in spec 002 that explicitly changes that behaviour.

This runs first because it is the claim most easily broken and least likely to be noticed: an
enhancement that quietly alters existing behaviour looks fine in its own tests.

One phase-1 test **is** expected to change, and only one: `DuplicateSubmissionTest` asserts a second
submission is *not* suppressed. It encodes the behaviour US3 reverses. It must be **replaced, not
deleted** — deleting it would erase the record that behaviour changed (spec US3 scenario 8).

## Scenario 1 — push delivers end to end

**SC-103.** Enable push in `routing-policy.yaml` (ADR-017 — the policy's `enabled` flag *is* the
feature flag), restart, then submit requesting `PUSH`.

Expect a delivery created, attempted and reported per recipient and channel, exactly as for the
existing channels.

## Scenario 2 — push is inert when disabled

**FR-105, ADR-017.** With `PUSH` disabled in the policy, submit requesting it.

Expect `202`, **no push delivery**, and `channelOutcomes` showing `CHANNEL_DISABLED`. The flag being
off is *explained* in the routing decision rather than merely absent — which is why no separate
boolean was introduced.

## Scenario 3 — existing channels are untouched

**SC-104, FR-112.** Submit an email-and-SMS notification identical to one from phase 1 and compare
against the baseline: same states, same audit event sequence, same status shape.

The point is not that push works. It is that adding it changed nothing else.

## Scenario 4 — the architecture claim is tested, not asserted

**SC-105, Principle VII.**

```bash
./gradlew archTest
```

Phase 1 claimed adding a channel touches only `channel/` and config. This is the first real
exercise of that claim.

**If it fails, that is a finding, not a licence to relax the rule.** The one accepted exception is
the `ChannelProviderPort` signature gaining the idempotency key — that is the abstraction itself
changing, not channel specifics leaking into routing or retry.

## Scenario 5 — a stranded delivery is reclaimed

**FR-159, B-13.** The defect fix, and the scenario the happy path never reaches.

Orphan a delivery mid-attempt — leave it `IN_PROGRESS` with an expired lease, as a worker or
provider crash would — then run the worker.

Expect it **reclaimed** and driven to a terminal state. Before this change it is stranded forever,
and rollup rule 2b reports the notification `IN_PROGRESS` indefinitely: status lying about work that
will never complete.

Two guards to verify alongside:

- **The budget is not bypassed** (FR-159a): a reclaimed delivery consumes an attempt. Otherwise a
  repeatedly crashing provider produces unbounded attempts.
- **Expiry still outranks reclaim** (FR-159b): a stranded delivery whose notification has since
  expired reaches `EXPIRED`, not a fresh attempt.

## Scenario 6 — the re-attempt carries the same key

**FR-161, FR-163, spec D12.** Reclaim a delivery whose provider call already succeeded, and inspect
the key on the repeat.

Expect the **same key** as the attempt it repeats, derived from `(notification, recipient, channel,
attempt number)` — reproducible after a crash that recorded nothing.

**What this proves and what it does not.** It proves *our* half of D12: the repeat is recognisable.
It proves nothing about the provider's half. The simulated provider honours the key so the behaviour
is demonstrable end to end, but against a real provider that ignores it, a reclaimed delivery can
duplicate and this service cannot detect it (**G-59**, a DO-004 limitation beside G-22).

## Scenario 7 — a duplicate submission is suppressed and visible

**SC-109, FR-140, FR-143, FR-144.** With deduplication enabled, submit twice with the same
`(source system, correlation identifier)`.

Expect **`200 OK`** — not `202` — with a body naming the original notification, **no second
delivery**, and a `NOTIFICATION_SUPPRESSED` audit record.

Assert the **status code alone** first, because that is what a consumer ignoring unfamiliar fields
would see (FR-144a). A `202` carrying a `suppressed` flag would pass a body-reading test while
still letting such a consumer believe its notification was accepted.

Suppression must never be silent. A dropped notification that reports success is indistinguishable
from one that was lost, and that is this feature's most damaging failure mode — which is also why
this is the response that tells a caller they have reused an event identifier (G-55).

## Scenario 8 — a failed original does not suppress a retry

**FR-141c.** Let a notification fail terminally, then resubmit the same pair.

Expect the resubmission **accepted, not suppressed**. Suppressing after a permanent failure would
convert a delivery problem into unrecoverable data loss.

## Scenario 9 — deduplication is inert when off

**FR-147, FR-164.** With both deduplication flags off, submit duplicates.

Expect phase-1 behaviour exactly: two independent notifications with distinct identities (D4). The
flag is the rollback mechanism, and rollback is only real if "off" means "as before".

## Scenario 10 — retry scheduling and execution are distinguishable

**SC-110, FR-150, FR-150a.** Drive a retryable failure followed by a successful retry.

Expect distinct records for the retry being **scheduled** and being **executed**, and — with several
retries — an unambiguous pairing between them. Today both records exist but nothing ties an
execution to the scheduling that caused it.

## Scenario 11 — nothing sensitive leaks

**SC-111, Principle V.**

```bash
./gradlew test --tests 'com.notification.privacy.*'
```

The existing marker-based gate, extended to the new records. **Push provider credentials** are the
new sensitive value; the suppression record's correlation identifier is *already* stored unmasked as
the audit index (phase-1 FR-048), so it adds no new exposure — checked rather than assumed.

## Scenario 12 — performance impact is measured

**SC-112, FR-107.** Measure and report the impact of each enhancement.

**No pass/fail threshold is asserted**, because no source document states one (G-40, G-11).
Measurement is required; a verdict on the number is not, and inventing one would be inventing a
requirement.

---

## Running everything

```bash
./gradlew check         # build, all layers, arch gate, privacy gate, coverage floors
./gradlew archTest      # Principle VII gate alone
```

Coverage floors are unchanged and must not fall (FR-108).

## Limitations this phase adds to DO-004

| Limitation | Detail |
|---|---|
| **G-59** | Delivery-level duplicate suppression depends on the provider honouring the key. We supply it; we cannot verify it is respected. Simulated providers honour it, which proves our half only |
| **G-49** | Push has no device token. A token has no source but the platform that issues it, and no source document supplies one |
| **G-55** | Deduplication correctness depends on callers making event identifiers unique — a contract we cannot enforce. Mitigated by D13: the caller is told in the response rather than left to discover missing notifications |
| **D14** | The 24-hour window means a caller legitimately repeating an event within a day has the repeat suppressed. Most likely to bite a periodic job reusing one event identifier |
| **G-56** | Enabling deduplication changes what an existing field means for callers never told it must be unique. The migration path must require an audit of identifier usage first |
| **G-46** | Adding `PUSH` is additive by the constitution's definition, but a strict consumer of the closed enum can still break on an unrecognised value |
| **ADR-018** | A provider's `Retry-After` hint is not honoured; backoff is the configured schedule |
| **G-26, G-32, G-15** | Carried unchanged from phase 1 — preference factor unmet, no destination data, `priority` drives nothing |
