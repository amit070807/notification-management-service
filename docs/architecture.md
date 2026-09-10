# Architecture Overview

**Deliverable DO-002** (source §5) · Service: notification-management-service · 2026-09-10

Governing documents: [constitution](../.specify/memory/constitution.md) v2.1.0 ·
[core specification](../specs/001-notification-management-core/spec.md) ·
[brownfield specification](../specs/002-push-dedup-refactor/spec.md) ·
[core ADRs](../specs/001-notification-management-core/research.md) (ADR-001–015) ·
[brownfield ADRs](../specs/002-push-dedup-refactor/research.md) (ADR-016–022)

---

## 1. What this service does

Accepts notification requests, selects delivery channels, delivers asynchronously over email, SMS and
push with bounded retry, suppresses duplicate submissions, recovers deliveries stranded by a crash,
and exposes both delivery status and an audit history. Two HTTP APIs, one in-process worker, one
PostgreSQL database, one deployable unit.

## 2. Requirements this service does NOT meet

Stated first, because a reader deserves to know the shape of the gap before the design.

### §4.3 recipient-preference routing factor — **not implemented** (spec G-26)

Source §4.3 names four routing factors: requested channel, notification severity, **recipient
preferences**, and routing policy. This service implements **three**.

The reason is in the source, not in the schedule: §4.3 names preferences as an input, but §4.1's
submission field list does not carry them and no section of §3–§5 defines a preference store, owner,
shape, or lifecycle (spec G-05). **The document requires an input it never makes obtainable.**
Implementing the factor would have meant inventing both a data source and a preference model, then
presenting the result as a requirement.

**This service must not be described as satisfying §4.3.** Closing the gap requires answering G-05
first — where preferences come from and what they look like.

The deferral is actively defended, not merely documented: `RoutingRequest` carries exactly three
factors and no field through which a recipient attribute could reach a decision, and
`RoutingPreferenceIndependenceTest` fails if a recipient-shaped component is ever added (FR-019a).

### Delivery has no destination data (spec G-32)

Attempting delivery on a channel requires an address, endpoint or token. §4.1 defines no such field,
and no section defines a directory or resolution mechanism — yet §3.1 ("delivery attempts") and §4.5
("invalid recipient") both presuppose a resolvable destination.

A recipient is therefore modelled as an **opaque reference and nothing else** (spec D5). The reference
is passed to the channel provider unchanged. How a real provider would resolve it is undefined by the
source and is not invented here. Push inherits this unchanged: a push "device token" is the same
opaque reference, not a new field.

### Provider-side deduplication is assumed, not implemented (spec D12, G-59, FR-162)

This is the most important limitation in the service, and the one most easily misread as a feature.

Every provider call carries a stable idempotency key. That makes a repeated call **recognisable**. It
does not make the provider **recognise** it. Whether a repeat is actually suppressed is the provider's
behaviour, governed by an agreement between this service and that provider.

No test in this repository asserts that a provider honours the key, and none can — a scripted test
double honouring it would prove only that the double was written to honour it. What is tested is the
half this service owns: that the key is stable across a crash and derivable from committed rows.

**Consequence**: enabling delivery reclaim against a provider that does not deduplicate on the key
converts a stranded delivery into a duplicate delivery. That trade is spelled out in
[migration-phase2.md](./migration-phase2.md) and must be confirmed per provider before rollout.

### The deduplication boundary rests on a caller contract this service cannot enforce (G-55, G-56)

The boundary is `(source system, event/correlation identifier)`. Correctness depends on the submitter's
event identifier being unique per event. Nothing here can verify that, and a caller that reuses an
identifier across distinct events will have real notifications discarded, with the audit trail
faithfully recording each discard as correct. The pre-enablement audit in
[migration-phase2.md](./migration-phase2.md) exists because of this.

Full list: [testing.md § Limitations](./testing.md#limitations).

## 3. Components

```
                    ┌──────────────────────────────────────────┐
   source system ──▶│ api/          controllers, DTO validation │
                    └──────────────┬───────────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────────┐
                    │ application/  SubmissionService (1 tx)    │
                    │               StatusQueryService          │
                    │               DeliveryProcessingService   │
                    └──────────────┬───────────────────────────┘
                                   │  depends only on ports
                    ┌──────────────▼───────────────────────────┐
                    │ domain/       model, routing, retry,      │
                    │               dedup, state machines,      │
                    │               ports                       │
                    │               NO framework imports        │
                    └──────────────┬───────────────────────────┘
                                   │  implemented by
        ┌──────────────┬───────────┴────────┬──────────────────┐
        ▼              ▼                    ▼                  ▼
   persistence/    worker/              channel/            audit/
   JdbcClient      OutboxPoller         AbstractChannel-    AuditRecorder
   explicit SQL    DeliveryWorker       Provider + 3 subs
        │              │                    │                  │
        └──────────────┴────────┬───────────┴──────────────────┘
                                ▼
                          PostgreSQL 16
```

The dependency direction is inward: adapters depend on the domain, never the reverse. The domain
declares what it needs as ports and the adapters come to it. This is enforced by
`ArchitectureRulesTest` as a blocking CI gate, not by convention (Principle VII, ADR-002).

### The channel adapter hierarchy

Three providers — email, SMS, push — share one base class. A subclass supplies exactly two things: the
call itself, and a map from provider error code to failure classification.

```
        AbstractChannelProvider           ← taxonomy mapping, diagnostic sanitising,
              ▲       ▲       ▲             fail-closed on an unmapped code
              │       │       │
    Simulated─┘  Simulated─┘  Simulated─┘
    Email        Sms          Push
```

The base fails **closed**: an error code absent from the subclass map becomes `UNKNOWN` rather than
being guessed at, and a subclass that supplies an *empty* map throws at construction rather than
silently classifying everything as unknown. Both behaviours exist because the alternative — a provider
whose failures are quietly misclassified — is invisible until a retry decision goes wrong.

`ChannelExtensibilityTest` asserts a new provider needs only these two members, which is the concrete
form of the refactoring requirement rather than a claim about it.

## 4. Control flow

### Submission — the acceptance transaction

```
POST /api/v1/notifications
  │
  ├─ Jakarta Validation on the DTO ──── fails ─▶ 400 naming EVERY offending field
  │                                              + NOTIFICATION_REJECTED audit record
  │                                              (no notification row is created)
  ▼
  SubmissionService.submit()  ── ONE @Transactional unit ─────────┐
      0. deduplication boundary check      ── duplicate ─▶ 200 OK  │
         (source system, correlation id)      + suppression row    │
         within the window, original            + audit record     │  commit
         not terminally unsuccessful            NO delivery        │
      1. notification_content   (payload, separate table)          │
      2. notification                                             │
      3. recipient rows                                           │
      4. ChannelRouter.route()  → routing_decision                 │
      5. delivery rows, one per SELECTED (recipient, channel)      │
      6. audit events                                             │
      7. outbox row                                               │
  ──────────────────────────────────────────────────────────────┘
  ▼
202 Accepted + Location  ← nothing has been delivered, and no provider was touched
```

**Why one transaction**: the common failure of accept-then-process is acknowledging work that was
never durably recorded, so a status read moments later says "unknown" — the API lying about work it
claimed. Everything the status API will need is committed before the caller is told anything
(Principle II, FR-015).

**Why the boundary check is inside it**: a suppression decided outside the transaction could suppress
and then fail to record, dropping a notification with nothing to show it happened. The decision and
its record commit together or not at all.

**Why a suppression returns 200 and not 202** (ADR-021): `202 Accepted` means accepted for processing,
and a suppressed submission creates no delivery. The status code carries the whole signal, because a
consumer that ignores response fields it does not recognise — which is most consumers — would see 202
and conclude its notification was on its way. Putting the signal in a field would relocate that silent
failure rather than remove it. There is also no `Location` header, because there is no new resource.

### Delivery

```
OutboxPoller.drainOnce()          DeliveryWorker.runOnce()
  claim outbox rows                 claim due deliveries
  FOR UPDATE SKIP LOCKED            FOR UPDATE SKIP LOCKED
  PENDING → QUEUED                  QUEUED | RETRY_SCHEDULED
                                    ── or ──
                                    IN_PROGRESS with an EXPIRED lease   ← reclaim
                                      │
                                      ▼
                          DeliveryProcessingService.process()
                                      │
                              IN_PROGRESS? ──▶ reclaim to QUEUED
                                      │        + DELIVERY_RECLAIMED
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
              expired?          not yet due?      channel in the
              → EXPIRED         → release lease   recorded decision?
                                                  → else UNDELIVERABLE
                                      │
                                      ▼
                          derive IdempotencyKey(notification,
                             recipient, channel, attempt number)
                                      │
                                      ▼
                          ChannelProviderPort.send(recipient,
                                      content, key)
                                      │
                    ┌─────────────────┴─────────────────┐
                    ▼                                   ▼
                 success                             failure
              → DELIVERED                    classify → Retryability
                                                        │
                                      ┌─────────────────┼──────────────┐
                                      ▼                 ▼              ▼
                              retryable +        retryable,      not retryable
                              budget left        budget spent    → FAILED
                              → RETRY_SCHEDULED  → EXHAUSTED     (+ alert if AUTH_ERROR)
```

Eligibility is re-checked immediately before **every** attempt, not once at first processing: a
delivery can cross the not-before or expiry boundary while waiting in backoff (FR-033).

Four things about this flow are easy to get backwards, and each is deliberate:

- **Reclaim runs before the expiry check.** A delivery that was stranded and then expired reaches
  `EXPIRED`, but the reclaim is still recorded on the way. That combination is the case an operator
  most needs to see, and checking expiry first would have discarded the only evidence the delivery was
  ever stuck.
- **Reclaim requires a non-null, expired lease.** A worker actively mid-attempt has its lease cleared,
  so it is never a reclaim candidate. Reclaiming a live attempt would create a genuine concurrent
  double send — turning a loss defect into a duplication defect.
- **The lease outlives the provider read timeout**, 60 s against 10 s, enforced by
  `LeaseExceedsReadTimeoutTest`. A merely slow provider call must not look like a crash. If the lease
  were the shorter of the two, every slow call would be reclaimed mid-flight.
- **`IN_PROGRESS → QUEUED` is a declared transition**, not an exception path. The state machine
  legitimately rejects `IN_PROGRESS → IN_PROGRESS`, and tolerating that quietly is exactly what
  Principle IV forbids.

### The idempotency key

Derived, never stored (ADR-019): SHA-256 over `(notification id, recipient id, channel, attempt
number)`, rendered as `nms-` plus 32 hex characters.

Derivation rather than storage is what makes it survive the window it exists for. A key held in memory
is gone after a crash — precisely when the repeat happens. A key written to a table would need to be
written *before* the call and read back after, adding the same failure window one level down. All four
inputs are already committed before the call, so the key is reproducible by recomputation.

The attempt number is in the key, and that is the whole design:

- A **reclaimed re-attempt** reuses its attempt number, so it carries the **same** key. That is what
  lets a provider recognise it as the same call.
- A **retry** is a new attempt with a new number, so it carries a **different** key. Reusing the key
  would have the provider suppress a legitimate retry, turning every transient failure into a
  permanent one — worse than the duplicate the key prevents.

Because a reclaimed re-attempt reuses its number, and `delivery_attempt` is unique on
`(delivery_id, attempt_number)`, the attempt row is **upserted**. It is the same logical attempt
executed again. Assigning a fresh number to satisfy the constraint would have changed the key and
defeated the deduplication the reclaim exists to make safe.

## 5. Key decisions

Full reasoning in [core ADRs](../specs/001-notification-management-core/research.md) (ADR-001–015) and
[brownfield ADRs](../specs/002-push-dedup-refactor/research.md) (ADR-016–022).

| Decision | Why |
|---|---|
| Java 21 + Spring Boot, Gradle, single project | Meets every Technology Selection criterion. Package boundaries with ArchUnit rather than build modules — the constitution requires a machine-checked rule, and simplicity is its default (ADR-001, ADR-002) |
| PostgreSQL + in-process transactional outbox | The outbox row commits with the entities, so the handoff cannot lose or phantom-create work. `SKIP LOCKED` makes a second instance safe with no coordination. H2 was rejected: its locking differs, so the concurrency test would prove nothing (ADR-003) |
| `JdbcClient`, explicit SQL, no ORM | No dirty-checking exists, so no accidental flush can mutate an append-only audit row. Immutability enforced by absence of a mechanism (ADR-004) |
| Server-issued identity is the retrieval key; a client identifier is descriptive | §4.1 never requires the client identifier to be unique. Deduplication is keyed on the event boundary instead, which is what the source actually describes (ADR-006, spec D4, D9) |
| Two timestamps, not one | §4.1's single "scheduling/expiration timestamp" names two opposite constraints. Split into not-before and expiry (spec D3) |
| Routing policy fixed at startup | Determinism is then trivial: no in-flight submission can straddle two policy versions. No availability target exists to justify hot reload (ADR-014) |
| Simulated providers driven by configuration | A per-request directive would put a test affordance in the production contract and make the simulator an injection surface (ADR-015) |
| Feature flags are `@ConfigurationProperties`, not a flag library | `RetryProperties` and `ChannelProperties` already provide per-deployment switching, which is what gradual rollout means here. A library would add a dependency for capability nobody asked for (ADR-016, G-39) |
| Push has no feature flag; the routing policy's `enabled` is its switch | That setting already exists and already records *why* a channel was not selected. A second switch would create two sources of truth for one question (ADR-017) |
| Flags are not runtime-toggleable | Suppression is irreversible. Flipping mid-flight would suppress across two states of the world with no clean boundary; a restart is the boundary |
| Provider rate limiting maps to `TRANSIENT_PROVIDER_FAILURE` | It is retryable and it is the provider's condition, which is exactly what that classification means. The substantive handling is the per-channel backoff schedule, not a new taxonomy member (ADR-018) |
| Retry schedule is per channel; the retry **bound** is not | A provider may differ in how patiently it backs off. None may opt out of being bounded — §4.5 requires bounded retry, and an unbounded channel would be an unbounded loop wearing a bound's clothes. `MAX_ALLOWED_ATTEMPTS` throws at startup (FR-132) |
| The suppression boundary is an index, not a unique constraint | A constraint would *reject* a duplicate where the requirement is to *suppress* it, and uniqueness across the pair is a caller contract this service cannot enforce (G-55) |
| The retry audit pairing reference is derived, not a foreign key | A key to the audit row would have to be read back before the execution could be recorded, so the pairing would go missing in exactly the runs that went wrong — the runs someone reads the trail to understand |

## 6. State model

The source names no states at all (spec G-09); §4.2 explicitly permits a different model "if it is
documented and defensible". This is that documentation.

**Delivery**: `PENDING → QUEUED → IN_PROGRESS → {DELIVERED | RETRY_SCHEDULED | FAILED | EXHAUSTED}`,
with `IN_PROGRESS → QUEUED` for reclaim, `EXPIRED` reachable from every non-terminal state, and
`UNDELIVERABLE` from `PENDING`. Transition tables in
[data-model.md](../specs/001-notification-management-core/data-model.md); illegal transitions throw
rather than being silently applied.

Four distinctions carry weight:

- **`EXHAUSTED` ≠ `FAILED`** — a flaky provider and an outright rejection both end unsuccessfully but
  mean different things operationally (FR-044).
- **`EXPIRED` is neither** — expiry is not a failure, and outranks both the retry budget and a reclaim
  when a delivery crosses its window while waiting (FR-034, FR-159b).
- **`UNDELIVERABLE`** — routing selected nothing. The source does not address this case (G-20).
- **A reclaim spends budget and does not refund it** (FR-159a) — a provider that crashed the worker on
  every call would otherwise attempt forever while each individual reclaim looked reasonable.

**Notification** state is a deterministic rollup of its deliveries, computed on read. The stored column
is a worker checkpoint; the derived value is authoritative, so status stays truthful even if a worker
died mid-update (FR-016).

## 7. Deduplication

Two independent mechanisms, at two levels, for two different problems. Conflating them is the easiest
mistake to make here.

| | Submission level | Delivery level |
|---|---|---|
| Problem | The same event submitted twice | The same attempt executed twice after a crash |
| Boundary | `(source system, correlation id)` within 24 h | `(notification, recipient, channel, attempt number)` |
| Mechanism | Suppress before creating anything | Send a stable key and let the provider decide |
| Enforced by | This service | The provider, by agreement |
| Flag | `dedup-submission` | `dedup-delivery`, `delivery-reclaim` |
| Reversible | **No** — a suppressed notification was never sent | Yes |

The boundary is a pure function (`DeduplicationDecision`) with a truth-table test, deliberately not
pushed into SQL. Three of its rules would otherwise be invisible:

- The window is **inclusive at its edge**.
- `FAILED` and `EXPIRED` originals do **not** suppress (FR-141c). Suppressing against a terminally
  unsuccessful original would refuse the caller's submission while the thing it collided with never
  arrived — leaving the notification unsent with no path to sending it.
- `PARTIALLY_FAILED` **does** suppress: something was delivered.

The lookup returns the most recent notification for the pair whatever its state, and lets the function
judge. A query that filtered terminal states out instead would look past a recent failure to an older
success and suppress on that.

The 24-hour window is an **assumption** (spec D14), not a stated requirement. No source document gives
a window.

## 8. Privacy design

Principle V is enforced structurally rather than by discipline:

- `AuditRepositoryPort` exposes no update or delete method; `V2__grants.sql` withholds the privileges
  so a raw SQL call cannot do it either.
- Audit payloads are a **sealed** allowlist of thirteen members — none carries the content payload, so
  no code path can add it.
- Content is referenced by SHA-256 digest, never contained (FR-051).
- The recipient reference is masked as prefix-plus-digest in audit, logs and metric labels, but echoed
  unmasked in status responses — that response goes back to the system that supplied it, so masking
  would protect nothing (FR-052).
- Provider diagnostics must be code-shaped (`[A-Za-z0-9_.:-]{1,32}`) or they are redacted. A provider
  echoing the submitted message in its error text is a real leak path, and bounding the length alone
  did not stop it.
- Push credentials are configuration and never reach a response, a log or a metric label. The
  idempotency key is a digest and appears in no table.

`NoSensitiveDataLeakTest` plants markers and scans audit rows, captured logs and metric labels,
including the three record types added in this phase. It is a merge blocker. Because two of those
types exist only when their flag is on, the test enables both flags and asserts the records were
actually written — a scan that finds nothing because nothing happened is not a pass.

## 9. Execution approach

Single deployable. `OutboxPoller` and `DeliveryWorker` run in-process on a fixed delay, and both expose
a public `runOnce()`/`drainOnce()` invoked directly by tests — no test waits on a scheduler, which
would prove timing rather than behaviour.

Horizontal scaling needs no correctness change: both claim work with `FOR UPDATE SKIP LOCKED`, and the
reclaim predicate is lease-based, so a second instance recovers the first instance's stranded work
without coordination. This is designed for but not required — the source states no volume target
(G-11).

Every enhancement in this phase is off by default and switchable per deployment. Enabling and reversing
each one, and what each reversal cannot recover, is in
[migration-phase2.md](./migration-phase2.md). Measured cost is in
[performance-phase2.md](./performance-phase2.md), which asserts no threshold because no source document
states one.
