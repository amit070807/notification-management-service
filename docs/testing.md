# Testing Approach, Limitations and Trade-offs

**Deliverables DO-004, DO-005** (source §5) · 2026-09-10

---

## Approach

Tests are mandatory here, not optional. Constitution Principle VI is non-negotiable: a failing test
must exist before the implementation that satisfies it, and a pull request whose history shows
otherwise is rejected.

**Five layers**, all blocking in CI:

| Layer | What it proves | Location |
|---|---|---|
| Unit / truth tables | Routing across every input combination; the retryability matrix; the deduplication boundary; the retry-pairing derivation; the severity rank **pairwise**; every state transition **including all illegal ones** | `src/test/java/com/notification/unit` |
| Contract | Requests and responses conform to `contracts/openapi.yaml`; code and contract declare identical enums | `.../contract` |
| Integration | Full lifecycle against real PostgreSQL via Testcontainers; concurrency; timestamps; audit; suppression; reclaim; claim ordering | `.../integration` |
| Architecture | The domain imports no framework and no adapter; adding a channel touches nothing outside `channel/` | `.../architecture` |
| Privacy | No content, credential or unmasked recipient reference reaches audit, logs or metric labels | `.../privacy` |

A sixth layer is deliberately **not** a gate: `.../performance` measures cost and asserts nothing (see
[performance-phase2.md](./performance-phase2.md)). A timing check inside a merge gate goes red for
reasons unrelated to correctness, which Principle VI does not permit. It runs on demand via
`./gradlew perfTest` and is excluded from `test`.

A seventh, `.../regression`, pins the flags-off baseline. Its job is to fail if any enhancement leaks
past the flag that is supposed to switch it.

**Determinism rules**, from Principle VI: no `Thread.sleep`, no `Awaitility`, no real network, no
reliance on execution order. Time advances through an injected `MutableClock`; the worker is invoked
directly rather than waited on. A flaky test is treated as a failing test.

Three decisions worth naming:

- **Real PostgreSQL, not H2.** H2's locking semantics differ, so the concurrent-worker test would pass
  against it while proving nothing about production (ADR-003). The cost is that integration tests need
  Docker.
- **A planted marker, not pattern matching.** The privacy test submits content containing a unique
  string and searches every observable surface for it. Matching patterns for emails or phone numbers can
  have gaps and produce false negatives; a marker cannot. This is what caught the real leak described
  below.
- **Every flag-gated feature has a paired off-test.** `DedupDisabledTest`, `ReclaimDisabledTest`,
  `PushDisabledTest` and `ClaimOrderUnchangedTest` assert the old behaviour still holds with the flag off
  — including, in the reclaim case, that a known defect is faithfully preserved, and in the severity case
  that ordering is ignored *entirely* rather than partially. It is the pair that demonstrates the flag is
  really the switch; either test alone proves much less.

## What testing actually found

Recorded because "the tests pass" is a weaker claim than "the tests caught these":

| Defect | How it surfaced |
|---|---|
| `NOTIFICATION_REJECTED` was never emitted, and the schema made it impossible — `audit_event.notification_id` was `NOT NULL` while FR-007 forbids creating a notification for a rejection | Audit completeness test asserting every declared event type is reachable |
| Audit history had no reliable order — events in one transaction share a timestamp, so ordering fell back to a random UUID | Ordering assertion; would have appeared in production as occasional scrambling |
| **A real content leak**: a provider echoing the submitted message in its error text carried it into the audit payload | The marker-based privacy test |
| The rollup made `ACCEPTED` nearly unreachable | Status test on a freshly accepted notification |
| The OpenAPI contract used `allOf` for `ValidationProblem`, which strict validators reject | Contract test |
| A stale delivery snapshot caused an illegal state transition | The state machine refusing it |
| The privacy CI step overwrote `build/jacoco/test.exec` with a subset's coverage, starving the coverage gate | Coverage gate failing in CI while passing locally |
| **A reclaimed re-attempt could not be recorded at all.** It reuses its attempt number, which collides with `uq_attempt_number`, so the reclaim path threw a duplicate-key error on its own attempt insert | `StrandedDeliveryReclaimTest`, the first time it could actually run |
| Fourteen Spring contexts × a 10-connection pool exceeded `max_connections`, so whichever class loaded last failed with "too many clients already" and looked like the defect | Full-suite run after the context count grew |
| The worker claims a bounded batch oldest-first, so once the suite left more than one batch of due deliveries behind, a test's own new delivery was never attempted | `ExpiryOutranksRetryTest` failing in the suite and passing alone |
| A `privacyTest` Gradle task that CI invoked **did not exist** — the commit that switched CI to it changed only `ci.yml` | `./gradlew clean check archTest privacyTest` |
| PostgreSQL rejects `FOR UPDATE` in any query containing a window function, so the first severity-ordering query would not run at all | The first execution of `ClaimOrderUnchangedTest` |
| A CRITICAL submission produces **two** deliveries — the routing policy escalates SMS at that severity — so a fixture assuming one delivery per notification broke | `SeverityClaimOrderTest`, on `.single()` returning two rows |
| A claim of 50 takes whatever else the shared container has due, so an unfiltered eligibility count asserted the state of the whole suite rather than the feature | `ClaimEligibilityUnchangedTest` passing alone and failing in the suite |
| The dependency audit gate ran with its two flags collapsed into **one argument** by a YAML folded scalar, so the CVSS threshold would not have applied even once Java was found | Reading the CI log after fixing a different failure in the same step |

The content leak is the argument for the whole approach. The risk was identified during planning and the
field was documented as "bounded, non-content" — but only the length was ever enforced, and bounding
length does not stop prose.

The four rows about the coverage gate, the reclaim attempt row, the connection pool and the batch
starvation are the argument for a second discipline: **a gate that cannot run is not a gate.** All four
were latent for as long as the integration suite was unrunnable on the development machine, and all four
surfaced within minutes of fixing a one-line Docker API pin. Two of them had already been committed as
working features.

The two rows about the missing `privacyTest` task and the collapsed dependency-audit arguments are the
sharper version of the same point: **a gate that runs and enforces nothing is worse than one that
fails**, because it reports success. One did not exist and was invoked; the other existed and applied no
threshold.

### A recurring shape

Across both phases, the same pattern produced most of the findings: **an assumption that fills a hole
the source left open is usually hiding a defect.** The content field "bounded, non-content"; the audit
vocabulary "obviously reachable"; the reclaim path "just re-runs the attempt". Each read as settled and
each was wrong in a way only an executable check exposed.

A second shape: **a test that finds nothing may not have looked.** Two privacy
tests would have passed vacuously — one because the push credential was never bound, another because
two of the three new audit types are only written when a flag is on. Both now assert that the thing
being scanned was actually produced. A scan that finds nothing because nothing happened is not a pass.

A third, from feature 003: **a near-miss is more dangerous than a miss.** Severity is stored as text, so
ordering on the stored value gets `CRITICAL` and `HIGH` right and inverts `MEDIUM` and `LOW`. An
implementation that simply sorted the column would pass every test phrased as "the critical one came
first", ship, and mis-order half the severities forever. The guard is a pairwise truth table rather than
an extremes check — and the same reasoning produced the assertion that ranks are *distinct*, since a
duplicated rank makes two severities tie and fall through to age, which an extremes check also cannot
see.

## Limitations

Every item here reaches the deliverable unresolved. DO-005 requires them stated rather than discovered.

### Requirements knowingly not met

- **§4.3 recipient-preference routing factor (G-26).** Three of four factors implemented. The source
  names preferences as an input but supplies no preference data and defines no preference source (G-05).
  **This service must not be described as satisfying §4.3.**
- **No destination data for delivery (G-32).** §3.1 and §4.5 presuppose a resolvable destination, but
  §4.1 defines no such field and no section defines a directory. A recipient is an opaque reference; a
  real deployment needs a resolution step this specification does not describe. Push inherits this: a
  device token is the same opaque reference.

### Assumed, not implemented — read these before relying on deduplication

- **Provider-side deduplication is assumed (G-59, D12, FR-162).** Every provider call carries a stable
  idempotency key, which makes a repeat *recognisable*. Nothing here makes a provider *recognise* it,
  and **no test in this repository asserts that any provider honours it** — a scripted double honouring
  the key would prove only that the double was written to. Enabling reclaim against a provider that does
  not deduplicate converts a stranded delivery into a duplicate delivery (G-58). Confirm per provider.
- **The deduplication boundary rests on a caller contract this service cannot enforce (G-55, G-56).** The
  boundary is `(source system, correlation id)`. If a caller reuses an event identifier across distinct
  events, real notifications are discarded and the audit trail records each discard as correct. Nothing
  here can tell that case from the one the feature is for. The pre-enablement audit in
  [migration-phase2.md](./migration-phase2.md) is the only control.
- **The 24-hour deduplication window is an assumption (D14).** No source document states a window. Two
  submissions of the same event 25 hours apart both proceed; a caller whose retry cadence exceeds a day
  gets no protection.
- **Suppression is irreversible (G-48).** A notification suppressed while the flag was on was never
  delivered and rolling back does not deliver it. `notification_suppression` records what did not get
  sent; resubmission is the caller's action.

### Unproven

- **No real provider integration (G-22).** All providers are simulated, push included. The failure
  taxonomy is exercised through the production code path, but nothing here proves interoperability with
  a real email, SMS or push provider.
- **`Retry-After` is not honoured (ADR-018).** A provider's rate limit maps to
  `TRANSIENT_PROVIDER_FAILURE` and is retried on this service's own per-channel schedule. If a provider
  supplies a `Retry-After` hint, it is ignored. The push schedule backs off more patiently to compensate,
  which is a mitigation, not the same thing.
- **CI has never executed (G-46).** The workflow is written and every gate now passes locally from clean,
  but the branch has not been pushed, so the gates remain unproven in CI (ADR-011). One CI-only defect —
  a missing `privacyTest` task — was already found by reading rather than by running.
- **Authentication is a placeholder (G-10).** The source states no authN/authZ requirement for these
  APIs. Callers are assumed authenticated at the edge; nothing in this service enforces it. **Not a
  production posture.**
- **Provider crash recovery is tested by writing the row state, not by crashing.** Killing a worker
  mid-transaction rolls it back and strands nothing, so the stranded state is written directly. It is a
  reachable state — the worker commits the lease and `IN_PROGRESS` in one transaction and calls the
  provider in the next — but the crash itself is simulated.

### Deliberate and irreversible while enabled

- **Unbounded starvation of low severity (G-64, decision D-15).** With severity-ordered claiming on, a
  `LOW` delivery under sustained higher-severity traffic may remain eligible and unclaimed indefinitely.
  FR-207 forbids capping it. It is also **invisible** — claim order appears in no response (G-63) and no
  starvation metric is in scope, so an aged eligible `LOW` delivery is indistinguishable from a stalled
  worker. `SeverityStarvationTest` asserts the starvation as *intended*, so a future "fix" reverses the
  decision loudly rather than quietly.
- **Recovery is not expedited (D-16).** Reclaimed deliveries participate in severity ordering on the same
  terms as fresh work, so a crashed `LOW` delivery waits behind new `CRITICAL` traffic.
- **Claim order is unobservable (G-63).** No response reveals it. It is tested at the repository and
  worker boundary instead, which is why `ClaimOrderPreservedTest` asserts what the *provider* saw.
- **The join-versus-denormalisation choice was not made on measured grounds (G-61).** No performance
  target exists to decide against. Measurement afterwards found no resolvable cost, which is not the same
  as the decision having been justified by measurement.

### Unresolved in the source

- **The source document is an excerpt (G-01).** §4 runs 4.1, 4.2, 4.3, 4.5, 4.9 — sections 4.4, 4.6, 4.7
  and 4.8 are absent. Obligations may exist that this service does not cover.
- **No performance targets (G-11, G-40).** No throughput, latency, volume or retention figure is stated,
  so none is asserted. [performance-phase2.md](./performance-phase2.md) reports measurements and renders
  no verdict, for this reason.
- **`priority` drives no behaviour (G-15).** It is a mandatory submission field, but no requirement
  anywhere states an effect for it, and §4.3 names severity — not priority — as the routing factor.
  Captured, stored and returned only. This was re-examined for this phase and the conclusion is
  unchanged: the brownfield source states no priority requirement either.
- **Content retention after a terminal state (G-25).** Content is retained for the notification's
  lifetime with no purge policy. Minimising this would better serve §4.9's intent;
  `notification_content` is a separate table so it can change without touching the aggregate.
- **Resilience beyond retry and failure handling is out of scope (D10).** Circuit breaking, bulkheads and
  load shedding are not implemented. §4.5 covers retry and failure handling, and that is what is built.

## Trade-offs accepted

| Trade-off | Reasoning |
|---|---|
| Architecture rules enforced at test time, not compile time | A single Gradle project with ArchUnit satisfies the constitution literally and honours its simplicity default. A multi-project build would fail at `javac` instead. Mitigated by making the gate non-bypassable (ADR-002) |
| Routing policy needs a restart to change | Determinism is then trivial — no in-flight submission straddles two versions. No availability target exists to justify the alternative (ADR-014) |
| Feature flags need a restart to change | Suppression is irreversible, so a mid-flight flip would suppress across two states of the world with no clean boundary. A restart is the boundary (ADR-016) |
| Notification state computed on every status read | The stored column is a worker checkpoint; recomputing keeps status truthful if a worker died mid-update. Costs a rollup per read |
| Masking keeps 2 plaintext characters | Enough to correlate and to recognise a recipient while debugging. **This is not anonymisation** — a short digest of a low-entropy identifier is open to a dictionary attack (ADR-012) |
| `RetryPolicy` takes the jitter factor as a parameter | Keeps it a pure function, so the schedule is assertable exactly. Costs the caller remembering to supply real randomness |
| Integration tests require Docker | The consequence of rejecting H2. Slower and less portable, but the concurrency guarantee is real |
| The idempotency key is recomputed, never stored | It must survive a crash between the provider call and the outcome write. A stored key would need writing before the call and reading after, moving the same window down a level (ADR-019) |
| A reclaimed attempt row is upserted, losing the stranded attempt's start time | It is the same logical attempt re-executed. A fresh attempt number would satisfy the constraint by changing the key, defeating the deduplication the reclaim exists to make safe. `DELIVERY_RECLAIMED` records that it happened twice |
| The deduplication boundary is an index, not a unique constraint | A constraint would *reject* where the requirement is to *suppress*, and uniqueness across the pair is a caller contract this service cannot enforce (G-55) |
| Leftover due deliveries are parked before each test | The worker claims a bounded batch oldest-first, so an accumulating suite starves its own newest delivery. Parking by pushing `next_attempt_at` out avoids inventing an outcome, unlike deleting or forcing a terminal state |
| Severity read by join, not denormalised onto `delivery` | One source of truth and no migration, at the cost of a join in a query every delivery passes through. Measured at no resolvable cost, but chosen on scope grounds (ADR-026) |
| The claim is four CTEs and a window function rather than a bare `ORDER BY` | More complex than Principle VII's simplicity default likes. Accepted because the simple version is wrong in a way no test would catch: `RETURNING` order is not guaranteed, so an ordering feature whose order never reaches the worker would look built (ADR-028) |
| Claim-ordering tests assert on the **notification**, not a single delivery | A CRITICAL submission produces two deliveries via SMS escalation, and which channel the claim returns first is not something FR-201 governs. Asserting a delivery id would test the query plan |

## Running the tests

```bash
./gradlew test          # all layers except performance
./gradlew archTest      # architecture gate alone, ~4s
./gradlew privacyTest   # the Principle V merge blocker, on its own exec file
./gradlew check         # everything plus coverage floors
./gradlew perfTest      # measurement only, asserts nothing
```

Docker must be running for integration tests, and the daemon must accept API 1.44 (Engine 25 or later).
Coverage floors are 90% on domain packages and 80% overall, enforced by
`jacocoTestCoverageVerification`.

`privacyTest` and `archTest` are separate Gradle tasks rather than filtered `test` runs for a reason
that has already cost one CI run: a filtered `test` rewrites `build/jacoco/test.exec` from the subset,
so the coverage gate then measures the subset and fails on packages the full suite covers.

**Current state**: 335 tests, 0 failures. `./gradlew clean check archTest privacyTest` passes from clean.
