package com.notification.application;

import com.notification.audit.AuditRecorder;
import com.notification.audit.Masking;
import com.notification.audit.payload.AuditPayload;
import com.notification.domain.model.*;
import com.notification.domain.port.ClockPort;
import com.notification.domain.port.IdPort;
import com.notification.domain.port.OutboxRepositoryPort;
import com.notification.domain.routing.*;
import com.notification.domain.state.DeliveryState;
import com.notification.domain.state.NotificationState;
import com.notification.persistence.JdbcDeliveryRepository;
import com.notification.persistence.JdbcNotificationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T044 — the acceptance transaction (Principle II, NON-NEGOTIABLE).
 *
 * <p>Everything the status API will need is committed here, in ONE transaction, before the caller
 * is told anything: the notification, its content, its recipients, the routing decision, one
 * delivery per selected (recipient, channel) pair, the audit events, and the outbox row.
 *
 * <p>The rule this enforces is that acknowledging work never outruns recording it. The common
 * failure of an accept-then-process design is a 202 for something that was never durably stored,
 * which makes the status API lie (FR-015).
 *
 * <p>No provider is touched here, and no blocking wait occurs. Delivery happens later, driven by
 * the outbox row (FR-029). The architecture gate forbids the api package from even reaching the
 * channel adapters, so this cannot regress quietly.
 */
@Service
public class SubmissionService {

    private final JdbcNotificationRepository notifications;
    private final JdbcDeliveryRepository deliveries;
    private final OutboxRepositoryPort outbox;
    private final AuditRecorder audit;
    private final ClockPort clock;
    private final IdPort ids;
    private final RoutingPolicy policy;

    public SubmissionService(
            JdbcNotificationRepository notifications,
            JdbcDeliveryRepository deliveries,
            OutboxRepositoryPort outbox,
            AuditRecorder audit,
            ClockPort clock,
            IdPort ids,
            RoutingPolicy policy) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
        this.ids = ids;
        this.policy = policy;
    }

    public record Accepted(UUID id, String clientNotificationId, NotificationState state, Instant receivedAt) {}

    public record Command(
            String clientNotificationId,
            String sourceSystem,
            String correlationId,
            NotificationType type,
            Severity severity,
            Priority priority,
            List<String> recipients,
            List<Channel> requestedChannels,
            Instant createdAt,
            Instant notBefore,
            Instant expiresAt,
            byte[] contentPayload) {}

    @Transactional
    public Accepted accept(Command cmd) {
        Instant now = clock.now();
        UUID notificationId = ids.newId();

        // 1. Content, stored separately so it has its own grant and can be purged independently
        //    if G-25 is ever resolved toward minimisation.
        UUID contentId = ids.newId();
        String payloadRef = nonReversibleRef(cmd.contentPayload());
        notifications.saveContent(contentId, cmd.contentPayload(), payloadRef);
        ContentRef content = new ContentRef(contentId, payloadRef, cmd.contentPayload().length);

        List<RecipientRef> recipients = cmd.recipients().stream().map(RecipientRef::new).toList();

        Notification notification =
                new Notification(
                        notificationId,
                        cmd.clientNotificationId(),
                        cmd.sourceSystem(),
                        cmd.correlationId(),
                        cmd.type(),
                        cmd.severity(),
                        cmd.priority(),
                        content,
                        recipients,
                        cmd.requestedChannels(),
                        cmd.createdAt(),
                        now,
                        cmd.notBefore(),
                        cmd.expiresAt(),
                        NotificationState.ACCEPTED,
                        now);
        notifications.save(notification);

        // 2. Recipients.
        List<UUID> recipientRowIds = recipients.stream().map(r -> ids.newId()).toList();
        Map<RecipientRef, UUID> recipientIds =
                notifications.saveRecipients(notificationId, recipients, recipientRowIds);

        // 3. Routing — a pure function, with identifiers and time supplied from outside so the
        //    domain needs no clock or id port of its own.
        RoutingDecision decision =
                ChannelRouter.route(
                        ids.newId(),
                        notificationId,
                        now,
                        new RoutingRequest(cmd.severity(), cmd.requestedChannels(), recipients),
                        policy);
        List<UUID> outcomeIds = decision.outcomes().stream().map(o -> ids.newId()).toList();
        notifications.saveRoutingDecision(decision, recipientIds, outcomeIds);

        // 4. One delivery per SELECTED (recipient, channel) pair. A delivery for a channel absent
        //    from the decision would be unattemptable anyway (FR-035).
        List<Delivery> pending =
                decision.outcomes().stream()
                        .filter(ChannelOutcome::selected)
                        .map(
                                o ->
                                        new Delivery(
                                                ids.newId(),
                                                notificationId,
                                                recipientIds.get(o.recipient()),
                                                o.recipient(),
                                                o.channel(),
                                                DeliveryState.PENDING,
                                                0,
                                                null,
                                                null,
                                                now))
                        .toList();
        deliveries.saveAll(pending);

        // 5. Audit.
        audit.record(
                notificationId,
                cmd.correlationId(),
                AuditEventType.NOTIFICATION_ACCEPTED,
                new AuditPayload.NotificationAccepted(
                        cmd.clientNotificationId(),
                        cmd.sourceSystem(),
                        recipients.size(),
                        cmd.requestedChannels().size(),
                        payloadRef));
        audit.record(
                notificationId,
                cmd.correlationId(),
                AuditEventType.ROUTING_DECISION_MADE,
                new AuditPayload.RoutingDecisionMade(
                        decision.policyVersion(),
                        decision.selectedChannels().stream().map(Enum::name).collect(Collectors.joining(",")),
                        decision.outcomes().size()));
        for (Delivery d : pending) {
            audit.record(
                    notificationId,
                    cmd.correlationId(),
                    AuditEventType.DELIVERY_QUEUED,
                    new AuditPayload.DeliveryQueued(
                            d.id().toString(), Masking.mask(d.recipientRef().value()), d.channel().name()));
        }

        // 6. The handoff — last, and inside the same transaction.
        outbox.append(notificationId, now);

        return new Accepted(notificationId, cmd.clientNotificationId(), NotificationState.ACCEPTED, now);
    }

    /** A derived, non-reversible reference so audit can cite content without containing it (FR-051). */
    private static String nonReversibleRef(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return "sha256:" + java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
