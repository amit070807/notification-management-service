package com.notification.persistence;

import com.notification.config.FeatureFlags;
import com.notification.domain.model.Channel;
import com.notification.domain.model.Delivery;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.port.DeliveryRepositoryPort;
import com.notification.domain.retry.FailureClassification;
import com.notification.domain.state.DeliveryState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** T042/T076 — delivery persistence and the concurrent worker claim (Principle II). */
@Repository
public class JdbcDeliveryRepository implements DeliveryRepositoryPort {

    private final JdbcClient jdbc;
    private final boolean reclaimEnabled;

    public JdbcDeliveryRepository(JdbcClient jdbc, FeatureFlags flags) {
        this.jdbc = jdbc;
        // Flag-gated in SQL rather than by branching between two queries, so the enabled and
        // disabled paths cannot drift apart. With the flag off the predicate is constant-false and
        // the plan is identical to phase 1 (FR-164).
        this.reclaimEnabled = flags.deliveryReclaim();
    }

    @Override
    public void saveAll(List<Delivery> deliveries) {
        for (Delivery d : deliveries) {
            jdbc.sql(
                            """
                            INSERT INTO delivery
                                (id, notification_id, recipient_id, channel, state, attempt_count,
                                 next_attempt_at, last_failure_classification, state_changed_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .param(d.id())
                    .param(d.notificationId())
                    .param(d.recipientId())
                    .param(d.channel().name())
                    .param(d.state().name())
                    .param(d.attemptCount())
                    .param(d.nextAttemptAt() == null ? null : Timestamp.from(d.nextAttemptAt()))
                    .param(d.lastFailureClassification() == null ? null : d.lastFailureClassification().name())
                    .param(Timestamp.from(d.stateChangedAt()))
                    .update();
        }
    }

    @Override
    public List<Delivery> findByNotificationId(UUID notificationId) {
        return jdbc.sql(
                        """
                        SELECT d.*, r.recipient_ref FROM delivery d
                        JOIN recipient r ON d.recipient_id = r.id
                        WHERE d.notification_id = ? ORDER BY r.recipient_ref, d.channel
                        """)
                .param(notificationId)
                .query(this::map)
                .list();
    }

    @Override
    public List<Delivery> claimDue(Instant now, Instant leaseUntil, int limit) {
        // FOR UPDATE SKIP LOCKED is what makes two workers safe on one delivery: a row already
        // locked by another worker is skipped rather than waited on (Principle II). H2 was
        // rejected in ADR-003 precisely because its locking semantics differ here.
        return jdbc.sql(
                        """
                        WITH claimed AS (
                            SELECT d.id FROM delivery d
                            WHERE (
                                    d.state IN ('QUEUED', 'RETRY_SCHEDULED')
                                    AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= ?)
                                    AND (d.claimed_until IS NULL OR d.claimed_until < ?)
                                  )
                               OR (
                                    -- Feature 002 T047/FR-159: reclaim a delivery stranded
                                    -- mid-attempt. IN_PROGRESS with an EXPIRED lease means the
                                    -- worker that held it is gone; without this the row is never
                                    -- re-claimed and the notification reports IN_PROGRESS forever.
                                    --
                                    -- claimed_until IS NOT NULL is required: a row mid-attempt under
                                    -- a live worker has its lease NULLed by update(), and reclaiming
                                    -- that would steal work from a running attempt.
                                    ? = true
                                    AND d.state = 'IN_PROGRESS'
                                    AND d.claimed_until IS NOT NULL
                                    AND d.claimed_until < ?
                                  )
                            ORDER BY d.state_changed_at
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE delivery d SET claimed_until = ?
                        FROM claimed WHERE d.id = claimed.id
                        RETURNING d.*, (SELECT recipient_ref FROM recipient WHERE id = d.recipient_id) AS recipient_ref
                        """)
                .param(Timestamp.from(now))
                .param(Timestamp.from(now))
                .param(reclaimEnabled)
                .param(Timestamp.from(now))
                .param(limit)
                .param(Timestamp.from(leaseUntil))
                .query(this::map)
                .list();
    }

    @Override
    public void updateKeepingLease(Delivery d) {
        // Identical to update() except that claimed_until is left alone. The attempt is still
        // running; the lease is what says so, and what lets a crash be recognised later.
        jdbc.sql(
                        """
                        UPDATE delivery SET state = ?, attempt_count = ?, next_attempt_at = ?,
                            last_failure_classification = ?, state_changed_at = ?
                        WHERE id = ?
                        """)
                .param(d.state().name())
                .param(d.attemptCount())
                .param(d.nextAttemptAt() == null ? null : Timestamp.from(d.nextAttemptAt()))
                .param(d.lastFailureClassification() == null ? null : d.lastFailureClassification().name())
                .param(Timestamp.from(d.stateChangedAt()))
                .param(d.id())
                .update();
    }

    @Override
    public void update(Delivery d) {
        jdbc.sql(
                        """
                        UPDATE delivery SET state = ?, attempt_count = ?, next_attempt_at = ?,
                            last_failure_classification = ?, state_changed_at = ?, claimed_until = NULL
                        WHERE id = ?
                        """)
                .param(d.state().name())
                .param(d.attemptCount())
                .param(d.nextAttemptAt() == null ? null : Timestamp.from(d.nextAttemptAt()))
                .param(d.lastFailureClassification() == null ? null : d.lastFailureClassification().name())
                .param(Timestamp.from(d.stateChangedAt()))
                .param(d.id())
                .update();
    }

    private Delivery map(ResultSet rs, int rowNum) throws SQLException {
        String cls = rs.getString("last_failure_classification");
        return new Delivery(
                rs.getObject("id", UUID.class),
                rs.getObject("notification_id", UUID.class),
                rs.getObject("recipient_id", UUID.class),
                new RecipientRef(rs.getString("recipient_ref")),
                Channel.valueOf(rs.getString("channel")),
                DeliveryState.valueOf(rs.getString("state")),
                rs.getInt("attempt_count"),
                rs.getTimestamp("next_attempt_at") == null ? null : rs.getTimestamp("next_attempt_at").toInstant(),
                cls == null ? null : FailureClassification.valueOf(cls),
                rs.getTimestamp("state_changed_at").toInstant());
    }
}
