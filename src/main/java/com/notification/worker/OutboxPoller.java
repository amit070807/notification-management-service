package com.notification.worker;

import com.notification.audit.AuditRecorder;
import com.notification.domain.port.ClockPort;
import com.notification.domain.port.OutboxRepositoryPort;
import com.notification.domain.state.DeliveryState;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T075 — drains the transactional outbox (Principle II).
 *
 * <p>Moves each accepted notification's deliveries from PENDING to QUEUED. Claiming uses
 * {@code FOR UPDATE SKIP LOCKED}, so a second instance can run without coordination.
 *
 * <p>{@link #drainOnce()} is public and invoked directly by tests. The scheduled trigger merely
 * calls it — Principle VI forbids tests that wait for a scheduler, since those prove timing rather
 * than behaviour and are flaky when they fail.
 */
@Component
public class OutboxPoller {

    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final int BATCH = 100;

    private final OutboxRepositoryPort outbox;
    private final JdbcClient jdbc;
    private final ClockPort clock;

    public OutboxPoller(OutboxRepositoryPort outbox, JdbcClient jdbc, ClockPort clock, AuditRecorder audit) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification.worker.poll-interval-ms:1000}")
    public void scheduled() {
        drainOnce();
    }

    @Transactional
    public int drainOnce() {
        List<UUID> notificationIds = outbox.claimUnprocessed(clock.now().plus(LEASE), BATCH);
        for (UUID id : notificationIds) {
            jdbc.sql(
                            "UPDATE delivery SET state = ?, state_changed_at = ? "
                                    + "WHERE notification_id = ? AND state = ?")
                    .param(DeliveryState.QUEUED.name())
                    .param(java.sql.Timestamp.from(clock.now()))
                    .param(id)
                    .param(DeliveryState.PENDING.name())
                    .update();
            outbox.markProcessed(id, clock.now());
        }
        return notificationIds.size();
    }
}
