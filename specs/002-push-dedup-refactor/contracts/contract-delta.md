# API Contract Delta

**Date**: 2026-09-09 | **Plan**: [plan.md](../plan.md) | **ADR-020**

The authoritative contract stays at
[`specs/001-notification-management-core/contracts/openapi.yaml`](../../001-notification-management-core/contracts/openapi.yaml)
and is **edited in place** (ADR-020). This file specifies the delta; it is not itself a contract.

## Why the edit is not made during planning

`EnumParityTest` asserts that the code's closed enums and the contract's enums match exactly. Adding
`PUSH` to the contract before the code has it would fail that test — correctly. Contract and code
must change together, so the YAML edit belongs to the task that adds the enum value, not to this
phase.

That is the gate doing its job, and it is worth stating: a planning step that leaves the build red
would be a planning step that broke a blocking gate.

## Change 1 — `Channel` gains `PUSH`

```yaml
Channel:
  type: string
  description: Closed set (ADR-005, extended by feature 002).
  enum: [EMAIL, SMS, PUSH]
```

**Compatibility**: additive within the major version, permitted by Principle I, which names removal,
type narrowing and enum-value *removal* as the breaking changes.

**Residual risk — G-46**: additive by the constitution's definition is not the same as safe for
every consumer. A client that validates responses against a *closed* enum will reject `PUSH` on a
status response even though nothing was removed. Two facts bound the risk:

- A caller who never requests push never sees it in their own notifications' status.
- A caller who reads *other* systems' notifications can encounter it.

**Mitigation available and not yet chosen (U-9 territory)**: ship the enum change with push disabled
in routing policy (ADR-017), so the value exists in the contract before any response can contain it.
That gives consumers a window to widen their parsing.

## Change 2 — a suppressed submission returns `200 OK` (ADR-021, spec D13)

FR-144 requires a suppressed submission to be reported **in the response to that submission**.
`202 Accepted` would misreport: nothing was accepted for processing, and no delivery exists.

```yaml
  /notifications:
    post:
      responses:
        '202':
          description: Accepted for processing (FR-006).
        '200':
          description: |
            Suppressed as a duplicate. No delivery was created (feature 002 FR-140).
            The body names the original notification this submission duplicated, so the
            caller can inspect it. Only reachable when deduplication is enabled for the
            caller (FR-144b).
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SubmissionSuppressed'
        '400':
          description: Rejected. Every offending field is named (FR-004).
```

```yaml
    SubmissionSuppressed:
      type: object
      required: [suppressed, originalNotificationId, clientNotificationId, suppressedAt]
      properties:
        suppressed:
          type: boolean
          description: Always true. Present so a body-reading consumer sees it explicitly.
        originalNotificationId:
          type: string
          format: uuid
          description: The notification this submission duplicated (FR-144).
        clientNotificationId:
          type: string
          description: Echoed from the request.
        suppressedAt:
          type: string
          format: date-time
```

**Why a status code and not a field on the 202.** FR-144a requires the response to be
distinguishable from an acceptance **without reading the body**. A `202` carrying
`suppressed: true` is purely additive — the most literal reading of FR-103 — but a consumer that
ignores unfamiliar fields sees `202` and believes its notification was accepted. That relocates the
silent failure rather than removing it, from "no difference" to "a difference they will not notice".

**Compatibility cost, and its bound.** Adding a status code to an operation that has only returned
`202` or `400` is a behaviour change for consumers that switch on status. The feature flag bounds
it: a `200` cannot occur before deduplication is enabled for that caller, which per G-56 requires
the migration-path audit first. It never arrives unannounced.

`409 Conflict` was rejected: `4xx` implies caller error, but suppression is the system working as
designed on a legitimate request, and it would make routine deduplication appear as failure in
every client's error metrics.

## Change 3 — status of a suppressed submission

A suppressed submission creates no notification, so it has no status resource of its own. The
response names `originalNotificationId`, and the caller retrieves that notification's status
normally. The suppression itself is visible in the response and in audit history
(`NOTIFICATION_SUPPRESSED`), which satisfies §4.9's "reflected in status **or** audit history".

No change to the status endpoint.

## What does not change

| Unchanged | Why |
|---|---|
| `RecipientRef` stays an opaque string | G-49: push needs a device token; no source supplies one |
| `SubmitNotificationRequest` gains no field | Deduplication keys on fields that already exist |
| `FailureClassification` unchanged | ADR-018 maps rate limiting to `TRANSIENT_PROVIDER_FAILURE` rather than extending a closed enum §4.5 fixes |
| `DeliveryState` unchanged | Reclaim reuses `IN_PROGRESS → QUEUED`; no new state is needed |
| Error shapes unchanged | Suppression is not a rejection |

## Verification

- `EnumParityTest` — all closed enums match, including the widened `Channel`.
- `SubmitNotificationContractTest`, `NotificationStatusContractTest` — conformance including the
  new fields.
- `oasdiff` breaking-change gate in CI — must report the change as non-breaking.
