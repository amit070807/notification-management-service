package com.notification.integration;

import com.notification.domain.model.Channel;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared plumbing for the reclaim tests (T035–T038).
 *
 * <p>{@link #strandMidAttempt} strands a delivery by <b>actually crashing an attempt</b>: the provider
 * is scripted to throw after the call has been made, which is what a worker or provider dying
 * mid-attempt looks like once the provider call sits outside a transaction.
 *
 * <p>It previously wrote the stranded row by hand, on the reasoning that a real crash would roll the
 * transaction back and leave nothing stranded. That reasoning was correct about the code as it then
 * stood — and that was the problem. The whole attempt was one transaction, so no delivery could ever
 * strand, the reclaim path was unreachable, and a hand-written row was the only way to reach it. The
 * fixture was describing a state the system could not produce, so the tests passed while proving
 * nothing.
 *
 * <p>Now that tx1 commits before the provider is called, the crash is real and so is the row. Note the
 * consequence for callers: a stranded delivery <b>has already had a provider call</b>. That is the
 * defining fact about it, and any assertion that no call was made is asserting the fabrication rather
 * than the failure.
 */
public abstract class ReclaimTestSupport extends RetryTestSupport {

    @Autowired protected JdbcClient jdbc;

    /**
     * Crashes an attempt for real and leaves the delivery stranded.
     *
     * <p>Advances the clock past the worker lease before returning, so the delivery is immediately
     * reclaimable — callers are testing what happens to a stranded delivery, not waiting for one.
     *
     * @return the id of the stranded delivery
     */
    protected UUID strandMidAttempt(UUID notificationId) {
        UUID deliveryId =
                jdbc.sql("SELECT id FROM delivery WHERE notification_id = ?")
                        .param(notificationId)
                        .query(UUID.class)
                        .single();

        scripts.of(Channel.EMAIL).thenThrow();
        try {
            worker.runOnce();
        } catch (RuntimeException expected) {
            // The crash. tx1 stands; the outcome was never recorded.
        }

        // Past the lease, so the row is recognisably abandoned rather than merely in flight.
        clock.advance(Duration.ofMinutes(10));
        return deliveryId;
    }

    protected String stateOf(UUID deliveryId) {
        return jdbc.sql("SELECT state FROM delivery WHERE id = ?")
                .param(deliveryId)
                .query(String.class)
                .single();
    }

    protected int attemptCountOf(UUID deliveryId) {
        return jdbc.sql("SELECT attempt_count FROM delivery WHERE id = ?")
                .param(deliveryId)
                .query(Integer.class)
                .single();
    }

    protected int reclaimAuditCount(UUID notificationId) {
        return jdbc.sql(
                        "SELECT count(*) FROM audit_event WHERE notification_id = ? "
                                + "AND event_type = 'DELIVERY_RECLAIMED'")
                .param(notificationId)
                .query(Integer.class)
                .single();
    }

    protected UUID recipientIdOf(UUID deliveryId) {
        return jdbc.sql("SELECT recipient_id FROM delivery WHERE id = ?")
                .param(deliveryId)
                .query(UUID.class)
                .single();
    }

    protected static final Channel ONLY_CHANNEL = Channel.EMAIL;
}
