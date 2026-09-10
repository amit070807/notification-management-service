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
    private final boolean severityOrderEnabled;

    /**
     * The severity rank rendered as SQL, built once (feature 003 ADR-027).
     *
     * <p>Generated from {@code Severity} rather than written out here, because a hand-written
     * {@code CASE} would be a second declaration of the rank able to disagree with the enum invisibly —
     * both places looking right, only the delivery order wrong (FR-202).
     */
    private static final String SEVERITY_RANK_TERM =
            com.notification.domain.model.SeverityOrdering.flaggedRankTerm("n.severity");

    public JdbcDeliveryRepository(JdbcClient jdbc, FeatureFlags flags) {
        this.jdbc = jdbc;
        // Flag-gated in SQL rather than by branching between two queries, so the enabled and
        // disabled paths cannot drift apart. With the flag off the predicate is constant-false and
        // the plan is identical to phase 1 (FR-164).
        this.reclaimEnabled = flags.deliveryReclaim();
        this.severityOrderEnabled = flags.severityClaimOrder();
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

    /**
     * Claims a batch of due deliveries, highest severity first (feature 003 FR-201).
     *
     * <p>FOR UPDATE SKIP LOCKED is what makes two workers safe on one delivery: a row already locked by
     * another worker is skipped rather than waited on (Principle II). H2 was rejected in ADR-003
     * precisely because its locking semantics differ here.
     *
     * <p>Four CTEs rather than the two this used to need, and none is decoration. The claim is
     * {@code UPDATE … FROM candidate … RETURNING}, and SQL does not guarantee {@code RETURNING} preserves
     * the CTE's {@code ORDER BY} (B-05). So the intended position is recorded with {@code ROW_NUMBER()}
     * and the final projection sorts by it — otherwise selection order would be correct and processing
     * order arbitrary, which passes every test today and breaks on a plan change with no code change to
     * blame (ADR-028, FR-205).
     *
     * <p>The numbering is a <b>separate</b> CTE from the locking one because PostgreSQL rejects
     * {@code FOR UPDATE} in a query containing a window function outright: "FOR UPDATE is not allowed
     * with window functions". So {@code locked} takes the rows under the lock, in order, and
     * {@code candidate} numbers that already-locked set. Both order by the same expression, and
     * {@code locked} exposes the rank as a column so the second sort reads it rather than recomputing
     * it — one evaluation, one bound flag, no chance of the two sorts disagreeing.
     *
     * <p>Two things about this query are easy to get wrong and are load-bearing:
     *
     * <ul>
     *   <li><b>{@code FOR UPDATE OF d}</b>, not bare {@code FOR UPDATE}. Now that {@code notification} is
     *       joined, a bare lock would also lock the notification row — contending with the acceptance
     *       transaction over rows this query has no business holding. Invisible until a worker and a
     *       submitter meet.
     *   <li>The {@code WHERE} clause is <b>unchanged</b> from before severity ordering existed. FR-204
     *       permits reordering the eligible set and nothing else; a join that narrowed it would leave
     *       some delivery permanently unclaimable while every ordering test still passed.
     * </ul>
     *
     * <p>The severity term applies to the whole eligible set, reclaim branch included — decision D-16.
     * Exempting reclaims would mean a second place the rank is applied, which is what FR-202 forbids.
     */
    @Override
    public List<Delivery> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql(
                        """
                        WITH locked AS (
                            SELECT d.id, d.state_changed_at, %1$s AS severity_rank
                            FROM delivery d
                            JOIN notification n ON n.id = d.notification_id
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
                            ORDER BY severity_rank DESC, d.state_changed_at
                            LIMIT ?
                            FOR UPDATE OF d SKIP LOCKED
                        ),
                        candidate AS (
                            SELECT id,
                                   ROW_NUMBER() OVER (ORDER BY severity_rank DESC, state_changed_at) AS ord
                            FROM locked
                        ),
                        claimed AS (
                            UPDATE delivery d SET claimed_until = ?
                            FROM candidate WHERE d.id = candidate.id
                            RETURNING d.*,
                                (SELECT recipient_ref FROM recipient WHERE id = d.recipient_id) AS recipient_ref
                        )
                        SELECT claimed.* FROM claimed
                        JOIN candidate ON candidate.id = claimed.id
                        ORDER BY candidate.ord
                        """
                                .formatted(SEVERITY_RANK_TERM))
                // Parameter order follows the query text. The severity flag is bound ONCE: the rank is
                // computed as a column in `locked` and both sorts read that column, so there is no second
                // occurrence to keep in step.
                .param(severityOrderEnabled)
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
