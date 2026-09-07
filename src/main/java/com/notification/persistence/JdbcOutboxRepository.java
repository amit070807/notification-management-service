package com.notification.persistence;

import com.notification.domain.port.OutboxRepositoryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * T043 — the transactional handoff (Principle II).
 *
 * <p>{@link #append} runs inside the acceptance transaction, so the work item commits atomically
 * with the notification it refers to. That is what closes the window where a process death between
 * commit and enqueue would lose work or create a phantom.
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepositoryPort {

    private final JdbcClient jdbc;

    public JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(UUID notificationId, Instant at) {
        jdbc.sql("INSERT INTO outbox (notification_id, created_at) VALUES (?, ?)")
                .param(notificationId)
                .param(Timestamp.from(at))
                .update();
    }

    @Override
    public List<UUID> claimUnprocessed(Instant leaseUntil, int limit) {
        return jdbc.sql(
                        """
                        WITH claimed AS (
                            SELECT id FROM outbox
                            WHERE processed_at IS NULL
                              AND (claimed_until IS NULL OR claimed_until < now())
                            ORDER BY id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE outbox o SET claimed_until = ?
                        FROM claimed WHERE o.id = claimed.id
                        RETURNING o.notification_id
                        """)
                .param(limit)
                .param(Timestamp.from(leaseUntil))
                .query(UUID.class)
                .list();
    }

    @Override
    public void markProcessed(UUID notificationId, Instant at) {
        jdbc.sql("UPDATE outbox SET processed_at = ?, claimed_until = NULL WHERE notification_id = ? AND processed_at IS NULL")
                .param(Timestamp.from(at))
                .param(notificationId)
                .update();
    }
}
