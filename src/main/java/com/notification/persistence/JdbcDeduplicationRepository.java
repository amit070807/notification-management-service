package com.notification.persistence;

import com.notification.domain.dedup.DeduplicationDecision;
import com.notification.domain.dedup.DeduplicationKey;
import com.notification.domain.state.NotificationState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * T052 — the boundary lookup and the suppression record (FR-141, FR-143).
 *
 * <p>The lookup returns the <b>most recent</b> notification sharing the key and lets
 * {@link DeduplicationDecision} judge it. Pushing the window and terminal-state rules into SQL would
 * have hidden them from the truth-table test and split the boundary across two places.
 *
 * <p>Note what the query does not do: it does not filter by state. A terminally failed original must be
 * <i>seen</i> and then rejected as a suppressor (FR-141c), because a query that skipped it would look
 * past a recent failure to an older success and suppress on that instead.
 */
@Repository
public class JdbcDeduplicationRepository {

    private final JdbcClient jdbc;

    public JdbcDeduplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the most recent notification sharing the key, whatever its state */
    public Optional<DeduplicationDecision.Existing> findMostRecent(DeduplicationKey key) {
        return jdbc.sql(
                        """
                        SELECT id, received_at, state
                        FROM notification
                        WHERE source_system = :source AND correlation_id = :correlation
                        ORDER BY received_at DESC
                        LIMIT 1
                        """)
                .param("source", key.sourceSystem())
                .param("correlation", key.correlationId())
                .query(
                        (rs, rowNum) ->
                                new DeduplicationDecision.Existing(
                                        rs.getObject("id", UUID.class),
                                        rs.getTimestamp("received_at").toInstant(),
                                        NotificationState.valueOf(rs.getString("state"))))
                .optional();
    }

    /** Records a suppression. Append-only in practice; nothing updates or deletes these rows. */
    public void recordSuppression(
            UUID id,
            DeduplicationKey key,
            UUID originalNotificationId,
            String clientNotificationId,
            Instant suppressedAt) {

        jdbc.sql(
                        """
                        INSERT INTO notification_suppression
                            (id, source_system, correlation_id, suppressed_at,
                             original_notification_id, client_notification_id)
                        VALUES (:id, :source, :correlation, :at, :original, :clientId)
                        """)
                .param("id", id)
                .param("source", key.sourceSystem())
                .param("correlation", key.correlationId())
                .param("at", Timestamp.from(suppressedAt))
                .param("original", originalNotificationId)
                .param("clientId", clientNotificationId)
                .update();
    }
}
