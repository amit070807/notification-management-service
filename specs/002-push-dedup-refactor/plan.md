# Implementation Plan: Push Channel, Deduplication and Provider Refactoring

**Branch**: `feature/phase-2-brownfield` | **Date**: 2026-09-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-push-dedup-refactor/spec.md`

**Governing document**: `.specify/memory/constitution.md` **v2.1.0** (amended 2026-09-09)

**Baseline**: phase 1 at commit `0c4ca3e` — 196 tests, all gates green.

## Summary

Three enhancements to a running system, plus one defect correction the enhancements exposed.

| # | Change | Nature |
|---|--------|--------|
| US1 | Add a `PUSH` channel | Additive |
| US2 | Per-provider error mapping and retry strategy | Refactoring |
| US3 | Deduplication at submission and delivery | New capability + **defect fix** |
| US4 | Retry audit records | Correction |

**Technical approach**: the stack is inherited, not chosen — this is the same Java 21 / Spring Boot
/ PostgreSQL service, and every change is made inside the existing hexagonal boundaries. The design
work is therefore about *where a change is allowed to land*, not what to build with.

Three things shape the plan more than the feature list does:

1. **Everything is flagged, default off.** FR-105 requires that with all flags off the system is
   indistinguishable from the phase-1 baseline. That makes the phase-1 test suite the regression
   oracle: it must pass unmodified against the flagged-off build.
2. **The push channel is the architecture test's first real exercise.** Phase 1 asserted that adding
   a channel touches only `channel/` and config. This plan is where that claim is either confirmed
   or found wanting — and it is deliberately not "fixed" by relaxing the rule if it fails.
3. **Deduplication splits across a trust boundary.** Per spec D12, this service reclaims stranded
   deliveries and supplies a stable key; the provider recognises it. The plan implements the
   worker's half only and declares the other.

## Technical Context

**Inherited unchanged from phase 1** — no re-decision, no new ADRs:

Java 21 · Spring Boot 3.4 · Gradle single project with ArchUnit boundaries · PostgreSQL 16 via
`JdbcClient` with explicit SQL · Flyway forward-only migrations · in-process transactional outbox ·
JUnit 5 + AssertJ + Testcontainers + swagger-request-validator · JaCoCo floors 90% domain / 80%
overall. Rationale in [001 research.md](../001-notification-management-core/research.md) ADR-001–015.

**New in this phase**:

| Concern | Approach |
|---------|----------|
| Channel set | `PUSH` added to the closed enum; contract change is additive (Principle I) |
| Feature flags | Configuration properties, matching the existing `RetryProperties`/`ChannelProperties` pattern. No new library — see ADR-016 |
| Per-provider retry | `RetryPolicy` becomes per-channel with a default, rather than a single bean |
| Deduplication state | New tables; no second datastore |
| Provider idempotency key | Derived, not stored — see ADR-019 |

**Performance**: still no target asserted. FR-107 requires the impact to be *measured*; G-40 records
that nothing defines "acceptable". Measurement is a task; a pass/fail threshold is not, because
inventing one would be inventing a requirement.

## Constitution Check

*GATE: evaluated against v2.1.0, which added obligations this feature must satisfy.*

### The v2.1.0 additions — the ones this feature exists to satisfy

| Obligation (Principle III) | How this plan satisfies it | Verdict |
|---|---|---|
| **Recoverability** — a delivery stopped mid-attempt must be reclaimable | `claimDue` extended to reclaim `IN_PROGRESS` rows whose lease has expired (FR-159). This is the B-13 defect fix | **PASS** |
| **Duplicate-execution safety** — every provider call carries a stable key | Key derived from `(notification, recipient, channel, attempt_number)`, all already held, so it survives a crash that recorded nothing (FR-161, FR-163) | **PASS** |
| **Documented boundary** | `(source system, event identifier)`, window, and failure-reset behaviour in `data-model.md` and DO-004 (FR-141, FR-142) | **PASS** |
| **Observable suppression** | New audit event type plus a status representation; a suppressed submission is never silently dropped (FR-143, FR-144) | **PASS** |
| **No suppression after terminal failure** | Boundary query excludes terminally unsuccessful notifications (FR-141c) | **PASS** |
| **Switchable and inert when off** | Independent flags; SC-102 runs the phase-1 suite against the flagged-off build | **PASS** |
| **Counterparty responsibility documented, not claimed** | D12 split stated in `architecture.md` and DO-004; FR-162 tagged `[A]`, not `[C]` | **PASS** |
| **Caller-contract dependency documented before enablement** | G-55 in the migration path; the flag is what makes the pre-enablement audit possible (G-56) | **PASS** |

### The eight principles

| Principle | Assessment | Verdict |
|---|---|---|
| **I. Contract-First** | Adding an enum value is additive and permitted within a major version. Adding response fields is additive. `EnumParityTest` already fails if code and contract diverge. **Residual risk recorded as G-46**: a *strict* consumer of the closed `Channel` enum can still break on an unrecognised value | **PASS, with a recorded risk** |
| **II. Durable Accept-Then-Process (NON-NEG)** | Submission-level deduplication happens **inside** the existing acceptance transaction — the check and the suppression record commit atomically with everything else. A suppression decision taken outside it could suppress and then fail to record | **PASS** |
| **III. Idempotent, Deterministic Domain Core** | See the table above. Routing stays pure: `PUSH` is a policy value, not a branch | **PASS** |
| **IV. State Machine + Failure Taxonomy (NON-NEG)** | Reclaim introduces a new legal transition (`IN_PROGRESS → QUEUED`) which must be declared, not slipped in. Rate limiting maps to an existing classification rather than extending a closed enum — ADR-018 | **PASS** |
| **V. Audit Without Sensitive Data (NON-NEG)** | New event types use the sealed allowlist. Push credentials never persisted or logged. The privacy gate runs unchanged over the new records | **PASS** |
| **VI. Test-First (NON-NEG)** | Every change leads with a failing test. Two adversarial tests are now mandatory: duplicate submission, and reclaim of a stranded delivery | **PASS** |
| **VII. Modular Boundaries** | `PUSH` must touch only `channel/` and config. **If it does not, that is a finding, not a licence to weaken the rule** | **PASS, and under test** |
| **VIII. Documented, Engineer-Owned** | ADR-016 – ADR-020 below; the amendment record is already in the constitution | **PASS** |

**Result: all gates PASS.** The Complexity Tracking table is therefore omitted.

### Carried-forward obligations

| Item | Obligation on this plan |
|------|-------------------------|
| **G-26** — §4.3 preference factor still unmet | The second document to name it without supplying it. `RoutingRequest` stays three-factor; `RoutingPreferenceIndependenceTest` must still pass |
| **G-32 / G-49** — no destination data; push sharpens it | A device token has no source but the platform that issues it. Simulated provider accepts the opaque reference; a real one would not |
| **G-15** — `priority` still drives nothing | Unchanged by D8. Stays in limitations |
| **G-59** — provider-side dedup assumed | Declared in DO-004 beside G-22 |

## Design decisions

Full ADRs in [research.md](./research.md). Summarised here because they shape the structure.

- **ADR-016 — Feature flags are configuration properties.** No flag library. The existing
  `@ConfigurationProperties` pattern already provides per-deployment switching, which is what G-39
  assumes "gradual" means. A library would add a dependency for capability nobody asked for.
- **ADR-017 — The push flag is the routing policy's existing `enabled` switch.** No new mechanism:
  `routing-policy.yaml` already carries per-channel enablement, and a disabled channel already
  produces `CHANNEL_DISABLED` with a recorded reason. US1's flag requirement is satisfied by
  configuration that exists.
- **ADR-018 — Provider rate limiting maps to `TRANSIENT_PROVIDER_FAILURE`.** It is transient and
  retryable, and per-provider backoff (FR-132) gives push a longer schedule. Rejected: adding a
  classification, which would extend a closed enum §4.5 fixes and that the contract publishes.
- **ADR-019 — The provider key is derived, never stored.** `(notification, recipient, channel,
  attempt_number)` are all in rows written *before* the provider call, so the key is reproducible
  after a crash that recorded nothing. A stored key would need writing before the call — the exact
  window the key exists to survive.
- **ADR-021 — A suppressed submission returns `200`, not `202`.** `202` means accepted for
  processing, which is untrue when no delivery was created. The rejected alternative, `202` with a
  `suppressed` field, is additive but lets a consumer that ignores unfamiliar fields believe its
  notification was accepted — the silent failure this decision exists to remove. See spec D13.
- **ADR-020 — The OpenAPI contract is edited in place at its phase-1 path.** It describes the
  system, not a phase; its location is incidental. Rejected: copying it under `002/`, which creates
  two documents and ambiguity about which is authoritative. Phase 1's *spec* stays intact; only the
  living contract evolves, which is what a contract evolving means.

## Project Structure

### Documentation (this feature)

```text
specs/002-push-dedup-refactor/
├── spec.md              # 47 requirements, 27 gaps, 7 decisions
├── plan.md              # This file
├── research.md          # ADR-016 – ADR-020
├── data-model.md        # DELTA only — what changes against 001's model
├── quickstart.md        # DELTA — new scenarios plus the regression check
└── checklists/
```

`contracts/openapi.yaml` stays at its phase-1 path and is edited in place (ADR-020).

### Source Code — delta against the existing tree

```text
src/main/java/com/notification/
├── domain/
│   ├── model/Channel.java                    # + PUSH
│   ├── port/ChannelProviderPort.java         # + idempotency key parameter (all adapters affected)
│   ├── retry/RetryPolicy.java                # unchanged; selection becomes per-channel
│   └── dedup/                                # NEW — boundary as a pure function
│       ├── DeduplicationKey.java
│       └── DeduplicationDecision.java
├── application/
│   ├── SubmissionService.java                # + dedup check inside the accept transaction
│   └── DeliveryProcessingService.java        # + key on provider call; reclaim path
├── persistence/
│   ├── JdbcDeliveryRepository.java           # claimDue reclaims stranded IN_PROGRESS
│   └── JdbcDeduplicationRepository.java      # NEW
├── channel/
│   └── SimulatedPushProvider.java            # NEW — auth + rate-limit conditions
├── audit/payload/AuditPayload.java           # + suppression, retry-executed, reclaim records
└── config/                                   # + FeatureFlags, per-channel retry, push properties

src/main/resources/db/migration/
├── V5__deduplication.sql                     # NEW
└── V6__delivery_reclaim.sql                  # NEW — index for stale-lease reclaim

src/test/java/com/notification/
├── regression/                               # NEW — phase-1 suite against flags-off
├── unit/DeduplicationBoundaryTest.java       # NEW — truth table
└── integration/                              # + push, reclaim, suppression, retry-audit
```

**Structure Decision**: no new module, no new datastore, no new deployable. Every change lands
inside an existing boundary. The one signature change — adding the idempotency key to
`ChannelProviderPort` — is deliberate: it puts the D12 agreement in the type system, so a new
adapter cannot silently omit it.

## Sequencing

Order is load-bearing, not arbitrary:

1. **US1 Push** — first, because it creates the provider divergence US2 consolidates. Also proves
   or disproves the Principle VII claim while the codebase is still small.
2. **US2 Provider refactoring** — second. See G-57: after D10 removed graceful degradation and
   given the abstraction already exists, what remains is per-provider error mapping and retry
   strategy, **both of which push needs anyway**. This may collapse into US1's tail rather than
   standing alone, and the plan says so rather than pretending it is a full story.
3. **US3 Deduplication** — third, and internally ordered **reclaim before key**: FR-159 fixes a
   defect that exists today; FR-161 makes that fix safe. Shipping the key first would be safe but
   pointless; shipping the reclaim first would be actively harmful.
4. **US4 Retry audit** — last, recording actions the others produce.

## Unresolved — decisions this plan does not make

| ID | Open decision | Why it is not assumed | Needed by |
|----|---------------|-----------------------|-----------|
| ~~U-6~~ | ~~Deduplication window duration~~ | **RESOLVED (spec D14)**: 24 hours, configurable, recorded as an assumption. No constitutional amendment — Principle III requires the window to be defined and documented, not to hold a particular value |  — |
| ~~U-7~~ | ~~G-55 mitigation~~ | **RESOLVED (spec D13)**: every suppression is reported in the submission response. The risk was silence, not the choice of mitigation | — |
| ~~U-8~~ | ~~Suppression response shape~~ | **RESOLVED (spec D13)**: `200 OK` with a suppression body. `202` would misreport — nothing was accepted for processing | — |
| ~~U-9~~ | ~~Whether US2 remains a separate story~~ | **RESOLVED (ADR-022)**: real work. Push duplicated ~19 shared concerns across two adapters and added a channel branch to wiring. Scoped to base extraction, removing the branch, and per-channel retry | — |

## Phase Outputs

| Phase | Artifact | Status |
|-------|----------|--------|
| 0 | `research.md` — ADR-016 – ADR-020 | Complete |
| 1 | `data-model.md`, contract edit, `quickstart.md` | Complete |
| 2 | `tasks.md` | Run `/speckit-tasks` |

## Post-Design Constitution Re-Check

- **Principle IV** — the reclaim transition `IN_PROGRESS → QUEUED` is added to the declared
  transition table, so the truth-table test covers it like any other. It is not an exception path.
  **Still PASS.**
- **Principle II** — the deduplication check sits inside the acceptance transaction, so a
  suppression decision and its record commit together. **Still PASS.**
- **Principle I** — the suppression response adds a status code to an operation that has only
  returned `202` or `400`. Additive in schema terms, but a behaviour change for consumers switching
  on status. Bounded by the flag: it cannot occur before a caller opts in through the migration
  path (G-56). **Still PASS, with the cost recorded rather than waved through.**
- **Principle V** — suppression records name the deduplication key, which contains a caller-supplied
  correlation identifier. That is already recorded unmasked in audit today (it is the audit's own
  index), so this adds no new exposure. **Still PASS, and checked rather than assumed.**
- **Principle VII** — `PUSH` touches only `channel/` and config **except** the `ChannelProviderPort`
  signature change, which is in `domain/port/`. That is the abstraction itself, not a leak of
  channel specifics into routing or retry, and the extensibility test still holds. **Still PASS.**
- **New risk surfaced by design**: making `claimDue` reclaim `IN_PROGRESS` rows means a delivery
  whose provider call is merely *slow* — longer than the lease — can be reclaimed while still in
  flight. That converts a stranding bug into a duplicate-call risk under load, which is precisely
  why FR-161's key is not optional and why the two must ship together (G-58). The lease must also
  exceed the provider read timeout, or reclaim races every slow call.

**No gate regressed. No complexity justification required.**
