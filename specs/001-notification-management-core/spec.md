# Feature Specification: Notification Management Core

**Feature Branch**: `feature/phase-1-greenfield`

**Created**: 2026-09-07

**Status**: Draft — all clarifications resolved 2026-09-07 (D1–D5);
1 recorded deviation from an explicit source requirement (G-26); planning in progress

**Input**: User description: "refer Greenfield_Requirements.pdf and derive the specification from the requirement document. If something is missing, ambiguous, contradictory, or necessary for implementation but not specified: do NOT silently decide it. Record it explicitly as an assumption, gap, or unresolved decision. Explain which source requirement creates the gap. Do not convert an implementation preference into a functional requirement. Distinguish clearly between explicit requirement, direct consequence of an explicit requirement, design-derived behavior, assumption/judgment, and out-of-scope behavior."

**Source of record**: `Greenfield_Requirements.pdf`, sections 3–7. Every statement in this
specification carries a provenance tag. Nothing here is silently invented.

## Provenance Legend

Every requirement, scenario, and criterion below is tagged with exactly one of:

| Tag | Meaning | Authority |
|-----|---------|-----------|
| **[E]** | **Explicit requirement** — stated in the source document, cited by section | Binding. Cannot be changed without a requirement change. |
| **[C]** | **Direct consequence** — logically forced by an [E] requirement; denying it would contradict the source | Binding. The derivation is stated so it can be challenged. |
| **[D]** | **Design-derived behavior** — a choice made to render an [E] requirement coherent or observable; reasonable alternatives existed | Changeable by design decision. The rejected alternative is named. |
| **[A]** | **Assumption / judgment** — not derivable from the source; adopted so work can proceed | Requires confirmation. Every [A] is listed in the Gap Register. |
| **[X]** | **Out of scope** — deliberately excluded, with the reason | Excluded until a requirement change says otherwise. |

**Rule applied throughout**: implementation preferences (technology, storage mechanism,
transport, internal structure) are NOT expressed as functional requirements. They belong to
`/speckit-plan`. Where an [E] requirement forces observable behavior, the behavior is stated;
the mechanism is not.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Submit a notification and receive a decision (Priority: P1)

**[E — source §3.1 "Notification submission", §4.1]**

A source system sends a notification request describing what happened, who should be told, and
over which channels. It immediately learns whether the request was accepted for processing or
rejected, and if rejected, exactly why.

**Why this priority**: Nothing else in the capability exists without an accepted notification.
It is the entry point named first in the scope section, and rejection handling is the only
protection the downstream pipeline has against malformed input.

**Independent Test**: Submit a well-formed request and confirm an acceptance carrying a
server-issued identity. Submit requests each missing one mandatory element and confirm each
is rejected with the offending element named. Requires no delivery machinery to test.

**Acceptance Scenarios**:

1. **[E]** **Given** a request carrying notification identifier, source system, event or
   correlation identifier, notification type, severity, priority, at least one recipient,
   requested or eligible channels, and a creation timestamp — plus **[A ← D1]** a content
   payload — **When** it is submitted, **Then** it is accepted for processing and the caller
   receives a server-issued identity by which status can be retrieved **[A ← D4]**.
2. **[C — from §4.1 "One or more recipients"]** **Given** a request with an empty recipient
   list, **When** it is submitted, **Then** it is rejected and the reason identifies the
   recipient list.
3. **[C — from §4.9, which names "Notification accepted/rejected" as a recorded action,
   establishing rejection as a real outcome]** **Given** a request missing any mandatory
   element, **When** it is submitted, **Then** it is rejected, every offending element is
   named in the response, and no delivery is created for it.
4. **[C — from §3.1 "Asynchronous processing"]** **Given** any accepted request, **When** the
   caller receives the acceptance, **Then** the acceptance does not report any delivery
   outcome, because no delivery has been attempted yet.
5. **[A ← D4]** **Given** two submissions carrying the same client notification identifier,
   **When** both are submitted, **Then** both are accepted, they receive different server-issued
   identities, and each has its own deliveries and its own audit history.

---

### User Story 2 - Retrieve the status of a notification (Priority: P2)

**[E — source §3.1 "Status retrieval", §4.2]**

An operator or the originating source system asks what happened to a notification and sees the
overall status, which channels were selected, the delivery status broken down by recipient and
by channel, and the timestamps of what occurred.

**Why this priority**: §4.2 makes this an explicitly required capability, and it is the only
way an asynchronous outcome becomes observable. Together with User Story 1 it forms the
smallest demonstrable end-to-end slice.

**Independent Test**: Submit a notification, then retrieve its status and assert all four
required elements are present, including one row per recipient-and-channel combination.

**Acceptance Scenarios**:

1. **[E]** **Given** an accepted notification, **When** its status is retrieved, **Then** the
   response contains the overall notification status, the selected channels, a delivery status
   for each recipient and channel combination, and the relevant timestamps.
2. **[C — from §4.2 "Delivery status by recipient and channel"]** **Given** a notification
   with 2 recipients and 2 selected channels, **When** its status is retrieved, **Then** 4
   distinct delivery statuses are visible and can differ from one another.
3. **[C — from §3.1 asynchrony combined with §4.2 status retrieval: a status that could not
   answer for an acknowledged notification would make acceptance meaningless]** **Given** a
   notification that has just been accepted and not yet processed, **When** its status is
   retrieved immediately, **Then** it is found and reports a not-yet-delivered state rather
   than "unknown".
4. **[D]** **Given** a server-issued identity that was never issued, **When** status is
   retrieved, **Then** the response distinguishes "no such notification" from "notification
   exists with no completed deliveries". *(Design-derived: the source does not address unknown
   identifiers; conflating the two cases would make debugging ambiguous.)*

---

### User Story 3 - Select delivery channels for each recipient (Priority: P3)

**[E — source §3.1 "Recipient and channel selection", §4.3 — scoped by D2]**

The system decides which channels will actually be used for each recipient, taking into account
what the sender requested, how severe the notification is, and the governing routing policy —
and records that decision so it can be explained later.

**Scope deviation**: §4.3 also names recipient preferences as a factor. They are excluded from
this iteration by owner decision (D2), so this story delivers three of the source's four
factors.

**Why this priority**: §4.2 requires "selected channels" to be retrievable, so a recorded
routing decision is a precondition for a complete status response.

**Independent Test**: Drive the selection with a table of inputs (requested channels, severity,
policy) and assert the selected channel set and the recorded reasons for each input combination,
without performing any delivery.

**Acceptance Scenarios**:

1. **[A ← D2]** **Given** a notification with requested channels, a severity, and an active
   routing policy, **When** channel selection runs, **Then** the delivery channels are
   determined from those three factors and from nothing else.
2. **[C — from §4.2 "Selected channels" being retrievable after the fact]** **Given** channel
   selection has run, **When** the notification's status is retrieved at any later time,
   **Then** the same selected channels are reported.
3. **[D]** **Given** the routing policy changes after a notification was accepted, **When**
   that notification's status is retrieved, **Then** the originally recorded selection is
   unchanged and the decision still names the policy version that produced it.
   *(Design-derived: the source is silent; recomputing selection at read time would make the
   audit record of §4.9 "Routing decision made" unverifiable.)*
4. **[D]** **Given** channel selection excludes a channel that was requested, **When** the
   routing decision is examined, **Then** the reason for the exclusion is recorded.
   *(Design-derived: §4.9 requires recording that a routing decision was made; a decision
   without its reason cannot be defended, per §6 "clarity and defensibility of decisions".)*
5. **[D]** **Given** selection yields no eligible channel for a recipient, **When** the status
   is retrieved, **Then** that recipient shows a distinct undeliverable outcome rather than a
   perpetually pending one. *(Design-derived: the source does not address the empty-selection
   case. See Gap G-20.)*
6. **[A ← D2]** **Given** any recipient, **When** channel selection runs, **Then** no stored
   preference of that recipient affects the outcome — verified by running identical selections
   against recipients holding differing preference data and asserting identical results.

---

### User Story 4 - Attempt delivery asynchronously (Priority: P4)

**[E — source §3.1 "Asynchronous processing" and "Delivery attempts"]**

After a notification is accepted, the system attempts delivery to each selected recipient and
channel on its own time, without the submitting system waiting, and the outcome of each attempt
becomes visible through status retrieval.

**Why this priority**: This is the capability the whole flow exists to perform; it depends on
Stories 1 and 3 having produced an accepted notification with a recorded selection.

**Independent Test**: Accept a notification, allow processing to run, and assert each delivery
reaches a terminal outcome that is visible in status, with the submission having returned
before any attempt occurred.

**Acceptance Scenarios**:

1. **[E]** **Given** an accepted notification, **When** processing runs, **Then** a delivery
   attempt is made for each selected recipient and channel combination.
2. **[C — from §3.1 "Asynchronous processing"]** **Given** a submission is in progress,
   **When** the acceptance is returned, **Then** no delivery attempt has yet been made and the
   caller was not made to wait for one.
3. **[C ← FR-003a]** **Given** a notification carrying a not-before time that has not arrived,
   **When** processing runs, **Then** no delivery attempt is made; **and Given** that time has
   since passed, **When** processing runs, **Then** the notification is eligible for attempt.
4. **[C ← FR-003b]** **Given** a notification whose expiry time has passed, **When** processing
   would otherwise attempt delivery, **Then** no attempt is made.
5. **[D]** **Given** a notification expires while a delivery is waiting between retries,
   **When** the next attempt would be due, **Then** it is abandoned and the delivery reaches a
   distinct expired outcome. *(Design-derived governing rule: expiration overrides remaining
   retry budget. §4.1 and §4.5 both apply here and the source does not state which wins;
   attempting after expiry would violate §4.1, so expiration is given precedence.)*

---

### User Story 5 - Retry retryable failures within a bound (Priority: P5)

**[E — source §4.5]**

When a delivery fails for a reason that might succeed later, the system tries again a limited
number of times; when it fails for a reason that will never succeed, it stops immediately. The
distinction between failure kinds is recorded.

**Why this priority**: §4.5 is an explicit requirement and is the principal source of
reliability risk, but it is only exercisable once Story 4 produces attempts.

**Independent Test**: Drive a simulated provider to produce each of the five named failure
kinds and assert, per kind, whether a retry was scheduled, how many attempts occurred, and the
terminal outcome — with no dependence on real timing.

**Acceptance Scenarios**:

1. **[E]** **Given** a delivery fails with a retryable failure, **When** the retry strategy
   applies, **Then** the delivery is retried, and the number of attempts never exceeds the
   declared bound.
2. **[E]** **Given** a delivery failure, **When** it is recorded, **Then** it is classified as
   one of: transient provider failure, permanent provider rejection, invalid recipient,
   timeout, or authentication/authorization error.
3. **[C — from §4.5 "bounded"]** **Given** a delivery has exhausted its retry bound, **When**
   the last attempt fails, **Then** no further attempt is made and the delivery reaches a
   terminal outcome visible in status.
4. **[D — see Gap G-07]** **Given** a permanent provider rejection, an invalid recipient, or an
   authentication/authorization error, **When** it occurs, **Then** no retry is scheduled.
   *(Design-derived: §4.5 says retry applies to "retryable delivery failures" and names five
   kinds, but never states which kinds are retryable. This partition is the proposed reading.)*
5. **[D]** **Given** a delivery fails with an authentication or authorization error, **When**
   it is recorded, **Then** it is additionally surfaced as an operational condition.
   *(Design-derived: this class indicates a service-side configuration fault rather than a
   recipient fault, so silently consuming it as a per-recipient failure would hide an outage.)*
6. **[D]** **Given** a provider returns an outcome that matches none of the five named kinds,
   **When** it is recorded, **Then** it is classified as unknown and is never treated as
   success. *(Design-derived: §4.5's list is closed as written but real providers are not.
   Failing closed is the conservative reading.)*

---

### User Story 6 - Reconstruct what happened from audit history (Priority: P6)

**[E — source §4.9]**

An operator or auditor can see the significant actions taken for a notification — acceptance or
rejection, the routing decision, queuing, attempts, successes, failures, and scheduled retries
— without that history exposing sensitive message content or credentials.

**Why this priority**: §4.9 is explicit and spans every other story; it is sequenced last
because it records the actions those stories produce.

**Independent Test**: Run one notification through acceptance, routing, a failing attempt, a
retry, and a terminal outcome; assert every named action type appears in its history, and
assert no forbidden value appears anywhere in it.

**Acceptance Scenarios**:

1. **[E]** **Given** a notification's lifecycle, **When** its audit history is examined,
   **Then** it records: accepted or rejected, routing decision made, delivery queued, delivery
   attempted, delivery succeeded, delivery failed, and retry scheduled — as applicable.
2. **[E]** **Given** any audit record, **When** it is examined, **Then** it contains no
   unnecessary sensitive message content and no credentials.
3. **[C — from §4.1 "Event or correlation identifier" combined with §4.9's history purpose]**
   **Given** a correlation identifier, **When** history is queried by it, **Then** the complete
   sequence of actions for the related notification is retrievable.
4. **[C — from §4.2 "Relevant timestamps"]** **Given** any recorded action, **When** it is
   examined, **Then** it carries the time at which it occurred.
5. **[D]** **Given** a recorded action, **When** any later correction occurs, **Then** the
   original record is not altered or removed. *(Design-derived: §4.9 calls this "Audit
   History"; a rewritable history is not a history. The source does not state immutability.)*
6. **[D]** **Given** delivery expired or retry-budget-exhausted occurs, **When** history is
   examined, **Then** those actions are recorded too. *(Design-derived, and permitted: §4.9
   introduces its list with "such as", making the list non-exhaustive.)*

---

### Edge Cases

Each is tagged by whether the source addresses it.

- **[C]** Submission with zero recipients → rejected (§4.1 requires one or more).
- **[D]** Routing selects zero channels for a recipient → distinct undeliverable outcome, not
  perpetual pending. Source silent (G-20).
- **[D ← D3]** Notification submitted with an expiry already in the past → rejected at
  submission rather than accepted and immediately expired (FR-003e).
- **[C ← D3]** Not-before time at or after the expiry time → rejected; the delivery window can
  never open (FR-003d). Reachable because D3 resolved §4.1's single field into two.
- **[C ← D3]** Not-before time set, expiry absent → valid; the notification waits, then remains
  eligible indefinitely (FR-003c).
- **[A ← G-29]** Processing is delayed well past a not-before time → still attempted; the
  not-before time is a floor, not a punctuality guarantee. No lateness bound is promised.
- **[D]** Expiration passes while a delivery waits between retries → abandoned as expired
  (US4 scenario 5).
- **[C]** One recipient succeeds on one channel while another fails on another → both are
  independently visible, because §4.2 requires per-recipient-and-channel status.
- **[D]** Status requested for an unknown identifier → distinguishable from a known
  notification with no completed deliveries.
- **[D]** Provider returns an unclassifiable outcome → recorded as unknown, never as success.
- **[C ← D5]** A channel provider cannot resolve or accept a recipient reference → reported by
  the provider as `INVALID_RECIPIENT` for that channel only, leaving the recipient's other
  channels unaffected. Recipient validity is a delivery-time provider verdict (§4.5), not a
  submission-time schema check.
- **[A ← D4]** Same client notification identifier submitted twice → **both accepted**, becoming
  two independent notifications with distinct server-issued identities (FR-008b). This is not
  deduplication and not idempotency; those remain deferred (G-12).
- **[A]** Creation timestamp supplied by the caller is implausible (far future or far past) →
  recorded as supplied, alongside a system-observed receipt time. Source silent (G-21).
- **[D ← D1]** Submission carries no content payload, or an empty one → rejected (FR-058, G-23).
- **[A ← D1]** Content payload exceeds the declared maximum size → rejected at submission,
  before any delivery is created (FR-059, G-24).
- **[C ← D1]** A delivery fails and its failure is recorded → the recorded failure carries the
  classification and the delivery's identity but no fragment of the content payload, including
  in any provider-supplied error text (FR-056).

## Requirements *(mandatory)*

### Functional Requirements

#### Submission (source §4.1)

- **FR-001 [E §4.1]**: System MUST provide a capability that accepts a notification request.
- **FR-002 [E §4.1]**: A notification MUST carry a notification identifier, a source system, an
  event or correlation identifier, a notification type, a severity, a priority, one or more
  recipients, requested or eligible channels, and a creation timestamp.
- **FR-003 [E §4.1]**: A notification MAY carry a scheduling/expiration timestamp; its absence
  MUST be valid. *(Source text retained verbatim. §4.1 writes this as one field naming two
  opposite constraints; D3 resolves it into the two below.)*
- **FR-003a [A ← D3]**: A notification MAY carry a **not-before** timestamp, meaning delivery
  MUST NOT be attempted before that time.
- **FR-003b [A ← D3]**: A notification MAY carry an **expiry** timestamp, meaning delivery MUST
  NOT be attempted after that time.
- **FR-003c [C ← FR-003a + FR-003b]**: Each of the two timestamps MUST be independently
  optional. All four combinations — neither, either alone, both — MUST be valid inputs.
- **FR-003d [C ← FR-003a + FR-003b]**: A submission whose not-before time is at or after its
  expiry time MUST be rejected, because it describes a delivery window that can never open.
- **FR-003e [D]**: A submission whose expiry time has already passed MUST be rejected rather
  than accepted and immediately expired. *(Design-derived: the source is silent. Accepting work
  that provably cannot be performed wastes a delivery record and makes the expired terminal
  state ambiguous between "expired in flight" and "expired on arrival".)*
- **FR-004 [C ← FR-002]**: System MUST reject a submission that omits any element required by
  FR-002, and the rejection MUST identify every offending element.
- **FR-005 [C ← §4.1 "one or more recipients"]**: System MUST reject a submission whose
  recipient list is empty.
- **FR-006 [C ← §4.9 "Notification accepted/rejected"]**: Every submission MUST resolve to
  exactly one of two outcomes, accepted or rejected, and both MUST be recorded.
- **FR-007 [C ← FR-006 + §4.2]**: A rejected submission MUST NOT produce any routing decision,
  delivery, or delivery attempt.
- **FR-008 [A ← D4, revised 2026-09-07]**: The acceptance response MUST carry a **server-issued
  notification identity**, and that identity MUST be the key by which status is retrieved.
  *(Supersedes the original design-derived FR-008, which made §4.1's client-supplied identifier
  the retrieval key. That reading became untenable once D4 allowed duplicate client identifiers:
  a non-unique key cannot address a single notification.)*
- **FR-008a [C ← FR-008 + FR-002]**: The client-supplied notification identifier of §4.1 MUST
  still be stored, returned, and searchable. It is descriptive, not identifying.
- **FR-008b [C ← D4]**: Two submissions carrying the same client-supplied notification identifier
  MUST both be accepted and MUST become two independent notifications with distinct server-issued
  identities, distinct deliveries, and distinct audit histories.
- **FR-009 [A]**: System MUST validate notification type, severity, priority, and channel
  values against closed, configured value sets. *(Assumption: the source names these fields but
  enumerates no values — G-18. Without closed sets, FR-004 and routing are untestable.)*

#### Message content (source §4.9, via resolved Q1)

*Provenance note: §4.1 lists no content field. These requirements exist because §4.9 forbids
storing "sensitive message content", which presupposes content exists, and because the
requirement owner resolved that contradiction on 2026-09-07 in favour of an opaque
source-supplied payload (G-02, Q1 option A). They are owner decisions, not source text.*

- **FR-055 [A ← Q1]**: A notification MUST carry a message content payload supplied by the
  source system. The system MUST treat that payload as opaque — it MUST NOT parse, interpret,
  transform, or render it.
- **FR-056 [C ← FR-055 + §4.9]**: The content payload MUST be classified as sensitive
  throughout its lifetime, and MUST NOT appear in any audit record, operational output, or
  status response.
- **FR-057 [C ← FR-055 + FR-012]**: The content payload MUST be conveyed to each selected
  delivery channel unchanged, so that identical content reaches every channel for a recipient.
- **FR-058 [D]**: A submission whose content payload is absent or empty MUST be rejected.
  *(Design-derived: §4.1 does not list content among its mandatory fields, so treating it as
  mandatory is a decision, not a reading of the source. Alternative rejected: optional content,
  which permits accepting a notification that cannot produce a meaningful delivery and makes
  §4.9's content-minimisation rule vacuous for those notifications. See residual gap G-23.)*
- **FR-059 [A]**: The content payload MUST have a declared maximum size, and a submission
  exceeding it MUST be rejected. *(Assumption: the source states no bound. An unbounded
  caller-supplied payload is a stability risk — G-24.)*

#### Status retrieval (source §4.2)

- **FR-010 [E §4.2]**: System MUST provide a capability to retrieve the overall notification
  status.
- **FR-011 [E §4.2]**: The status MUST report the selected channels.
- **FR-012 [E §4.2]**: The status MUST report delivery status broken down by recipient and by
  channel.
- **FR-013 [E §4.2]**: The status MUST report the relevant timestamps.
- **FR-014 [C ← FR-012]**: System MUST track and retain delivery state at the granularity of a
  single recipient-and-channel pair; a per-notification-only state is insufficient.
- **FR-015 [C ← §3.1 asynchrony + FR-010]**: Any notification that has been accepted MUST be
  retrievable through status retrieval from the moment of acceptance onward. *(Stated as
  observable behavior; the mechanism that guarantees it is a planning concern, not a
  requirement.)*
- **FR-016 [D]**: The overall notification status MUST be a deterministic and documented
  function of its per-recipient-and-channel delivery states. *(Design-derived: §4.2 requires an
  overall status and per-pair statuses without relating them. §4.2 explicitly permits "a
  different state model if it is documented and defensible", which is the licence used here.)*
- **FR-017 [A]**: The state vocabulary — for both the overall notification and each delivery —
  MUST be an explicit, closed, documented set. *(Assumption: the source names no states at all
  — G-09. The proposed vocabulary is recorded in Assumptions, not fixed here.)*
- **FR-018 [D]**: A status request for an unknown server-issued identity MUST be
  distinguishable from one for a known notification with no completed deliveries.

#### Channel routing (source §4.3)

> **DEVIATION NOTICE (D2).** §4.3 names **four** routing factors: requested channel, notification
> severity, **recipient preferences**, and routing policy. This iteration implements **three**,
> excluding recipient preferences (owner decision, 2026-09-07).
>
> The exclusion is forced by the source itself: §4.3 names preferences as an input, but §4.1's
> submission fields do not carry them and no section of §3–§5 defines a preference store, owner,
> or shape. **§4.3 requires an input the document never makes obtainable** (G-05). Implementing it
> would mean inventing both a data source and a preference model.
>
> This specification therefore does **not** satisfy §4.3 in full. Recorded as G-26; MUST be
> declared in DO-002 and DO-004. See D2.

- **FR-019 [E §4.3 — NOT FULLY IMPLEMENTED THIS ITERATION]**: The source requires that delivery
  channels be determined using the requested channel, the notification severity, recipient
  preferences, and routing policy. Recorded here in full so the unmet portion stays visible. The
  preference factor is unimplementable as the source stands, because no submission field carries
  preferences and no preference source is defined anywhere in the document (G-05).
- **FR-019a [A ← D2, owner-scoped]**: In this iteration, System MUST determine delivery channels
  using the requested channel, the notification severity, and the configured routing policy.
  Recipient preferences MUST NOT influence channel selection, and MUST NOT be silently
  substituted by any proxy — a per-recipient channel list, a suppression flag, or a quiet-hours
  attribute introduced under another name would implement §4.3 partially while reporting it as
  deferred (G-26).
- **FR-020 [C ← FR-019a]**: Because the three factors of FR-019a can disagree for the same
  recipient, a single deterministic precedence among them MUST exist and MUST be documented.
- **FR-020a [D ← D2]**: That precedence is: the configured routing policy is the deciding
  authority; the requested channels and the severity are inputs the policy consults. A requested
  channel is therefore a proposal the policy may decline, not an instruction it must honour.
  *(Design-derived: the owner's answer names the three factors but not their ordering. Treating
  policy as authority is the only reading under which "routing policy" is a factor rather than a
  synonym for the other two. Alternative rejected: requested channel binding unless policy
  forbids it, which makes policy a veto rather than a decision-maker and leaves severity with no
  defined role. See residual gap G-27.)*
- **FR-021 [C ← FR-011]**: The channel selection outcome MUST be recorded at decision time and
  MUST be reported unchanged by later status retrievals.
- **FR-022 [D]**: The recorded routing decision MUST include, for each candidate channel,
  whether it was selected or excluded and why. *(Design-derived: §4.9 requires recording that a
  routing decision was made, and §6 requires decisions to be defensible; a decision recorded
  without its reason satisfies neither.)*
- **FR-023 [D]**: Routing MUST be repeatable — the same inputs MUST always yield the same
  selection. *(Design-derived: required to make FR-021 and the §4.9 audit record verifiable.)*
- **FR-024 [D]**: If routing selects no channel for a recipient, that recipient's outcome MUST
  reach a distinct terminal undeliverable state that is visible in status (G-20).
- **FR-025 [X ← D2]**: *(Withdrawn this iteration.)* Recipient preferences are not read and not
  consulted. The number is retained rather than reused so that the deferral stays legible in the
  requirement sequence. Reinstating §4.3 in full restores this requirement.
- **FR-026 [A]**: The routing policy MUST be expressible and changeable without altering the
  notification-handling behavior around it, and the policy version in force MUST be recorded
  with each decision. *(Assumption: §4.3 names "routing policy" as an input but does not define
  what one is — G-06. Recording the version is what makes a past decision explicable.)*

#### Asynchronous processing and delivery attempts (source §3.1)

- **FR-027 [E §3.1]**: Processing of an accepted notification MUST be asynchronous with respect
  to its submission.
- **FR-028 [E §3.1]**: System MUST make delivery attempts for each selected recipient and
  channel.
- **FR-029 [C ← FR-027]**: The submission response MUST NOT report a delivery outcome and MUST
  NOT wait for one.
- **FR-030 [C ← FR-028 + §4.2]**: Every delivery attempt MUST produce a recorded outcome that
  is reflected in the delivery's status.
- **FR-031 [C ← FR-003a]**: A notification carrying a not-before time MUST NOT be attempted
  before that time, and MUST become eligible for attempt once that time has passed.
- **FR-032 [C ← FR-003b]**: A notification whose expiry time has passed MUST NOT be attempted.
- **FR-033 [D]**: Both timestamps MUST be evaluated immediately before each attempt, not only
  when the notification is first processed. *(Design-derived: a delivery can cross the expiry
  boundary while waiting between retries; evaluating only once would violate FR-032.)*
- **FR-034 [D]**: Expiration MUST take precedence over any remaining retry budget, and the
  resulting outcome MUST be distinguishable from a delivery failure. *(Design-derived governing
  rule where §4.1 and §4.5 interact; the source states no precedence — G-03.)*
- **FR-035 [C ← FR-021 + FR-028]**: A delivery attempt MUST NOT be made on a channel that is
  absent from the recorded routing decision for that notification.

#### Retry and failure handling (source §4.5)

- **FR-036 [E §4.5]**: System MUST implement a bounded retry strategy for retryable delivery
  failures.
- **FR-037 [E §4.5]**: System MUST distinguish, and record, these delivery failure kinds:
  transient provider failure, permanent provider rejection, invalid recipient, timeout, and
  authentication or authorization error.
- **FR-038 [C ← §4.5 "bounded"]**: The number of attempts for a single delivery MUST have a
  finite maximum, and reaching it MUST produce a terminal outcome.
- **FR-039 [C ← FR-036 + FR-037]**: Each failure kind of FR-037 MUST be designated either
  retryable or non-retryable, and that designation MUST determine whether a retry occurs.
  **The designation is not stated by the source — see G-07.**
- **FR-040 [D — proposed designation for FR-039]**: Transient provider failure and timeout are
  retryable; permanent provider rejection, invalid recipient, and authentication or
  authorization error are non-retryable. *(Design-derived. Alternative considered and rejected:
  retrying authentication errors, which cannot succeed until configuration changes and would
  consume the budget for a fault the recipient did not cause.)*
- **FR-041 [D]**: An outcome that matches none of the FR-037 kinds MUST be recorded as unknown
  and MUST NOT be treated as a success.
- **FR-042 [D]**: An authentication or authorization failure MUST be surfaced as an operational
  condition in addition to being recorded against the delivery.
- **FR-043 [C ← §4.9 "Delivery queued/attempted/succeeded/failed"]**: Each individual attempt
  MUST be separately observable, carrying its own outcome and failure classification.
- **FR-044 [C ← FR-038]**: A delivery that exhausted its retry budget MUST be distinguishable
  from one that failed permanently on its first attempt.
- **FR-045 [A]**: The maximum attempt count and the spacing between attempts MUST be
  configurable. *(Assumption: §4.5 requires the strategy to be "bounded" but supplies no
  numbers — G-08. Values are proposed in Assumptions, not fixed here.)*

#### Audit history (source §4.9)

- **FR-046 [E §4.9]**: System MUST record these significant actions: notification accepted,
  notification rejected, routing decision made, delivery queued, delivery attempted, delivery
  succeeded, delivery failed, and retry scheduled.
- **FR-047 [E §4.9]**: Audit data MUST NOT store unnecessary sensitive message content and
  MUST NOT store credentials.
- **FR-048 [C ← §4.1 correlation identifier + §4.9]**: Every audit record MUST be attributable
  to its notification and retrievable by the event or correlation identifier.
- **FR-049 [C ← §4.2 "Relevant timestamps"]**: Every audit record MUST carry the time of the
  action it describes.
- **FR-050 [D]**: Audit records MUST NOT be modifiable or removable once written; corrections
  are recorded as additional actions. *(Design-derived: §4.9 titles this "Audit History"; the
  source does not state immutability.)*
- **FR-051 [C ← FR-047 + FR-056]**: Where an audited action relates to message content, the
  record MUST refer to that content indirectly — by a derived, non-reversible reference — and
  MUST NOT contain any part of the payload.
- **FR-052 [A ← D5]**: The recipient reference MUST be assumed to carry personal data and MUST
  be recorded in masked or indirect form in audit records, logs, and metric labels. It MAY be
  returned in status responses, which are addressed to the system that supplied it.
  *(Assumption: §4.9 forbids "unnecessary sensitive message content or credentials" but does not
  classify recipient identifiers, which may nonetheless be personal data. Narrower than the
  earlier form of this requirement, because after D5 no contact values exist to protect.)*
- **FR-053 [D]**: Delivery expired and retry budget exhausted MUST also be recorded as
  significant actions. *(Design-derived and permitted: §4.9 introduces its list with "such as",
  making it non-exhaustive.)*
- **FR-054 [C ← FR-047 + FR-056]**: The set of fields permitted in an audit record MUST be
  explicitly defined as a closed allowlist rather than left to whatever a caller supplies, and
  the content payload MUST NOT be a member of it. *(§4.9's "unnecessary" is a judgment term and
  was untestable until Q1 established what content is; with G-02 resolved, the allowlist is now
  a direct consequence rather than an assumption.)*

### Deliverable Obligations *(source §5 — project deliverables, not system behavior)*

These are stated separately because they are obligations on the work product, not functional
requirements on the running system. They are recorded here so they are not lost.

- **DO-001 [E §5]**: A working prototype that runs end to end.
- **DO-002 [E §5]**: An architecture overview covering components, tools, execution approach,
  control flow, and key decisions.
- **DO-003 [E §5]**: Setup instructions.
- **DO-004 [E §5]**: A statement of the testing approach, its limitations, and the trade-offs
  accepted.
- **DO-005 [C ← DO-004 + this document]**: Every unresolved item in the Gap Register that
  reaches implementation without an answer MUST appear in the limitations statement of DO-004.
- **DO-006 [C ← G-26]**: Any explicit source requirement that this iteration does not meet MUST
  be named as such in both DO-002 and DO-004. At present that is §4.3's recipient-preference
  factor. The prototype MUST NOT be presented as satisfying §4.3.

### Key Entities

- **[E] Notification**: The thing submitted. **[A ← D4]** Addressed by a server-issued identity;
  the client-supplied identifier below is descriptive and non-unique. Carries identifier, source system, event or
  correlation identifier, type, severity, priority, creation timestamp, its recipients, and its
  requested or eligible channels. **[A ← D3]** Also carries an optional not-before timestamp and
  an optional expiry timestamp, independently settable — §4.1 writes these as one field.
  **[A ← D1]** Also carries an opaque content payload authored by the source system.
- **[A ← D1] Content payload**: Sensitive, opaque, never parsed or rendered by this system,
  never present in audit records or status responses, conveyed unchanged to every selected
  channel, and referred to in audit only by a derived non-reversible reference.
- **[E] Recipient**: A party to be notified. At least one per notification. **[A ← D5]** Modelled
  as an opaque reference and nothing else — the source defines no recipient field. The system
  neither holds nor requires any contact detail; the reference is passed to the channel provider
  as supplied (G-17, G-32).
- **[E] Channel**: A means of delivery. Appears as requested/eligible on submission and as
  selected after routing. **[A]** The available set is closed and configured (G-18).
- **[X ← D2] Recipient preference**: Named as a routing input by §4.3, but never supplied by any
  submission field and never defined by any section of the source (G-05). Not modelled, not
  stored, and not consulted in this iteration. Retained in this list so its absence is visible
  rather than forgotten.
- **[E] Routing policy**: The governing rule set named as a routing input by §4.3. **[A]**
  Representation unspecified (G-06).
- **[C] Routing decision**: The recorded outcome of channel selection for a notification —
  required because §4.2 must report selected channels after the fact and §4.9 must record that
  the decision was made. **[D]** Includes per-channel selection reasons and the policy version.
- **[C] Delivery**: The unit of work for one recipient on one channel, required by §4.2's
  per-recipient-and-channel status. Holds the current delivery state.
- **[C] Delivery attempt**: A single try against a channel, required by §4.9's separate
  "attempted", "succeeded", and "failed" actions. Holds its outcome and, on failure, the
  classification from §4.5.
- **[E] Audit record**: One significant action, per §4.9. Immutable **[D]**, timestamped
  **[C]**, correlated **[C]**, content-free **[E]**.

### Out of Scope *(this iteration)*

- **[X] G-12 Idempotency, deduplication, and replay.** Deferred to a later deliverable by
  explicit project decision, recorded in the constitution v2.0.0 register item 9. D4 defines what
  *happens* on a duplicate — both submissions are accepted independently (FR-008b) — but
  deliberately supplies none of the semantics that would make it idempotent: no body comparison,
  no replay of an earlier response, no dedup window, no suppression of the second delivery.
- **[X] Cancellation or withdrawal of an accepted notification.** Not present in the source.
- **[X] Replay or manual reprocessing of exhausted deliveries.** Not present in the source.
- **[X] Rate limiting or throttling of source systems.** Not present in the source.
- **[X] Multi-tenancy and per-source-system authorization boundaries.** Not present in the
  source; the associated risk is recorded as G-16.
- **[X] End-user delivery receipts and read confirmations.** §4.2 requires "delivery status",
  which is interpreted as provider-side outcome rather than end-user receipt (G-14).
- **[X] Real third-party provider integrations.** §5 requires a runnable prototype;
  integrations with live providers are not required and are not assumed available (G-22).
- **[X] Message composition, templating, localisation, and rendering.** Not present in the
  source, and confirmed out of scope by the D1 resolution of Q1: content is authored by the
  source system and treated as opaque, so this system never constructs a message.
- **[X] Inspection, validation, or transformation of content payloads.** Direct consequence of
  D1 — an opaque payload cannot be validated beyond presence and size (FR-058, FR-059).
- **[X] Cross-notification ordering guarantees.** Not present in the source (G-19).
- **[X ← D2] Recipient preferences as a routing factor, and any preference store, preference API,
  opt-out, suppression, or quiet-hours capability.** §4.3 names preferences as a routing factor,
  so this is the one out-of-scope item that leaves an **explicit source requirement unmet**
  (G-26) rather than merely declining something the source never asked for. Excluded because the
  source supplies no preference data and defines no preference source (G-05). Closing it requires
  answering G-05 first.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001 [C ← §4.1/§4.2]**: 100% of submissions receive an explicit accept or reject
  decision, and every rejection names every offending element. Zero submissions end in an
  ambiguous outcome.
- **SC-002 [C ← §3.1/§4.2]**: 100% of accepted notifications are found by status retrieval
  immediately after acceptance. Zero occurrences of an acknowledged notification reporting as
  unknown.
- **SC-003 [E ← §4.2]**: For every accepted notification, the status shows exactly one delivery
  entry per selected recipient-and-channel pair — verified across a matrix including at least
  one multi-recipient, multi-channel case.
- **SC-004 [C ← §3.1]**: 100% of submissions return their decision before any delivery attempt
  for that notification has begun.
- **SC-005 [E ← §4.5]**: Each of the five named failure kinds is exercised at least once, and
  in every case the recorded classification matches the injected kind — 5 of 5.
- **SC-006 [C ← §4.5 "bounded"]**: Across a full exercise run, zero deliveries exceed the
  configured maximum attempt count.
- **SC-007 [C ← FR-003b]**: Zero delivery attempts occur after a notification's expiry time,
  including for deliveries that were waiting between retries when expiry passed.
- **SC-014 [C ← FR-003a]**: Zero delivery attempts occur before a notification's not-before
  time, and every such notification is attempted once that time has passed — verified across all
  four combinations of the two optional timestamps being present or absent.
- **SC-008 [E ← §4.9]**: A single end-to-end run that includes a rejection, a routing decision,
  a failure, a retry, and a terminal outcome produces a retrievable record for every one of the
  action types required by §4.9 — 8 of 8, plus the two added by FR-053.
- **SC-009 [E ← §4.9, sharpened by D1 and D5]**: An inspection of the complete audit history and
  operational output of that run finds zero occurrences of the content payload or any fragment of
  it, zero credentials, and zero unmasked recipient references — verified by searching for a known
  marker string planted in the submitted payload. Status responses are excluded from the recipient
  -reference part of this check: the reference is echoed there to the system that supplied it
  (FR-052).
- **SC-010 [C ← §4.9 + §6 "defensibility"]**: Given only a correlation identifier, a reviewer
  can reconstruct the full lifecycle of a notification — including why each channel was chosen
  or rejected — without consulting anyone who built the system.
- **SC-011 [E ← §5]**: A person who did not build the system can run the prototype end to end
  from a clean checkout using only the written setup instructions, with no undocumented step.
- **SC-012 [C ← §4.3, scoped by D2]**: For a documented table of routing inputs — requested
  channels, severity, and policy — the selected channels match the expected selection in 100% of
  cases, and repeating any case yields the identical result.
- **SC-013 [A ← D2]**: Channel selection is provably independent of recipient preference data:
  identical selections run against recipients holding differing preference attributes produce
  identical results in 100% of cases. This guards against a preference proxy creeping in under
  another name while §4.3 is formally deferred.

*(No latency, throughput, or capacity target appears here, because the source document states
none. Inventing one would fail this specification's own provenance rule — see G-11.)*

## Gap Register *(mandatory for this specification)*

Every gap is listed with the source requirement that creates it. **Unresolved** items have no
answer yet. **Assumed** items have a working answer adopted so implementation can proceed;
each remains a candidate for correction and each flows into DO-005.

| ID | Gap | Type | Source requirement that creates it | Status / working answer |
|----|-----|------|------------------------------------|--------------------------|
| G-01 | The source numbering runs 4.1, 4.2, 4.3, 4.5, 4.9 — sections 4.4, 4.6, 4.7 and 4.8 are absent | Missing | §4 as a whole | **Unresolved.** The document appears to be an excerpt. Obligations may exist that this specification does not cover. Requires the requirement owner's confirmation. |
| G-02 | No message body, payload, subject, or content field is listed among the submission fields, yet the audit rule speaks of "sensitive message content" | **Contradiction** | §4.1 field list vs §4.9 | **RESOLVED 2026-09-07 (Q1 option A).** Notification carries an opaque source-supplied content payload, treated as sensitive throughout, referenced indirectly in audit. See D1; FR-055–FR-059. |
| G-03 | "Optional scheduling/expiration timestamp" is written as a single field, but scheduling (do not send before) and expiration (do not send after) are opposite constraints | Ambiguous | §4.1 | **RESOLVED 2026-09-07 (D3, Q3 option A).** Two independently optional timestamps: a not-before time and an expiry time. FR-003a–FR-003e. |
| G-04 | The four routing factors are listed with no precedence when they disagree | Missing | §4.3 | **RESOLVED 2026-09-07 (D2), and largely dissolved.** With recipient preferences excluded, the preference-versus-severity conflict cannot arise. Precedence among the remaining three is FR-020a: policy decides; severity and requested channel are inputs. |
| G-05 | **§4.3 requires an input the source never makes obtainable.** Recipient preferences are named as a routing factor, but §4.1's submission field list does not carry them and no section of the document defines a preference store, owner, shape, or lifecycle | **Internal inconsistency — an explicit requirement with no supplied input** | §4.3 (names the factor) against §4.1 (enumerates submission fields, none of them preferences) and the absence of any preference-source section anywhere in §3–§5 | **RESOLVED by deferral 2026-09-07 (D2).** Because no preference data reaches this system and no source for it is defined, the factor cannot be implemented as specified without inventing both a data source and a preference model. Excluded from this iteration rather than fabricated. See G-26. |
| G-06 | "Routing policy" is named as an input but never defined | Missing | §4.3 | **Assumed.** A policy is externally configurable and versioned, and the version in force is recorded with each decision (FR-026). |
| G-07 | The five failure kinds are listed, and retry applies to "retryable" failures, but no kind is designated retryable or non-retryable | Missing | §4.5 | **Assumed** — FR-040. Retryable: transient provider failure, timeout. Non-retryable: permanent rejection, invalid recipient, auth error. |
| G-08 | "Bounded" retry with no maximum attempt count, spacing, or ceiling given | Missing | §4.5 | **Assumed.** Bounds are configurable (FR-045); proposed starting values are in Assumptions. Any value satisfies "bounded"; the specific value does not come from the source. |
| G-09 | No state vocabulary is given for either the overall status or per-delivery status | Missing | §4.2 | **Assumed** — a documented closed set (FR-017). §4.2 explicitly permits a different state model "if it is documented and defensible", so this gap is licensed by the source. |
| G-10 | No authentication or authorization requirement is stated for the notification submission and status retrieval capabilities themselves | Missing | §4.1, §4.2 (both define externally reachable capabilities); §4.9 mentions credentials only in the provider sense | **Assumed.** Callers are authenticated at the boundary. This is a standard default rather than a source requirement, and the prototype's arrangement must be declared a limitation under DO-004. |
| G-11 | No volume, throughput, latency, concurrency, or retention target is stated anywhere | Missing | §5 "production-grade" and §6 "scalable" imply targets exist, but none are given | **Unresolved, non-blocking.** No performance criterion is asserted in Success Criteria; any figure used during planning is an engineering assumption, not a requirement. |
| G-12 | Behavior on resubmission of an existing notification identifier is unspecified | Missing | §4.1 "Notification identifier" | **RESOLVED 2026-09-07 (D4).** Both submissions are accepted as independent notifications addressed by distinct server-issued identities. Idempotency, deduplication and replay remain deferred (constitution v2.0.0 item 9) and MUST appear in DO-004 limitations. |
| G-31 | §4.1 names a "Notification identifier" but the source never states that it must be unique, nor what identifies a notification to the status API | Ambiguous | §4.1 (names the identifier) against §4.2 (requires a notification's status to be retrievable, which requires a unique address) | **RESOLVED 2026-09-07 (D4).** The client identifier is descriptive and non-unique; a server-issued identity is the retrieval key. FR-008, FR-008a, FR-008b. |
| G-13 | "Delivery status" is not defined as provider acceptance versus end-user receipt | Ambiguous | §4.2 | **Assumed.** Status reflects the outcome reported by the delivery channel, not confirmed end-user receipt. Receipts are out of scope (G-14 duplicate view). |
| G-14 | No provider callback, webhook, or asynchronous receipt path is described | Missing | §4.2, §3.1 "Delivery attempts" | **Out of scope.** Consequence of the G-13 reading. |
| G-15 | "Priority" is a mandatory submission field but no requirement anywhere states what priority does | **Missing behavior for a mandatory field** | §4.1 lists priority; §4.3 lists severity — not priority — as a routing factor | **Assumed.** Priority is captured, stored, and returned but drives no behavior in this iteration. If it is meant to order processing, that is an unstated requirement. Worth raising with the requirement owner. |
| G-16 | Nothing prevents a source system from submitting notifications naming arbitrary recipients | Missing | §4.1 "Source system" is captured but never used as an authorization subject | **Out of scope, risk recorded.** Multi-tenancy and per-source authorization are not in the source. |
| G-17 | A "recipient" is required but never defined — the source states no field of a recipient at all | Missing | §4.1 "One or more recipients"; §4.2 requires status per recipient, which requires a stable recipient identity | **RESOLVED 2026-09-07 (D5).** A recipient is an opaque reference and nothing more. No address, contact value, or per-channel addressing is submitted, stored, or invented. |
| G-32 | **Delivery requires a destination the source never supplies.** Attempting delivery on a channel requires an address, endpoint, or token for the recipient, but §4.1 defines no such field, and no section defines a directory or any resolution mechanism | **Internal inconsistency — an explicit capability with no supplied input** | §3.1 "Delivery attempts" and §4.5 "Invalid recipient" (both presuppose a resolvable destination) against §4.1 (enumerates submission fields, none of them a destination) | **Unresolved, working answer recorded.** The recipient reference is passed opaquely to the channel provider, which is simulated (G-22). A real provider would require a resolution step this specification does not describe. Same shape as G-05. MUST appear in DO-004 limitations. |
| G-18 | The set of available channels is never enumerated | Missing | §4.1 "Requested or eligible channels", §4.3 | **Assumed.** A closed, configured channel set (FR-009). Prototype channels are simulated (G-22). |
| G-19 | No ordering guarantee is stated between notifications or between a recipient's channels | Missing | §3.1 "Asynchronous processing" | **Assumed.** No ordering is guaranteed. Declared rather than promised. |
| G-20 | The source does not say what happens when routing selects no channel | Missing | §4.3 combined with §4.2, which must still report something | **Assumed** — a distinct terminal undeliverable outcome (FR-024). |
| G-21 | The creation timestamp's authority is unstated — caller-supplied or system-observed | Ambiguous | §4.1 "Creation timestamp" | **Assumed.** Caller-supplied and recorded as given, with a separate system-observed receipt time so that the audit history remains coherent under clock skew. |
| G-22 | Whether real delivery providers are available or in scope is never stated | Missing | §3.1 "Delivery attempts" vs §5 "working prototype" | **Assumed.** Simulated providers, exercised through the same path real ones would use. Declared as a limitation under DO-004. |
| G-23 | Whether the content payload is mandatory or optional | Missing | Created by the D1 resolution of G-02, not by the source — §4.1 lists no content field at all | **Assumed** — mandatory (FR-058). A notification with no content cannot produce a meaningful delivery. Reversible if the owner wants content-free notification types. |
| G-24 | No size bound on the caller-supplied content payload | Missing | Created by the D1 resolution of G-02; §4.1 bounds no field, and §6 asks for scalable code | **Assumed** — a declared maximum size, enforced at submission (FR-059). The specific figure is an engineering assumption, not a requirement. |
| G-26 | This iteration implements three of the four routing factors §4.3 requires | **Unmet explicit requirement, deliberately deferred** | §4.3, rendered unimplementable by the omission recorded in G-05 | **Accepted and recorded (D2).** MUST be stated in DO-002 and DO-004. This capability MUST NOT be described as satisfying §4.3. Closing it requires answering G-05 first: where preferences come from and what they look like. |
| G-27 | Precedence among the three retained routing factors was not stated | Missing | Created by the D2 scoping of §4.3 | **Assumed** — FR-020a: policy is the deciding authority; severity and requested channel are inputs it consults. Low stakes, since all three now resolve inside one policy evaluation. |
| G-28 | Whether excluding recipient preferences is permanent or sequencing | Ambiguous | Created by D2; the owner's phrase "in this scope" reads as deferral | **Assumed deferral, not cancellation.** Routing MUST remain extensible to a fourth factor without reworking status, retry, or audit behavior. |
| G-29 | Whether a not-before time may be honoured only approximately, and how much lateness is acceptable | Missing | Created by the D3 resolution of G-03; §4.1 states no timeliness obligation and §5/§6 state no timing targets (G-11) | **Assumed.** The not-before time is a floor, not a schedule guarantee: delivery is attempted at or after it, with no upper bound on lateness promised. A punctuality obligation would be a new requirement. |
| G-30 | Which clock decides that a not-before or expiry time has been reached, given caller-supplied timestamps | Ambiguous | Created by D3; compounds G-21, where the creation timestamp's authority is already unstated | **Assumed.** The system's own clock decides, and the two timestamps are interpreted as absolute instants. Caller clock skew therefore shifts the caller's intent, not the system's behavior. |
| G-25 | Whether the content payload must be retained after a notification reaches a terminal state | Missing | Created by the D1 resolution of G-02; interacts with §4.9 minimisation and the absent retention target (G-11) | **Unresolved, non-blocking.** Working answer: retained for the notification's lifetime with no purge policy defined. Minimising retention would better serve §4.9's intent; needs an owner decision alongside G-11. |

### Resolved Decisions

#### D1: Message content model — **resolved 2026-09-07 by the requirement owner** *(closes G-02)*

**The contradiction**: §4.1 enumerates ten submission fields, none of which is a message body,
subject, or template reference. §4.9 then requires that audit data "avoid storing unnecessary
sensitive message content or credentials", which presupposes message content exists.

**Decision (Q1 option A)**: A notification carries an **opaque content payload supplied by the
source system**. The system does not parse, interpret, transform, or render it. It is treated
as sensitive for its whole lifetime and is referenced only indirectly in audit.

**Consequences**: FR-055 through FR-059 added; FR-051 and FR-054 upgraded from assumption to
direct consequence, because the audit allowlist is now derivable rather than guessed.
Templating, composition, localisation, and rendering remain out of scope — option A places
content authorship with the source system, so this system never needs to construct a message.

**Authority**: This is an owner decision resolving a source contradiction. It is not source
text, and every requirement flowing from it is tagged accordingly.

**Residual open points** created by the decision, recorded but not blocking: G-23 (whether
content is mandatory) and G-24 (payload size bound). Both have working answers.

#### D2: Routing factors scoped to three — **decided 2026-09-07 by the requirement owner** *(closes G-04; resolves G-05 by deferral; opens G-26, G-27, G-28)*

**The question asked (Q2)**: §4.3 names four routing factors — requested channel, severity,
recipient preferences, routing policy — and states no precedence when they disagree. The concrete
stake put to the owner was whether a severity level may override a recipient's channel opt-out.

**Decision**: "The system decides, for each recipient, which channels will actually be used. In
this scope the decision draws on the channels the submitter requested, the severity of the event,
and the configured routing policy."

**Why the exclusion is justified by the source, not merely by preference**: §4.3 names recipient
preferences as a routing factor, but the document never supplies them. §4.1 enumerates the
submission fields and preferences are not among them, and no section of §3–§5 defines a preference
store, owner, shape, or lifecycle. **§4.3 therefore requires an input that the rest of the source
makes unobtainable** (G-05). Implementing it would have meant inventing both a data source and a
preference model and then presenting the result as a source requirement — precisely the silent
decision this specification is written to avoid.

**What this resolves**: The consent conflict disappears, because preferences are no longer an
input. Precedence among the three retained factors is then a low-stakes engineering choice, taken
as FR-020a: policy decides; severity and requested channel are inputs to it.

**What this costs — stated plainly**: this iteration implements **three of four** factors and
**does not satisfy §4.3 in full** (G-26). It MUST be declared in the architecture overview
(DO-002) and the limitations statement (DO-004), and this capability MUST NOT be described as
meeting §4.3.

**Read as deferral, not cancellation** (G-28): "in this scope" indicates sequencing. Closing G-26
requires answering G-05 first — where preferences come from and what they look like. The recorded
routing decision and FR-024's undeliverable outcome are shaped so a fourth factor can be added
later without disturbing status, retry, or audit behavior.

**Guard against silent substitution** (FR-019a): no proxy for recipient preference — a
per-recipient channel list, a suppression flag, a quiet-hours attribute — may be introduced under
another name while preferences are formally out of scope. That would implement §4.3 partially
while reporting it as deferred, which is worse than either doing it or not.

#### D3: Scheduling and expiry are two fields — **resolved 2026-09-07 by the requirement owner** *(closes G-03; opens G-29, G-30)*

**The ambiguity**: §4.1's final field is "Optional scheduling/expiration timestamp". Scheduling
means *do not deliver before*; expiration means *do not deliver after*. One field cannot express
both, yet FR-031 and FR-032 each depended on a different reading of it.

**Decision (Q3 option A)**: Two distinct, independently optional timestamps — a **not-before**
time and an **expiry** time.

**Consequences**: FR-003a–FR-003e added. FR-031 and FR-032 are now separate behaviors, each
anchored to its own field, rather than two candidate readings of one. The combination check
becomes reachable and is specified: a not-before time at or after the expiry time describes a
window that never opens, and is rejected (FR-003d). An already-passed expiry is rejected at
submission rather than accepted and immediately expired (FR-003e).

**Authority and cost**: this is an owner decision resolving a source ambiguity, and it is the
reading that departs furthest from §4.1's literal single-field wording. It is tagged `[A ← D3]`
rather than `[E]` throughout, so a reviewer comparing this specification against §4.1 sees
immediately that the two-field model is an interpretation, not source text. The cost of being
wrong is low and one-directional: option B (expiry only) is a strict subset, so an unused
not-before field would be the whole of the waste.

**Residual open points**, recorded with working answers: G-29 (a not-before time is a floor, not
a punctuality promise) and G-30 (the system's clock decides, so caller clock skew shifts the
caller's intent rather than system behavior).

#### D4: Duplicate client identifiers accepted; server-issued identity is the retrieval key — **decided 2026-09-07 by the requirement owner** *(closes G-12 and G-31; revises FR-008)*

**The problem**: §4.1 names a "Notification identifier" supplied by the caller, but never states
that it must be unique. §4.2 requires a notification's status to be retrievable, which requires
some unique address. Constitution v2.0.0 (register item 9) defers idempotency, so uniqueness
could not simply be assumed into place.

**Decision**: Duplicate client identifiers are **accepted**. Each submission becomes an
independent notification with its own **server-issued identity**, and that identity — not the
client identifier — is the key by which status is retrieved.

**What it revises**: the original FR-008 was design-derived and named §4.1's client identifier as
the natural retrieval key, explicitly rejecting "a server-issued separate handle" as adding a
second identity for no stated benefit. That reasoning does not survive non-unique identifiers: a
non-unique key cannot address a single notification. FR-008 is revised, and FR-008a and FR-008b
are added.

**What it deliberately does not do**: this is not idempotency and must not be described as such.
There is no body comparison, no replay of an earlier response, no dedup window, and no
suppression of the second notification's deliveries. Every deduplication behavior remains
deferred under constitution register item 9. D4 defines only what *happens*, so that "out of
scope" does not silently become "whatever the datastore does".

**Cost**: callers now hold two identifiers and must keep the server-issued one to check status.
The client identifier remains stored, returned, and searchable (FR-008a).

#### D5: A recipient is an opaque reference — **decided 2026-09-07 by the requirement owner** *(closes G-17; opens G-32; narrows FR-052)*

**The problem**: §4.1 requires "one or more recipients" as a submission field, but the source
never states a single field of a recipient — no address, no contact value, no name. An earlier
draft of this specification assumed a recipient carried per-channel addresses supplied by the
submitter, and built masking, an address model, and a `NO_ADDRESS_FOR_CHANNEL` routing outcome on
top of that assumption.

**Why that assumption was withdrawn**, on two grounds:

1. **§4.5 frames recipient validity as a provider verdict.** Its five failure kinds — transient
   provider failure, permanent provider rejection, *invalid recipient*, timeout, and
   authentication error — are uniformly things a provider reports at delivery time. Validating a
   recipient at submission, against a schema this specification invented, works against that
   framing.
2. **It was inconsistent with D2.** Preferences and addresses are both recipient-associated data.
   D2 defers preferences precisely because the source supplies none and defines no source for
   them. Treating addresses as submitter-supplied while treating preferences as unavailable
   assumed a recipient directory into existence for one purpose and out of existence for another.

**Decision**: A recipient is an opaque reference. Nothing else about a recipient is submitted,
stored, or inferred. The reference is passed to the channel provider unchanged.

**Consequences**: The address model, per-channel address storage, contact-value masking, and the
`NO_ADDRESS_FOR_CHANNEL` routing reason are all removed — none of them was traceable to the
source. `INVALID_RECIPIENT` survives unchanged as a provider-reported outcome, which is what §4.5
describes. FR-052 narrows to protecting the reference itself, since no contact values remain.

**What it exposes rather than hides** (G-32): delivery on a channel needs a destination, and the
source supplies none and defines no way to obtain one. The earlier assumption concealed that gap
by inventing the missing input. It is now recorded as an unresolved finding of the same shape as
G-05, and must appear in the DO-004 limitations.

### Open Questions

**None.** All three clarifications raised in iteration 1 are resolved: D1 (message content model),
D2 (routing factors), D3 (scheduling and expiry). What remains open is recorded in the Gap
Register with working answers, and the single knowingly-unmet source requirement is G-26. that have no defensible default and would change the specification's content,
not merely its configuration. They are raised rather than decided.

## Assumptions

Each assumption is a working answer to a Gap Register entry. None is a source requirement.
Every one of them flows into the limitations statement required by DO-004 if it is still
unconfirmed at implementation time.

- **[A ← G-02 / D1, owner-decided]** A notification carries an opaque content payload authored
  by the source system. This system never parses, renders, or transforms it. It is sensitive
  for its whole lifetime and appears in no audit record or status response.
- **[A ← G-23]** That payload is mandatory; a submission without one is rejected. Reversible if
  content-free notification types turn out to be wanted.
- **[A ← G-24]** The payload has a declared maximum size enforced at submission. The specific
  figure is an engineering assumption, not a requirement.
- **[A ← G-25]** The payload is retained for the notification's lifetime, with no purge policy
  defined. Minimising this retention would better serve §4.9's intent and should be decided
  together with the absent retention target (G-11).
- **[A ← G-03 / D3, owner-decided]** §4.1's "scheduling/expiration timestamp" is read as two
  independently optional fields: a not-before time and an expiry time. This is the reading that
  departs furthest from the literal single-field wording, and every requirement flowing from it
  is tagged as an interpretation rather than as source text.
- **[A ← G-29]** A not-before time is a floor, not a punctuality guarantee. Delivery is attempted
  at or after it, with no promised bound on lateness.
- **[A ← G-30]** The system's own clock decides whether a not-before or expiry time has been
  reached; both are absolute instants. Caller clock skew shifts the caller's intent, not the
  system's behavior.
- **[A ← G-05 / D2, owner-decided]** Recipient preferences do not participate in routing in this
  iteration, because §4.3 names them while §4.1 supplies them nowhere and the source defines no
  preference store. No preference data is modelled, stored, or consulted, and no substitute for
  it is introduced under another name.
- **[A ← G-27]** Among the three retained routing factors, the configured policy is the deciding
  authority and the requested channels and severity are inputs it consults. A requested channel
  is a proposal the policy may decline.
- **[A ← G-28]** The exclusion is read as deferral rather than cancellation, so routing must stay
  extensible to a fourth factor without reworking status, retry, or audit behavior.
- **[A ← G-06]** The routing policy is externally configurable, versioned, and evaluated
  identically for identical inputs. The version in force is recorded with every decision.
- **[A ← G-07]** Retryable: transient provider failure, timeout. Non-retryable: permanent
  provider rejection, invalid recipient, authentication or authorization error. Unknown
  outcomes are treated as retryable but never as success.
- **[A ← G-08]** Proposed starting bounds, configurable and not derived from the source: a
  maximum of 5 attempts per delivery, with increasing spacing between attempts. These exist to
  make "bounded" testable; any confirmed figures replace them without changing any requirement.
- **[A ← G-09]** Proposed delivery states: pending, queued, in progress, delivered, failed
  (with classification), retry scheduled, exhausted, expired, undeliverable. Proposed overall
  states: accepted, in progress, completed, partially failed, failed, expired. *(Rollup refined
  2026-09-07: all-pending rolls up to accepted, so "accepted" means nothing has been attempted
  yet rather than being reachable only when routing selected nothing.)* Both sets are
  documented per §4.2's explicit licence to use a different state model.
- **[A ← G-10]** Callers of both capabilities are authenticated; the notification capability is
  not assumed to be openly reachable. The prototype's specific arrangement is a limitation to
  declare, not a production posture.
- **[A ← G-11]** No performance or capacity target is asserted. Any figure adopted during
  planning is an engineering assumption and is not traceable to the source.
- **[A ← G-13]** Delivery status reflects the outcome reported by the delivery channel, not
  confirmed end-user receipt.
- **[A ← G-15]** Priority is captured, stored, and reported but drives no behavior in this
  iteration, because no source requirement states an effect for it.
- **[A ← G-17 / D5, owner-decided]** A recipient is an opaque reference. No address, contact
  value, or per-channel addressing is submitted, stored, or invented, because the source defines
  no recipient field. Recipient validity is reported by the channel provider at delivery time.
- **[A ← G-32]** The recipient reference is passed to the channel provider unchanged. How a real
  provider would turn that reference into a destination is not described by the source and is not
  invented here; the prototype's providers are simulated.
- **[A ← G-18]** The channel set is closed and configured. Adding a channel is a configuration
  and adapter concern, not a change to routing, retry, status, or audit behavior.
- **[A ← G-19]** No ordering is guaranteed between notifications or across a recipient's
  channels.
- **[A ← G-21]** The creation timestamp is caller-supplied and recorded as given; the system
  additionally records its own receipt time.
- **[A ← G-22]** Delivery providers are simulated for the prototype and are driven through the
  same path a real provider would use, so that all five failure kinds of §4.5 are exercisable.
- **[A ← G-12 / D4, owner-decided]** Duplicate client identifiers are accepted as independent
  notifications, addressed by distinct server-issued identities. This satisfies the
  constitution's deferral of idempotency (v2.0.0, register item 9) by declining every
  deduplication behavior, while still leaving no undefined behavior in the store. If the deferral
  is lifted, G-12 reopens and FR-008b is the requirement that would change.

## Dependencies

- **[X ← D2]** *No preference-data dependency exists in this iteration.* §4.3 would require one,
  but no preference source is defined by the source document and none is introduced here (G-05,
  G-26). Meeting §4.3 in a later iteration adds a dependency this specification does not
  describe: an owner, a shape, and a lifecycle for recipient preference data.
- **[A ← D2]** The routing policy must be configurable and available at decision time; it is the
  deciding authority under FR-020a.
- **[E ← §3.1 "Delivery attempts"]** At least one delivery channel must be reachable for any
  delivery to succeed. Under assumption G-22 these are simulated.
- **[C ← §4.9]** Audit history must outlive the notifications it describes for the history to
  be useful; no retention period is stated by the source (G-11).
