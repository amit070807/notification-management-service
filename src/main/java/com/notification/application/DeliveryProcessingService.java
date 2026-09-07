package com.notification.application;

import com.notification.audit.AuditRecorder;
import com.notification.audit.Masking;
import com.notification.audit.payload.AuditPayload;
import com.notification.domain.model.*;
import com.notification.domain.port.*;
import com.notification.domain.retry.FailureClassification;
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

    private final DeliveryRepositoryPort deliveries;
    private final NotificationRepositoryPort notifications;
    private final Map<Channel, ChannelProviderPort> providers;
    private final AuditRecorder audit;
    private final ClockPort clock;
    private final IdPort ids;
    private final JdbcClient jdbc;

    public DeliveryProcessingService(
            DeliveryRepositoryPort deliveries,
            NotificationRepositoryPort notifications,
            List<ChannelProviderPort> providerList,
            AuditRecorder audit,
            ClockPort clock,
            IdPort ids,
            JdbcClient jdbc) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.providers =
                providerList.stream().collect(Collectors.toMap(ChannelProviderPort::channel, Function.identity()));
        this.audit = audit;
        this.clock = clock;
        this.ids = ids;
        this.jdbc = jdbc;
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
            // Phase 6: every failure is terminal. Phase 7 introduces bounded retry.
            transition(inProgress, DeliveryState.FAILED, finished, outcome.classification(), attemptNumber);
            audit.record(
                    notification.id(),
                    notification.correlationId(),
                    AuditEventType.DELIVERY_FAILED,
                    new AuditPayload.DeliveryFailed(
                            delivery.id().toString(),
                            Masking.mask(delivery.recipientRef().value()),
                            delivery.channel().name(),
                            attemptNumber,
                            outcome.classification().name(),
                            outcome.diagnostic()));
        }
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
