# Constitution Compliance Review

**Task T113** · Constitution v2.0.0 · Reviewed 2026-09-07 · Feature 001-notification-management-core

Recorded as review evidence, per the constitution's Review Evidence requirement. Each item is
verified against a specific artefact rather than asserted.

## Review Evidence checklist

| Requirement | Verified by | Result |
|---|---|---|
| Contract and schema match the required field lists | `SubmitNotificationContractTest`, `NotificationStatusContractTest`, `EnumParityTest` | **PASS** — all 8 closed enums match the contract exactly |
| State transitions are legal-set enforced | `DeliveryStateTransitionTest` — all 81 pairs, including every illegal one | **PASS** — illegal transitions throw |
| Each new failure path maps to the closed classification enum | `FailureClassificationTest`, `DeliveryOutcomeTest` | **PASS** — an unclassified failure is rejected at the port |
| Audit events exist for new significant actions | `AuditCompletenessTest` — all 10 declared types reachable in one run | **PASS** (was failing; see below) |
| No sensitive value is newly logged or persisted | `NoSensitiveDataLeakTest` — marker scan over audit, logs, metric labels | **PASS** (caught a real leak; see below) |
| No new dependency or abstraction lacks an ADR | ADR-001 … ADR-015 | **PASS** — 15 ADRs, 5 owner decisions recorded |

## Principle-by-principle

| Principle | Evidence | Result |
|---|---|---|
| **I. Contract-First** | `openapi.yaml` is hand-authored and authoritative; conformance and enum-parity tests block drift | PASS |
| **II. Durable Accept-Then-Process** (NON-NEG) | `SubmissionAcceptanceTest` asserts everything commits before 202; `ConcurrentWorkerClaimTest` proves two workers never claim one delivery | PASS |
| **III. Deterministic Domain Core** | `RoutingDeterminismTest`; ArchUnit forbids `Instant.now()`, `UUID.randomUUID()` and `java.util.Random` in the domain | PASS |
| **IV. State Machine + Failure Taxonomy** (NON-NEG) | Full 81-pair transition matrix; 6-value closed taxonomy with retryability declared in one place | PASS |
| **V. Audit Without Sensitive Data** (NON-NEG) | No update/delete on the port or in the DB grant; sealed payload allowlist; marker-based privacy gate | PASS |
| **VI. Test-First, Deterministic** (NON-NEG) | Every phase led with failing tests; no sleeps, no `Awaitility`, no real clock; suite stable across repeated full runs | PASS |
| **VII. Modular Boundaries** | 8 ArchUnit rules plus a vacuity guard; `ChannelExtensibilityTest` proves a channel change touches nothing core | PASS |
| **VIII. Documented, Engineer-Owned** | 15 ADRs; DO-001 … DO-006 delivered; every AI-generated file reviewed before commit | PASS |

## Quality gates

| Gate | Status |
|---|---|
| Build, lint/format | PASS |
| Unit, contract, integration tests | PASS — 194 tests, 0 failures |
| Architecture gate | PASS — runs standalone in ~4s |
| Privacy gate (merge blocker) | PASS |
| Coverage floors: 90% domain, 80% overall | PASS — reached by adding tests, **no threshold was lowered** |
| Secret scan, dependency audit, OpenAPI diff | Configured, **never executed** — the branch has not been pushed (ADR-011) |

## Non-negotiable principles: no exceptions taken

Principles II, IV, V and VI admit no exception, and none was needed. Where behaviour conflicted
with a gate, the code changed — not the gate.

## Defects found by the gates themselves

The gates earned their cost. Each of these was found by a check, not by inspection:

| Defect | Found by |
|---|---|
| A real content leak: a provider echoing the submitted message carried it into the audit payload | Privacy gate (Principle V) |
| `NOTIFICATION_REJECTED` was unreachable, and the schema made it impossible | Audit completeness (Principle IV/V) |
| Audit history had no reliable order — one timestamp per transaction, then a random UUID | Ordering assertion |
| A stale delivery snapshot attempted an illegal state transition | The state machine refusing it (Principle IV) |
| `allOf` in the contract, rejected by strict validators | Contract test (Principle I) |
| Three test-isolation defects from a shared fixture and a globally-scoped worker | Repeated full-suite runs (Principle VI) |
| The rollup made `ACCEPTED` nearly unreachable | Status test |

## Deviations recorded, not waived

| Item | Status |
|---|---|
| §4.3 recipient-preference routing factor (G-26) | **Unmet.** Named in DO-002 and DO-004 per DO-006. Defended by `RoutingPreferenceIndependenceTest` |
| No destination data for delivery (G-32) | **Unmet.** Named in both documents |
| Idempotency (G-12) | Deferred by constitution register item 9; D4 defines behaviour without implementing dedup |
| G-01, G-10, G-11, G-15, G-22, G-25 | Unresolved in the source; all listed in DO-004 limitations per DO-005 |

## Reviewer note

The two unmet requirements share one cause: **the source document names an input it never
supplies.** Both were found by asking where the data would come from, and both were recorded
rather than papered over with an invented data source. That is the outcome the specification
discipline was for.
