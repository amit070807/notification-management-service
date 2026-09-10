# Phase 0 Research: Push Channel, Deduplication and Provider Refactoring

**Date**: 2026-09-09 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Continues ADR numbering from phase 1, which ended at ADR-015. **No stack decision appears here**:
the stack is inherited, and re-deciding it would be re-litigating settled choices. ADR-001 through
ADR-015 remain in force unless an entry below supersedes one explicitly.

---

## ADR-016: Feature flags are configuration properties, not a flag library

**Context**: FR-104 requires each enhancement to be independently switchable and inert when off.
FR-105 makes that the rollback mechanism. Baseline fact B-10: no flag infrastructure exists.

**Options**: (a) `@ConfigurationProperties`, matching `RetryProperties` and `ChannelProperties`;
(b) a feature-flag library or service; (c) database-backed flags with runtime toggling.

**Decision**: (a).

**Consequences**: no new dependency, and the mechanism is already familiar in this codebase. Spec
G-39 assumes "gradual rollout" means per-deployment rather than per-caller or percentage-based;
(a) satisfies exactly that and nothing more. Rejected (b): a dependency for capability nobody
asked for, against Principle VII's YAGNI rule. Rejected (c): runtime toggling of deduplication is
actively undesirable — suppression is irreversible, so flipping it mid-flight would suppress
notifications between two states of the world with no clean boundary.

**Cost**: changing a flag needs a restart. Acceptable for the same reason ADR-014 accepted it for
the routing policy: no availability target exists (G-11).

**Serves**: FR-104, FR-105; Principle VII.

---

## ADR-017: The push feature flag is the routing policy's existing channel enablement

**Context**: FR-104 requires the push channel to be switchable. The routing policy already carries
per-channel `enabled`, and a disabled channel already yields `CHANNEL_DISABLED` with a recorded
reason (phase-1 FR-022).

**Decision**: `PUSH` is switched by `routing-policy.yaml`, not by a new flag.

**Consequences**: the requirement is met by configuration that already exists, and a disabled push
channel is *explained* rather than merely absent — the routing decision records why, which a
separate boolean elsewhere would not. Adding a second switch would create two sources of truth for
one question and a state where they disagree.

**Verification**: with `PUSH` disabled, a submission requesting it is accepted, no push delivery is
created, and `channelOutcomes` shows `CHANNEL_DISABLED`. That is the flag being inert, observably.

**Serves**: FR-104, FR-105, FR-111; Principle VII.

---

## ADR-018: Provider rate limiting maps to `TRANSIENT_PROVIDER_FAILURE`

**Context**: FR-117 requires rate limiting to be handled. Spec G-38 records that neither the limit
nor the required response is specified, and that rate limiting is absent from §4.5's five failure
kinds.

**Options**: (a) map to the existing transient classification; (b) add a `RATE_LIMITED`
classification; (c) throttle before attempting, so the condition never arises.

**Decision**: (a).

**Consequences**: a rate limit *is* transient and retryable, so the existing partition already
gives the right behaviour. Per-provider retry (FR-132) lets push back off more patiently than
email, which is the substantive part of "handling" it. Rejected (b): §4.5 fixes a five-kind
taxonomy and the contract publishes the enum; adding a value to satisfy a bullet in an option's key
considerations would extend a closed set the source defines, and would break strict consumers for
no behavioural gain. Rejected (c): client-side throttling requires a limit, and no limit is stated
— implementing it would mean inventing the number the requirement omits.

**Accepted limitation**: a provider's `Retry-After` hint, if one exists, is not honoured; backoff
is the configured schedule. Recorded in DO-004.

**Serves**: FR-117, FR-131, FR-132; Principle IV.

---

## ADR-019: The provider idempotency key is derived, never stored

**Context**: FR-161 requires every provider call to carry a key stable across re-attempts of the
same logical attempt. FR-163 requires it to be derivable from data already held. The case it must
survive: a crash after the provider call and before anything about the outcome is recorded.

**Options**: (a) derive from `(notification id, recipient id, channel, attempt number)`;
(b) generate a key and store it before the call; (c) let each adapter choose.

**Decision**: (a).

**Consequences**: all four components are written *before* the provider call — the delivery row at
acceptance, the attempt row at the start of the attempt — so the key is reproducible after a crash
that recorded nothing further. That is the whole point: option (b) would need a write immediately
before the call, which is the exact window the key exists to survive, and a crash between that
write and the call would leave a key for a send that never happened. Rejected (c): per-adapter key
derivation makes the D12 agreement unverifiable, since no two providers would be promised the same
thing.

**Consequence for the port**: the key becomes a parameter of `ChannelProviderPort.send`. Deliberate
— it puts the agreement in the type system, so a new adapter cannot silently omit it.

**Serves**: FR-161, FR-163, FR-165; spec D12; constitution Principle III (v2.1.0).

---

## ADR-020: The OpenAPI contract is edited in place at its phase-1 path

**Context**: the authoritative contract lives at
`specs/001-notification-management-core/contracts/openapi.yaml` and is referenced by the contract
tests. This phase changes it: a new channel value, and a representation for suppression.

**Options**: (a) edit in place; (b) copy it under `002/` and repoint the tests; (c) move it to a
repository-level `contracts/` directory.

**Decision**: (a).

**Consequences**: one document remains authoritative, and its history in git shows the API
evolving — which is what a living contract's history should show. Rejected (b): two contracts, and
a standing question about which one governs; the phase-1 copy would immediately be a lie about the
running system. Rejected (c): conceptually cleanest, since the contract belongs to the system
rather than to a phase, but it churns five documents and two test constants for no functional gain.
Worth doing if a phase 3 arrives and the oddity compounds.

**Note on what "keeping phase 1 intact" means**: phase 1's *specification* is a record of what was
built and why, and stays untouched. Its *contract* is a live artefact of the running system. A
contract that never changed would mean the API never evolved.

**Serves**: Principle I; feature 002 FR-103.

---

## ADR-021: A suppressed submission returns `200 OK`, not `202 Accepted`

**Context**: FR-144 requires a suppressed submission to be visible to the caller. Spec D13 decided
it is reported in the submission response. The remaining question is the shape.

**Options**: (a) `200 OK` with a suppression body; (b) `202 Accepted` carrying `suppressed: true`;
(c) `409 Conflict`.

**Decision**: (a).

**Consequences**: `202` means *accepted for processing*, and a suppressed submission creates no
delivery — returning it would misreport what happened. Rejected (b): purely additive and therefore
the most literal reading of FR-103, but a consumer that ignores unfamiliar fields sees `202` and
believes its notification was accepted. That is the silent failure D13 exists to remove, relocated
from "no difference" to "a difference they will not notice", which is harder to catch rather than
easier. Rejected (c): `4xx` implies caller error, but suppression is the system working as designed
on a legitimate request, and it would make routine deduplication show up as failure in every
client's error metrics.

**Cost**: a new status code on an operation that has only returned `202` or `400` is a behaviour
change for consumers switching on status. Bounded by the feature flag — the response cannot occur
before a caller has opted into deduplication through the migration path (G-56), so it never
arrives unannounced.

**Serves**: FR-144, FR-144a, FR-144b; spec D13; G-55.

---

## ADR-022: US2 is real work — resolves U-9

**Context**: G-57 observed that after D10 removed graceful degradation, and given `ChannelProviderPort`
already existed, Option 3's remaining scope might be satisfied as a by-product of US1. U-9 deferred
the answer until push had shown how much divergence it actually introduced. T027 is that decision.

**Evidence gathered after US1 landed**:

- The two adapters are 89 and 107 lines and carry ~19 hits each of the *same* concerns: timeout
  fields, call counting, fail-first-N logic, outcome construction. Before push there was one adapter
  shape and nothing to extract; there are now two copies of it.
- `ChannelConfig` gained `if (channel == Channel.PUSH)`. It is in `config/`, which the extensibility
  test legitimately exempts, so no rule was broken — but a fourth channel adds a fourth branch, and
  the wiring becomes a place where channel knowledge accumulates.
- Nothing leaked into `routing`, `retry`, `state` or `audit`. The Principle VII boundary held.

**Decision**: US2 proceeds, scoped to what is genuinely outstanding:

1. Extract the shared adapter behaviour push duplicated into a base type (FR-130).
2. Remove the channel branch from wiring, so adapters declare their own channel rather than the
   configuration deciding for them.
3. Per-channel retry selection, which is what "handling" rate limiting substantively means (FR-132,
   ADR-018).

**What US2 does not include**: extracting the interface, which phase 1 already did, and graceful
degradation, which D10 scoped out. Recording that keeps the phase honest about its size — a reader
comparing Option 3's bullet list against this codebase should be able to see which bullets were
already satisfied and which this phase addresses.

**Why the sequencing was right**: had US2 run first, the abstraction would have been extracted from a
single example. The duplication it removes did not exist until push created it.

**Serves**: FR-130, FR-131, FR-132; spec G-57; Principle VII.

---

## Resolved by inheritance

These were decided in phase 1 and are **not** revisited. Listed so their absence here is legible as
a decision rather than an oversight.

| Concern | Decided in |
|---------|-----------|
| Language, framework, build tool, module layout | ADR-001, ADR-002 |
| Datastore and async substrate | ADR-003 |
| Persistence approach | ADR-004 |
| Simulated providers | ADR-009, ADR-015 |
| Recipient model — opaque reference | phase-1 D5 |
| Routing policy fixed at startup | ADR-014 |
| Hand-written controllers plus conformance test | ADR-013 |

## ADR-023: A reclaimed re-attempt upserts its `delivery_attempt` row

**Status**: Accepted, during implementation of US3.

**Context**: The idempotency key includes the attempt number (ADR-019), and a reclaimed re-attempt
reuses its number — that is precisely what makes the repeat recognisable to the provider (FR-161).
But `delivery_attempt` carries `uq_attempt_number UNIQUE (delivery_id, attempt_number)` from phase 1,
so the re-attempt cannot insert a row. This was not anticipated when ADR-019 was written; the reclaim
path threw a duplicate-key error the first time it was executed against a real database.

**Options**:

1. **Assign the re-attempt a fresh number.** Satisfies the constraint. Also changes the key, which
   defeats the entire purpose of the reclaim being safe — the provider would see a new call, not a
   repeat, and could deliver twice.
2. **Drop the unique constraint.** Removes a real invariant to accommodate one path. The constraint is
   what stops two rows claiming to be the same attempt.
3. **Upsert the row.** `ON CONFLICT (delivery_id, attempt_number) DO UPDATE`, resetting the outcome to
   `PENDING` and clearing the stale finish data.

**Decision**: option 3.

**Reasoning**: a reclaimed re-attempt *is* the same logical attempt, executed again. The row should
therefore describe one attempt, not two. Option 1 would have satisfied a constraint by breaking a
requirement, which is the worst available trade — it would have looked correct and silently removed the
guarantee.

**Consequence**: the stranded attempt's start time is overwritten, so the row does not show that the
attempt ran twice. That is not lost: `DELIVERY_RECLAIMED` records it, with the attempt count and the
time, and is the only place that also records *why*. An operator asking "was this attempted twice"
reads the audit trail, not the attempt row.

**Also**: the row id must come from `RETURNING`, not from the generated value. On conflict the existing
row keeps its own id, so using the generated one would leave the outcome update matching nothing and
the attempt would stay `PENDING` forever — a silent stall in the code path that exists to fix a silent
stall.

## Open — carried into implementation, not defaulted

| ID | Open decision | Why it is not being assumed |
|----|---------------|------------------------------|
| **U-9** | ~~Whether US2 survives as a separate story (G-57)~~ | **Resolved by ADR-022**: US2 is real work. `AbstractChannelProvider` removed duplicated taxonomy mapping and diagnostic sanitising from three adapters, and made the fail-closed behaviour on an unmapped error code exist in one place instead of three |

## Deliberately not researched

- **A preference store** — G-26 stands; the second document to name recipient preferences without
  supplying them. Researching it would risk introducing the proxy FR-019a forbids.
- **Device token acquisition** — G-49. A push token has no source but the platform that issues it,
  and no source document supplies one.
- **Provider-side deduplication** — spec D12 assigns it to the provider. Designing it here would be
  designing someone else's system, and would invite claiming a guarantee this service cannot make
  (G-59).
- **Resilience patterns** — out of scope by D10.
