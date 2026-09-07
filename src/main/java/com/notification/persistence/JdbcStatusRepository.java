package com.notification.persistence;

import com.notification.api.dto.NotificationStatusResponse.ChannelOutcomeDto;
import com.notification.api.dto.NotificationStatusResponse.DeliveryStatusDto;
import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import com.notification.domain.routing.RoutingReasonCode;
import com.notification.domain.state.DeliveryState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * T054 — status read queries.
 *
 * <p>Note what these do NOT do: they never join {@code notification_content}, so the payload
 * cannot reach a status response even by accident (FR-056). Delivery state is read from its stored
 * column rather than reconstructed from audit history, which FR-014 requires — deriving it would
 * make the read cost grow with the audit trail and would couple two independent concerns.
 */
@Repository
public class JdbcStatusRepository {

    private final JdbcClient jdbc;

    public JdbcStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DeliveryStatusDto> deliveriesFor(UUID notificationId) {
        return jdbc.sql(
                        """
                        SELECT r.recipient_ref, d.channel, d.state, d.attempt_count,
                               d.last_failure_classification, d.next_attempt_at, d.state_changed_at
                        FROM delivery d
                        JOIN recipient r ON d.recipient_id = r.id
                        WHERE d.notification_id = ?
                        ORDER BY r.recipient_ref, d.channel
                        """)
                .param(notificationId)
                .query(this::mapDelivery)
                .list();
    }

    public List<ChannelOutcomeDto> outcomesFor(UUID notificationId) {
        return jdbc.sql(
                        """
                        SELECT r.recipient_ref, o.channel, o.selected, o.reason_code
                        FROM routing_channel_outcome o
                        JOIN routing_decision rd ON o.routing_decision_id = rd.id
                        JOIN recipient r ON o.recipient_id = r.id
                        WHERE rd.notification_id = ?
                        ORDER BY r.recipient_ref, o.channel
                        """)
                .param(notificationId)
                .query(this::mapOutcome)
                .list();
    }

    public Optional<String> policyVersionFor(UUID notificationId) {
        return jdbc.sql("SELECT policy_version FROM routing_decision WHERE notification_id = ?")
                .param(notificationId)
                .query(String.class)
                .optional();
    }

    private DeliveryStatusDto mapDelivery(ResultSet rs, int rowNum) throws SQLException {
        String cls = rs.getString("last_failure_classification");
        return new DeliveryStatusDto(
                rs.getString("recipient_ref"),
                Channel.valueOf(rs.getString("channel")),
                DeliveryState.valueOf(rs.getString("state")),
                rs.getInt("attempt_count"),
                cls == null ? null : FailureClassification.valueOf(cls),
                rs.getTimestamp("next_attempt_at") == null ? null : rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getTimestamp("state_changed_at").toInstant());
    }

    private ChannelOutcomeDto mapOutcome(ResultSet rs, int rowNum) throws SQLException {
        return new ChannelOutcomeDto(
                rs.getString("recipient_ref"),
                Channel.valueOf(rs.getString("channel")),
                rs.getBoolean("selected"),
                RoutingReasonCode.valueOf(rs.getString("reason_code")));
    }
}
