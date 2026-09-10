package com.notification.application;

import com.notification.audit.AuditRecorder;
import com.notification.audit.Masking;
import com.notification.audit.payload.AuditPayload;
import com.notification.domain.model.*;
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

        UUID attemptId = ids.newId();
        jdbc.sql(
                        "INSERT INTO delivery_attempt (id, delivery_id, attempt_number, started_at, outcome) "
                                + "VALUES (?, ?, ?, ?, 'PENDING')")
                .param(attemptId)
                .param(delivery.id())
                .param(attemptNumber)
                .param(Timestamp.from(now))
                .update();

        DeliveryOutcome outcome =
                providers
                        .get(delivery.channel())
                        .send(delivery.recipientRef(), notification.content());

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
