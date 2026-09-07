package com.notification.persistence;

import com.notification.domain.model.*;
import com.notification.domain.port.NotificationRepositoryPort;
import com.notification.domain.routing.RoutingDecision;
import com.notification.domain.state.NotificationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** T042 — notification, content, recipient and routing-decision persistence. */
@Repository
public class JdbcNotificationRepository implements NotificationRepositoryPort {

    private final JdbcClient jdbc;

    public JdbcNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void saveContent(UUID id, byte[] payload, String payloadRef) {
        jdbc.sql(
                        """
                        INSERT INTO notification_content (id, payload, payload_size_bytes, payload_ref)
                        VALUES (:id, :payload, :size, :ref)
                        """)
                .param("id", id)
                .param("payload", payload)
                .param("size", payload.length)
                .param("ref", payloadRef)
                .update();
    }

    @Override
    public void save(Notification n) {
        jdbc.sql(
                        """
                        INSERT INTO notification
                            (id, client_notification_id, source_system, correlation_id, notification_type,
                             severity, priority, content_ref, created_at_client, received_at,
                             not_before_at, expires_at, state, state_changed_at)
                        VALUES (:id, :clientId, :source, :corr, :type, :sev, :prio, :content,
                                :createdClient, :received, :notBefore, :expires, :state, :stateAt)
                        """)
                .param("id", n.id())
                .param("clientId", n.clientNotificationId())
                .param("source", n.sourceSystem())
                .param("corr", n.correlationId())
                .param("type", n.type().name())
                .param("sev", n.severity().name())
                .param("prio", n.priority().name())
                .param("content", n.content().id())
                .param("createdClient", Timestamp.from(n.createdAtClient()))
                .param("received", Timestamp.from(n.receivedAt()))
                .param("notBefore", n.notBefore() == null ? null : Timestamp.from(n.notBefore()))
                .param("expires", n.expiresAt() == null ? null : Timestamp.from(n.expiresAt()))
                .param("state", n.state().name())
                .param("stateAt", Timestamp.from(n.stateChangedAt()))
                .update();
    }

    /** @return recipient row ids, in the order supplied, so deliveries can reference them. */
    public Map<RecipientRef, UUID> saveRecipients(UUID notificationId, List<RecipientRef> recipients, List<UUID> ids) {
        Map<RecipientRef, UUID> assigned = new LinkedHashMap<>();
        for (int i = 0; i < recipients.size(); i++) {
            UUID rowId = ids.get(i);
            RecipientRef ref = recipients.get(i);
            jdbc.sql("INSERT INTO recipient (id, notification_id, recipient_ref) VALUES (?, ?, ?)")
                    .param(rowId)
                    .param(notificationId)
                    .param(ref.value())
                    .update();
            assigned.put(ref, rowId);
        }
        return assigned;
    }

    public void saveRoutingDecision(RoutingDecision decision, Map<RecipientRef, UUID> recipientIds, List<UUID> outcomeIds) {
        jdbc.sql(
                        "INSERT INTO routing_decision (id, notification_id, policy_version, decided_at) "
                                + "VALUES (?, ?, ?, ?)")
                .param(decision.id())
                .param(decision.notificationId())
                .param(decision.policyVersion())
                .param(Timestamp.from(decision.decidedAt()))
                .update();

        for (int i = 0; i < decision.outcomes().size(); i++) {
            var o = decision.outcomes().get(i);
            jdbc.sql(
                            """
                            INSERT INTO routing_channel_outcome
                                (id, routing_decision_id, recipient_id, channel, selected, reason_code)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)
                    .param(outcomeIds.get(i))
                    .param(decision.id())
                    .param(recipientIds.get(o.recipient()))
                    .param(o.channel().name())
                    .param(o.selected())
                    .param(o.reasonCode().name())
                    .update();
        }
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        return jdbc.sql("SELECT * FROM notification WHERE id = ?").param(id).query(this::map).optional();
    }

    @Override
    public List<Notification> findByClientNotificationId(String clientNotificationId) {
        // Deliberately returns a list: the identifier is not unique (FR-008b, spec D4).
        return jdbc.sql("SELECT * FROM notification WHERE client_notification_id = ? ORDER BY received_at")
                .param(clientNotificationId)
                .query(this::map)
                .list();
    }

    @Override
    public void updateState(UUID id, NotificationState state, Instant at) {
        jdbc.sql("UPDATE notification SET state = ?, state_changed_at = ? WHERE id = ?")
                .param(state.name())
                .param(Timestamp.from(at))
                .param(id)
                .update();
    }

    private Notification map(ResultSet rs, int rowNum) throws SQLException {
        return new Notification(
                rs.getObject("id", UUID.class),
                rs.getString("client_notification_id"),
                rs.getString("source_system"),
                rs.getString("correlation_id"),
                NotificationType.valueOf(rs.getString("notification_type")),
                Severity.valueOf(rs.getString("severity")),
                Priority.valueOf(rs.getString("priority")),
                new ContentRef(rs.getObject("content_ref", UUID.class), "", 0),
                List.of(),
                List.of(),
                rs.getTimestamp("created_at_client").toInstant(),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("not_before_at") == null ? null : rs.getTimestamp("not_before_at").toInstant(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                NotificationState.valueOf(rs.getString("state")),
                rs.getTimestamp("state_changed_at").toInstant());
    }
}
