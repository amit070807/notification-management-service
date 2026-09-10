package com.notification.application;

import com.notification.audit.AuditRecorder;
import com.notification.audit.Masking;
import com.notification.audit.payload.AuditPayload;
import com.notification.domain.model.*;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.port.*;
import com.notification.config.ObservabilityConfig.NotificationMetrics;
import com.notification.domain.retry.FailureClassification;
import com.notification.config.RetryPolicySelector;
import com.notification.domain.retry.RetryPolicy;
import com.notification.domain.retry.Retryability;
import com.notification.domain.state.DeliveryState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T077-T082 — drives one delivery attempt through the state machine.
 *
 * <p>Retry is NOT implemented here yet; that is Phase 7 (US5). At this phase a failure is terminal.
 */
@Service
public class DeliveryProcessingService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeliveryProcessingService.class);

    private final DeliveryRepositoryPort deliveries;
    private final NotificationRepositoryPort notifications;
    private final Map<Channel, ChannelProviderPort> providers;
    private final AuditRecorder audit;
    private final ClockPort clock;
    private final IdPort ids;
    private final JdbcClient jdbc;
    private final RetryPolicySelector retryPolicies;
    private final RandomPort random;
    private final NotificationMetrics metrics;

    public DeliveryProcessingService(
            DeliveryRepositoryPort deliveries,
            NotificationRepositoryPort notifications,
            List<ChannelProviderPort> providerList,
            AuditRecorder audit,
            ClockPort clock,
            IdPort ids,
            JdbcClient jdbc,
            RetryPolicySelector retryPolicies,
            RandomPort random,
            NotificationMetrics metrics) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.providers =
                providerList.stream().collect(Collectors.toMap(ChannelProviderPort::channel, Function.identity()));
        this.audit = audit;
        this.clock = clock;
        this.ids = ids;
        this.jdbc = jdbc;
        this.retryPolicies = retryPolicies;
        this.random = random;
        this.metrics = metrics;
    }

    @Transactional
    public void process(Delivery delivery) {
        Instant now = clock.now();
        Optional<Notification> maybe = notifications.findById(delivery.notificationId());
        if (maybe.isEmpty()) {
            return;
        }
        Notification notification = maybe.get();

        // T049/FR-159 — a reclaimed delivery arrives still marked IN_PROGRESS, because claimDue takes
        // the lease without changing state. It must return to QUEUED before anything else: the state
        // machine legitimately rejects IN_PROGRESS -> IN_PROGRESS, and silently tolerating that would
        // be exactly the kind of exception path Principle IV forbids.
        //
        // Deliberately BEFORE the expiry check, so the reclaim is recorded even when the delivery is
        // then expired. Otherwise a stranded-then-expired delivery would reach EXPIRED with no
        // record that it had ever been stuck, which is the case an operator most needs to see.
        Delivery current = delivery;
        if (delivery.state() == DeliveryState.IN_PROGRESS) {
            current = reclaim(delivery, notification, now);
        }
        delivery = current;

        // T078 — the eligibility check runs immediately before EVERY attempt, not only when the
        // notification is first processed. A delivery can cross either boundary while waiting in
        // backoff, so evaluating once would violate FR-032.
        if (notification.isExpiredAt(now)) {
            expire(delivery, notification, now);
            return;
        }
        if (!notification.isReleasedAt(now)) {
            // Not yet due. Release the lease and leave it QUEUED (FR-031).
            release(delivery);
            return;
        }

        // T080 — an attempt on a channel absent from the recorded routing decision is forbidden
        // (FR-035). The delivery rows are created only from selected outcomes, so this is a
        // defence-in-depth check against a row arriving by another path.
        if (!isChannelInRecordedDecision(delivery)) {
            transition(delivery, DeliveryState.UNDELIVERABLE, now, null, 0);
            return;
        }

        attempt(delivery, notification, now);
    }

    private void attempt(Delivery delivery, Notification notification, Instant now) {
        int attemptNumber = delivery.attemptCount() + 1;

        // The local object goes stale the moment we transition, so carry the live state forward.
        // Without this, the second transition would be checked from QUEUED rather than
        // IN_PROGRESS and the state machine would correctly reject it as illegal.
        Delivery inProgress = transition(delivery, DeliveryState.IN_PROGRESS, now, null, delivery.attemptCount());

        audit.record(
                notification.id(),
                notification.correlationId(),
                AuditEventType.DELIVERY_ATTEMPTED,
                new AuditPayload.DeliveryAttempted(
                        delivery.id().toString(),
                        Masking.mask(delivery.recipientRef().value()),
                        delivery.channel().name(),
                        attemptNumber));

        // A reclaimed re-attempt reuses its attempt number — that is exactly what keeps the idempotency
        // key stable across the crash (FR-161) — and uq_attempt_number forbids a second row for it. So
        // the row is upserted: this is the same logical attempt executed again, not a new one. Handing
        // it a fresh number to satisfy the constraint would change the key and defeat the provider-side
        // deduplication the reclaim exists to make safe.
        //
        // The stranded row's PENDING outcome and stale start time are overwritten. That the attempt ran
        // twice is not lost — DELIVERY_RECLAIMED records it, and is the only place that says why.
        //
        // The id is taken from RETURNING rather than the generated one: on conflict the existing row
        // keeps its own id, so using the generated value would leave the outcome update below matching
        // nothing and the attempt would stay PENDING forever.
        UUID attemptId =
                jdbc.sql(
                                """
                                INSERT INTO delivery_attempt
                                    (id, delivery_id, attempt_number, started_at, outcome)
                                VALUES (?, ?, ?, ?, 'PENDING')
                                ON CONFLICT (delivery_id, attempt_number) DO UPDATE
                                   SET started_at = EXCLUDED.started_at,
                                       outcome = 'PENDING',
                                       finished_at = NULL,
                                       failure_classification = NULL,
                                       diagnostic = NULL
                                RETURNING id
                                """)
                        .param(ids.newId())
                        .param(delivery.id())
                        .param(attemptNumber)
                        .param(Timestamp.from(now))
                        .query(UUID.class)
                        .single();

        DeliveryOutcome outcome =
                providers
                        .get(delivery.channel())
                        .send(delivery.recipientRef(), notification.content());
        // T044/FR-161. Derived here, immediately before the call, from data already written: the
        // delivery row at acceptance and the attempt row just above. That is what makes it
        // reproducible after a crash between this call and the outcome write — the exact window the
        // key exists to survive (ADR-019).
        IdempotencyKey key =
                IdempotencyKey.of(
                        delivery.notificationId(), delivery.recipientId(), delivery.channel(), attemptNumber);

        Instant finished = clock.now();
        jdbc.sql(
                        "UPDATE delivery_attempt SET finished_at = ?, outcome = ?, failure_classification = ?, "
                                + "diagnostic = ? WHERE id = ?")
                .param(Timestamp.from(finished))
                .param(outcome.success() ? "SUCCESS" : "FAILURE")
                .param(outcome.classification() == null ? null : outcome.classification().name())
                .param(outcome.diagnostic())
                .param(attemptId)
                .update();

        if (outcome.success()) {
            transition(inProgress, DeliveryState.DELIVERED, finished, null, attemptNumber);
            audit.record(
                    notification.id(),
                    notification.correlationId(),
                    AuditEventType.DELIVERY_SUCCEEDED,
                    new AuditPayload.DeliverySucceeded(
                            delivery.id().toString(),
                            Masking.mask(delivery.recipientRef().value()),
                            delivery.channel().name(),
                            attemptNumber));
        } else {
            handleFailure(inProgress, notification, outcome, attemptNumber, finished);
        }
    }

    /**
     * T091-T093, T095 — the retry decision.
     *
     * <p>Retryability is read from {@link Retryability}, the single place the partition is
     * declared, so the worker cannot drift from the unit truth table.
     */
    private void handleFailure(
            Delivery delivery,
            Notification notification,
            DeliveryOutcome outcome,
            int attemptNumber,
            Instant now) {

        FailureClassification classification = outcome.classification();
        metrics.attemptFailed(classification.name(), delivery.channel().name());

        audit.record(
                notification.id(),
                notification.correlationId(),
                AuditEventType.DELIVERY_FAILED,
                new AuditPayload.DeliveryFailed(
                        delivery.id().toString(),
                        Masking.mask(delivery.recipientRef().value()),
                        delivery.channel().name(),
                        attemptNumber,
                        classification.name()));

        // FR-042: a configuration fault, not a recipient fault. Surfaced separately so it is not
        // absorbed into the ordinary failure count, where an outage affecting every recipient
        // would look like ordinary attrition.
        if (Retryability.raisesOperationalSignal(classification)) {
            metrics.authErrorRaised(delivery.channel().name());
            log.warn(
                    "Delivery failed with {} on channel {} - this indicates a service configuration"
                            + " fault rather than a recipient problem; retrying cannot help until it is fixed",
                    classification,
                    delivery.channel());
        }

        // T032/FR-132: the schedule is per channel — push backs off more patiently than email,
        // which is the substantive part of "handling" a rate limit (ADR-018). The BOUND is not
        // per channel: a provider may differ in schedule, never opt out of being bounded.
        RetryPolicy retryPolicy = retryPolicies.forChannel(delivery.channel());

        boolean retryable = Retryability.isRetryable(classification);
        boolean budgetRemains = retryPolicy.hasBudgetAfter(attemptNumber);

        if (retryable && budgetRemains) {
            Instant nextAttempt = now.plus(retryPolicy.delayAfter(attemptNumber, random.nextJitterFactor()));
            scheduleRetry(delivery, classification, attemptNumber, nextAttempt, now);
            metrics.retryScheduled(delivery.channel().name());
            audit.record(
                    notification.id(),
                    notification.correlationId(),
                    AuditEventType.RETRY_SCHEDULED,
                    new AuditPayload.RetryScheduled(
                            delivery.id().toString(),
                            delivery.channel().name(),
                            attemptNumber,
                            nextAttempt.toString(),
                            classification.name()));
            return;
        }

        if (retryable) {
            // Budget spent. EXHAUSTED, not FAILED: a flaky provider and an outright rejection
            // mean different things operationally (FR-044).
            transition(delivery, DeliveryState.EXHAUSTED, now, classification, attemptNumber);
            metrics.deliveryTerminal(DeliveryState.EXHAUSTED.name(), delivery.channel().name());
            audit.record(
                    notification.id(),
                    notification.correlationId(),
                    AuditEventType.RETRY_BUDGET_EXHAUSTED,
                    new AuditPayload.RetryBudgetExhausted(
                            delivery.id().toString(),
                            delivery.channel().name(),
                            attemptNumber,
                            classification.name()));
            return;
        }

        // Non-retryable: stop immediately rather than spending budget on something that cannot
        // succeed (FR-040).
        transition(delivery, DeliveryState.FAILED, now, classification, attemptNumber);
        metrics.deliveryTerminal(DeliveryState.FAILED.name(), delivery.channel().name());
    }

    private void scheduleRetry(
            Delivery delivery,
            FailureClassification classification,
            int attemptNumber,
            Instant nextAttempt,
            Instant now) {
        delivery.state().checkTransitionTo(DeliveryState.RETRY_SCHEDULED);
        deliveries.update(
                new Delivery(
                        delivery.id(),
                        delivery.notificationId(),
                        delivery.recipientId(),
                        delivery.recipientRef(),
                        delivery.channel(),
                        DeliveryState.RETRY_SCHEDULED,
                        attemptNumber,
                        nextAttempt,
                        classification,
                        now));
    }

    /**
     * Returns a delivery stranded mid-attempt to QUEUED (FR-159).
     *
     * <p>The attempt count is <b>not</b> reset (FR-159a). A reclaim consumes nothing but neither does
     * it refund: a provider that crashes the worker on every call would otherwise produce unbounded
     * attempts while each individual reclaim looked reasonable.
     */
    private Delivery reclaim(Delivery delivery, Notification notification, Instant now) {
        Delivery queued =
                transition(delivery, DeliveryState.QUEUED, now, delivery.lastFailureClassification(),
                        delivery.attemptCount());

        audit.record(
                notification.id(),
                notification.correlationId(),
                AuditEventType.DELIVERY_RECLAIMED,
                new AuditPayload.DeliveryReclaimed(
                        delivery.id().toString(),
                        delivery.channel().name(),
                        delivery.attemptCount(),
                        now.toString()));

        log.warn(
                "Reclaimed delivery {} on channel {} after {} attempt(s): its lease expired while"
                        + " IN_PROGRESS, meaning the worker or provider stopped mid-attempt. The"
                        + " provider may already have processed that call; the idempotency key is what"
                        + " makes the repeat recognisable to it (FR-161).",
                delivery.id(),
                delivery.channel(),
                delivery.attemptCount());

        return queued;
    }

    private void expire(Delivery delivery, Notification notification, Instant now) {
        transition(delivery, DeliveryState.EXPIRED, now, delivery.lastFailureClassification(), delivery.attemptCount());
        audit.record(
                notification.id(),
                notification.correlationId(),
                AuditEventType.DELIVERY_EXPIRED,
                new AuditPayload.DeliveryExpired(
                        delivery.id().toString(),
                        delivery.channel().name(),
                        String.valueOf(notification.expiresAt()),
                        delivery.attemptCount()));
    }

    private boolean isChannelInRecordedDecision(Delivery delivery) {
        Integer matches =
                jdbc.sql(
                                """
                                SELECT count(*) FROM routing_channel_outcome o
                                JOIN routing_decision rd ON o.routing_decision_id = rd.id
                                WHERE rd.notification_id = ? AND o.recipient_id = ?
                                  AND o.channel = ? AND o.selected = true
                                """)
                        .param(delivery.notificationId())
                        .param(delivery.recipientId())
                        .param(delivery.channel().name())
                        .query(Integer.class)
                        .single();
        return matches != null && matches > 0;
    }

    private void release(Delivery delivery) {
        jdbc.sql("UPDATE delivery SET claimed_until = NULL WHERE id = ?").param(delivery.id()).update();
    }

    /** @return the delivery in its new state, so callers do not act on a stale snapshot. */
    private Delivery transition(
            Delivery delivery, DeliveryState target, Instant at, FailureClassification classification, int attempts) {
        // Principle IV: the state machine rejects an illegal transition rather than applying it.
        delivery.state().checkTransitionTo(target);
        Delivery updated =
                new Delivery(
                        delivery.id(),
                        delivery.notificationId(),
                        delivery.recipientId(),
                        delivery.recipientRef(),
                        delivery.channel(),
                        target,
                        attempts,
                        null,
                        classification,
                        at);
        deliveries.update(updated);
        return updated;
    }
}
