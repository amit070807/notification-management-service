# Testing Approach, Limitations and Trade-offs

**Deliverable DO-004** (source §5) · 2026-09-07

---

## Approach

Tests are mandatory here, not optional. Constitution Principle VI is non-negotiable: a failing
test must exist before the implementation that satisfies it, and a pull request whose history
shows otherwise is rejected.

**Five layers**, all blocking in CI:

| Layer | What it proves | Location |
|---|---|---|
| Unit / truth tables | Routing across every input combination; the retryability matrix; every state transition **including all illegal ones** | `src/test/java/com/notification/unit` |
| Contract | Requests and responses conform to `contracts/openapi.yaml`; code and contract declare identical enums | `.../contract` |
| Integration | Full lifecycle against real PostgreSQL via Testcontainers; concurrency; timestamps; audit | `.../integration` |
| Architecture | The domain imports no framework and no adapter; adding a channel touches nothing outside `channel/` | `.../architecture` |
| Privacy | No content, credential or unmasked recipient reference reaches audit, logs or metric labels | `.../privacy` |

**Determinism rules**, from Principle VI: no `Thread.sleep`, no `Awaitility`, no real network, no
reliance on execution order. Time advances through an injected `MutableClock`; the worker is
invoked directly rather than waited on. A flaky test is treated as a failing test.

Two decisions worth naming:

- **Real PostgreSQL, not H2.** H2's locking semantics differ, so the concurrent-worker test would
  pass against it while proving nothing about production (ADR-003). The cost is that integration
  tests need Docker.
- **A planted marker, not pattern matching.** The privacy test submits content containing a unique
  string and searches every observable surface for it. Matching patterns for emails or phone
  numbers can have gaps and produce false negatives; a marker cannot. This is what caught the real
  leak described below.

## What testing actually found

Recorded because "the tests pass" is a weaker claim than "the tests caught these":

| Defect | How it surfaced |
|---|---|
| `NOTIFICATION_REJECTED` was never emitted, and the schema made it impossible — `audit_event.notification_id` was `NOT NULL` while FR-007 forbids creating a notification for a rejection | Audit completeness test asserting all 10 declared event types are reachable |
| Audit history had no reliable order — events in one transaction share a timestamp, so ordering fell back to a random UUID | Ordering assertion; would have appeared in production as occasional scrambling |
| **A real content leak**: a provider echoing the submitted message in its error text carried it into the audit payload | The marker-based privacy test |
| The rollup made `ACCEPTED` nearly unreachable | Status test on a freshly accepted notification |
| The OpenAPI contract used `allOf` for `ValidationProblem`, which strict validators reject | Contract test |
| A stale delivery snapshot caused an illegal state transition | The state machine refusing it |

The third one is the argument for the whole approach. The risk was identified during planning and
the field was documented as "bounded, non-content" — but only the length was ever enforced, and
bounding length does not stop prose.

## Limitations

Every item here reaches the deliverable unresolved. DO-005 requires them stated rather than
discovered.

### Requirements knowingly not met

- **§4.3 recipient-preference routing factor (G-26).** Three of four factors implemented. The
  source names preferences as an input but supplies no preference data and defines no preference
  source (G-05). **This service must not be described as satisfying §4.3.**
- **No destination data for delivery (G-32).** §3.1 and §4.5 presuppose a resolvable destination,
  but §4.1 defines no such field and no section defines a directory. A recipient is an opaque
  reference; a real deployment needs a resolution step this specification does not describe.

### Deferred by decision

- **Idempotency, deduplication and replay (G-12).** Deferred by the constitution (v2.0.0, register
  item 9). Duplicate client identifiers are accepted as independent notifications (spec D4) —
  defined behaviour, but explicitly not idempotency: no body comparison, no replay, no dedup
  window.

### Unproven

- **No real provider integration (G-22).** All providers are simulated. The failure taxonomy is
  exercised through the production code path, but nothing here proves interoperability with a
  real email or SMS provider.
- **CI has never executed.** The workflow is written and every gate runs locally, but the branch
  has not been pushed, so the gates are unproven in CI (ADR-011).
- **Authentication is a placeholder (G-10).** The source states no authN/authZ requirement for
  these APIs. Callers are assumed authenticated at the edge; nothing in this service enforces it.
  **Not a production posture.**

### Unresolved in the source

- **The source document is an excerpt (G-01).** §4 runs 4.1, 4.2, 4.3, 4.5, 4.9 — sections 4.4,
  4.6, 4.7 and 4.8 are absent. Obligations may exist that this service does not cover.
- **No performance targets (G-11).** No throughput, latency, volume or retention figure is stated,
  so none is asserted. Any number used while tuning is an engineering assumption.
- **`priority` drives no behaviour (G-15).** It is a mandatory submission field, but no
  requirement anywhere states an effect for it, and §4.3 names severity — not priority — as the
  routing factor. Captured, stored and returned only.
- **Content retention after a terminal state (G-25).** Content is retained for the notification's
  lifetime with no purge policy. Minimising this would better serve §4.9's intent;
  `notification_content` is a separate table so it can change without touching the aggregate.

## Trade-offs accepted

| Trade-off | Reasoning |
|---|---|
| Architecture rules enforced at test time, not compile time | A single Gradle project with ArchUnit satisfies the constitution literally and honours its simplicity default. A multi-project build would fail at `javac` instead. Mitigated by making the gate non-bypassable (ADR-002) |
| Routing policy needs a restart to change | Determinism is then trivial — no in-flight submission straddles two versions. No availability target exists to justify the alternative (ADR-014) |
| Notification state computed on every status read | The stored column is a worker checkpoint; recomputing keeps status truthful if a worker died mid-update. Costs a rollup per read |
| Masking keeps 2 plaintext characters | Enough to correlate and to recognise a recipient while debugging. **This is not anonymisation** — a short digest of a low-entropy identifier is open to a dictionary attack (ADR-012) |
| `RetryPolicy` takes the jitter factor as a parameter | Keeps it a pure function, so the schedule is assertable exactly. Costs the caller remembering to supply real randomness |
| Integration tests require Docker | The consequence of rejecting H2. Slower and less portable, but the concurrency guarantee is real |

## Running the tests

```bash
./gradlew test          # all layers
./gradlew archTest      # architecture gate alone, ~4s
./gradlew check         # everything plus coverage floors
./gradlew test --tests 'com.notification.privacy.*'   # the merge blocker
```

Docker must be running for integration tests. Coverage floors are 90% on domain packages and 80%
overall, enforced by `jacocoTestCoverageVerification`.
