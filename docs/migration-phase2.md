# Migration and rollback

Four enhancements are switchable independently: the push channel, deduplication at both the submission
and the delivery level, the recovery of deliveries stranded by a crash, and severity-ordered delivery
claiming. This document states how to enable each one, how to reverse it, and — the part that matters
more — **what reversing it cannot recover**.

Two of the four cannot be fully reversed, and they are the two worth reading before enabling anything:
submission deduplication discards notifications permanently, and severity ordering can starve
low-severity deliveries for as long as it is enabled.

## The rollback mechanism

Every enhancement is off by default. `notification.features` in `application.yaml` holds four flags, all
defaulting to `false`. The push channel is the exception: it is switched by the routing policy's existing
per-channel `enabled` setting rather than a flag here (ADR-017), because that setting already exists and
already records *why* a channel was not selected.

```yaml
notification:
  features:
    dedup-submission: false      # suppress duplicate submissions at the event boundary
    dedup-delivery: false        # send a stable idempotency key on every provider call
    delivery-reclaim: false      # recover deliveries stranded mid-attempt
    severity-claim-order: false  # claim higher-severity deliveries first
```

The flags are **not runtime-toggleable**, and that is deliberate rather than an omission. Suppression is
irreversible: a notification suppressed while deduplication was on was never delivered and cannot be
recovered. Flipping the flag mid-flight would suppress across two states of the world with no clean
boundary between them, so a restart is the boundary.

Rolling back is therefore: set the flag to `false`, restart. There is no down-migration, and there
does not need to be — see the next section.

## The database migrations are forward-only, and are safe to leave in place

Severity ordering adds **no migration at all** — it reads severity through a join rather than
denormalising a column onto `delivery` (ADR-026), so there is nothing schema-level to reverse.

| Migration | Adds | Reversal |
|---|---|---|
| `V5__deduplication.sql` | `notification_suppression` table; index on `notification (source_system, correlation_id, received_at)` | None required. Nothing reads the table when the flag is off. |
| `V6__delivery_reclaim.sql` | Partial index on `delivery (claimed_until) WHERE state = 'IN_PROGRESS'` | None required. Nothing queries it when the flag is off. |

Neither migration alters or drops an existing column, and neither adds a constraint that could reject
a write the previous version accepted. `V5` in particular adds an **index**, not a unique constraint,
on the deduplication boundary — a unique constraint would *reject* a duplicate submission where the
requirement is to *suppress* it, and uniqueness across that pair is a caller contract this service
cannot enforce anyway.

So a rolled-back deployment runs against the migrated schema, with the new table sitting unread. That
is the intended steady state during a rollback, not a leftover to clean up. Dropping the table would
destroy the record of what was suppressed while the flag was on — which is the one thing a rollback
most needs to leave intact.

## Rollback procedures, and what each cannot recover

### Push channel

**Enable**: set `enabled: true` for `PUSH` in the routing policy.
**Roll back**: set `enabled: false`. Restart.

**Cannot recover**: nothing. Deliveries already sent to push providers stay sent. In-flight push
deliveries continue to their terminal state, because the policy governs channel *selection*, not
attempts on deliveries already created. Notifications submitted after the rollback route to the
remaining channels exactly as they did in phase 1.

This is the cleanest of the three because it is additive: with push disabled, a push outcome is still
*recorded* as not-selected with a reason, so the audit trail says why rather than going silent.

### Delivery-level deduplication (idempotency key and reclaim)

**Enable**: `dedup-delivery: true`, then `delivery-reclaim: true`. **In that order** — see below.
**Roll back**: `delivery-reclaim: false` first, then `dedup-delivery: false`. Restart.

**Cannot recover**: a delivery already stranded in `IN_PROGRESS` at the moment reclaim is disabled
stays stranded, permanently. That is the pre-existing defect B-13, and disabling reclaim restores it
rather than causing it. There is no automated sweep; such a delivery needs manual intervention.

**Order is load-bearing in both directions.** Enabling reclaim without the idempotency key trades a
silent stall for a silent duplicate: reclaim causes a second provider call for an attempt that may
already have succeeded, and without the key the provider has no way to recognise it as a repeat
(G-58). Disabling in reverse order keeps the key present for as long as anything can reclaim.

### Submission-level deduplication

**Enable**: `dedup-submission: true`. Restart. **Do not enable this for a caller before auditing
their event-identifier usage** — see the mandatory pre-condition below.

**Roll back**: `dedup-submission: false`. Restart.

**Cannot recover — state this to stakeholders before enabling, not after:**

> **Notifications suppressed while deduplication was on were never delivered, and rolling back does
> not deliver them.** There is no queue of held submissions to replay. Suppression is a decision not
> to create a notification at all, so there is nothing to release.

What a rollback *does* leave is the evidence: every suppression wrote a row to
`notification_suppression` and a `NOTIFICATION_SUPPRESSED` audit record naming the original
notification it collided with. After a rollback, that is the list of what did not get sent:

```sql
SELECT client_notification_id, source_system, correlation_id, suppressed_at,
       original_notification_id
FROM notification_suppression
WHERE suppressed_at >= :flag_enabled_at
ORDER BY suppressed_at;
```

Resubmission is the caller's decision and the caller's action. This service will not replay them,
because it cannot know whether the original delivery served the caller's purpose.

## Mandatory pre-condition: audit the caller's event identifiers first

The deduplication boundary is `(source system, event/correlation identifier)`. Correctness rests
entirely on an assumption the service **cannot verify**: that a submitter's event identifier is unique
per event within that submitter (spec G-55, G-56).

If a caller reuses an identifier across genuinely distinct events — a fixed string, a daily batch
label, a truncated hash — then enabling deduplication for them silently discards real notifications,
and the audit trail will faithfully record each discard as correct behaviour. Nothing in this service
can distinguish that from the case it is designed for.

**Therefore, before enabling `dedup-submission` for any source system:**

1. Confirm with the owning team, in writing, that their event identifier is unique per event.
2. Verify it against their traffic. This query finds identifiers already reused inside the window:

   ```sql
   SELECT source_system, correlation_id, count(*) AS submissions,
          min(received_at) AS first_seen, max(received_at) AS last_seen
   FROM notification
   WHERE received_at >= now() - interval '24 hours'
   GROUP BY source_system, correlation_id
   HAVING count(*) > 1
   ORDER BY count(*) DESC;
   ```

   Every row is a submission that **would have been suppressed** had the flag been on. Review each
   with the owning team. A row that represents a genuine retry is the feature working. A row that
   represents two distinct events is a blocker.
3. Only then enable the flag, for that source system's traffic.

The 24-hour window in the query matches the configured `notification.dedup.window`, which is an
assumption (spec D14), not a stated requirement. If that window is changed, change this query with it.

## Severity-ordered delivery claim (feature 003)

**Enable**: `notification.features.severity-claim-order: true`. Restart.
**Roll back**: set it to `false`. Restart.

Higher-severity deliveries are claimed and attempted before lower-severity ones. Within equal severity
the existing oldest-first order is unchanged, so severity refines the order rather than replacing it.

### Cannot recover — and unlike the others, this one is ongoing rather than historical

> **Low-severity deliveries can be starved without bound.** Under a sustained stream of higher-severity
> traffic a `LOW` delivery may remain eligible and unclaimed **indefinitely**. This is decision D-15,
> taken deliberately: severity means what it says, and a `LOW` notification that never arrives during an
> incident is the accepted trade. FR-207 forbids adding an age-based override to cap it.

Two operator consequences follow, and both are the kind of thing that gets misdiagnosed:

**An aged `LOW` delivery sitting unclaimed is correct behaviour, not a stalled worker.** Gap G-64 records
that nothing in the system distinguishes the two: claim order is invisible in every API response (G-63)
and no starvation metric is in scope. If you see a `LOW` backlog that is not draining while high-severity
work flows, the worker is healthy and the feature is working.

**The flag is the only mitigation.** Because starvation is uncapped, there is no timer that will
eventually release the backlog. Setting `severity-claim-order: false` restores the age-only order and the
starved deliveries are claimed on the next run — verified, not assumed:
`SeverityStarvationTest.Disabled` asserts exactly that.

To see what is being starved before deciding:

```sql
SELECT n.severity, count(*) AS due, min(d.state_changed_at) AS oldest
FROM delivery d
JOIN notification n ON n.id = d.notification_id
WHERE d.state IN ('QUEUED', 'RETRY_SCHEDULED')
  AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= now())
GROUP BY n.severity
ORDER BY min(d.state_changed_at);
```

A growing `due` count against an `oldest` that keeps receding for the lower severities is starvation.
That is the signal the system does not give you, which is why the query is here.

### Recovery is not expedited

Decision D-16: reclaimed deliveries — those recovered after a worker or provider crash (FR-159) —
participate in severity ordering on the same terms as anything else. They hold **no** priority as
recovery work.

So a crashed `LOW` delivery waits behind freshly-arrived `CRITICAL` traffic, and during an incident that
means the crashed one may not be retried for as long as the incident lasts. The converse also holds and
is the reason the decision is not simply "reclaims go last": a crashed `CRITICAL` delivery is still
recovered ahead of fresh `LOW` work.

### What rollback does recover

Everything else. No notification is discarded by this feature, nothing is written that a rollback leaves
behind, and no schema object was added — there is no migration for this feature at all. Ordering changes
which eligible delivery is taken next and nothing about which deliveries are eligible (FR-204).

### Rollout note

The flag can be enabled independently of the three phase-2 flags; it shares no state with them. Enable it
when a severity-aware queue is wanted **and** an unbounded `LOW` backlog is acceptable. If the second
condition does not hold, do not enable it — there is no partial setting, and FR-207 forecloses adding
one.

## What the service cannot promise about provider-side deduplication

The idempotency key makes a repeated provider call *recognisable*. It does not make the provider
*recognise* it. Whether a repeat is actually suppressed is the provider's behaviour, governed by an
agreement between this service and that provider (spec D12, FR-162).

**This is assumed, not implemented, and not verified by any test here.** A test can assert that the
key is stable and reproducible across a crash — and does — but no test in this repository can assert
that a provider honours it. Before relying on it for a given provider, confirm the provider actually
deduplicates on the key, and record that confirmation. Absent that, reclaim can produce a duplicate
delivery, which is exactly the exposure G-58 names.

## Rollback verification

Each rollback path has been executed, not merely described. A procedure that has never been run is a
guess (SC-114).

Run: `2026-09-10`, commit `a9e305e`, against `postgres:16-alpine` with `V1`–`V6` applied.

| Rollback path | Executed by | Tests | Result |
|---|---|---|---|
| All flags off equals the shipped default | `FlagsOffBaselineTest` | 4 | pass |
| All flags off equals phase-1 behaviour end to end | `ExistingChannelsUnchangedTest` | 4 | pass |
| `dedup-submission: false` | `DedupDisabledTest` | 2 | pass |
| `dedup-submission: false`, phase-1 duplicate semantics intact | `DuplicateSubmissionTest` | 3 | pass |
| `delivery-reclaim: false` | `ReclaimDisabledTest` | 1 | pass |
| Push disabled via routing policy | `PushDisabledTest` | 3 | pass |
| `severity-claim-order: false` restores the age-only claim | `ClaimOrderUnchangedTest` | 2 | pass |
| `severity-claim-order: false` releases a starved backlog | `SeverityStarvationTest.Disabled` | 1 | pass |
| Ordering never changes which deliveries are eligible | `ClaimEligibilityUnchangedTest` | 2 | pass |

**22 tests, 0 failures, 0 errors.**

Two of these assert something that reads oddly and is intended. `ReclaimDisabledTest` asserts that a
stranded delivery *stays* stranded, and `DuplicateSubmissionTest` asserts that a duplicate submission
*is* accepted twice. Both are assertions that a defect and an old behaviour are faithfully preserved
when the flag is off — which is what makes the flag a rollback mechanism rather than a setting. If
either enhancement leaked past its flag, the rollback path would not exist and these are the tests
that would say so.

Reproduce with:

```bash
./gradlew test --tests 'com.notification.regression.FlagsOffBaselineTest' \
               --tests 'com.notification.regression.ExistingChannelsUnchangedTest' \
               --tests 'com.notification.integration.DedupDisabledTest' \
               --tests 'com.notification.integration.ReclaimDisabledTest' \
               --tests 'com.notification.integration.PushDisabledTest' \
               --tests 'com.notification.integration.DuplicateSubmissionTest'
```

## Recommended rollout order

Ordered by how reversible each step is, most reversible first.

1. **Push channel.** Additive, cleanly reversible, recovers nothing on rollback because it loses
   nothing.
2. **`dedup-delivery`.** Adds a key to provider calls and changes nothing else. Nothing consumes it
   yet.
3. **`delivery-reclaim`.** Only after step 2 is in place everywhere. This step fixes B-13 and is the
   one that needs provider-side deduplication to be real.
4. **`severity-claim-order`.** Fully reversible in the sense that nothing is destroyed, but it holds an
   ongoing hazard while enabled: a low-severity backlog can grow without bound and nothing signals it.
   Enable only if that is acceptable — there is no partial setting, and FR-207 forecloses adding one.
5. **`dedup-submission`, per source system, after the identifier audit above.** Last, because it is the
   only step whose effect cannot be undone at all: what it discarded stays discarded.
