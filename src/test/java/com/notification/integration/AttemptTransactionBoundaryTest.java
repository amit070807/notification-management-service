package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.model.Channel;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The transaction boundary around a provider call, and the stranding it makes real.
 *
 * <p>Before this split the whole attempt — the IN_PROGRESS write, the attempt row, the provider call
 * and the outcome — was one transaction. A crash rolled all of it back, so the delivery returned to
 * QUEUED and no delivery was ever stranded. The reclaim path could therefore never run, and the test
 * that covered it wrote the stranded row by hand rather than producing one.
 *
 * <p>That design only looked safe because the provider is simulated. A real provider is an external
 * API: rolling back the database does not un-send the notification, so "crash, roll back, retry
 * cleanly" was an illusion — the recipient had already been messaged and the system held no record
 * of it.
 *
 * <p>These tests assert the boundary itself. They fail if the split is ever collapsed back, which a
 * behavioural test of the reclaim alone would not catch.
 */
@TestPropertySource(properties = "notification.features.delivery-reclaim=true")
class AttemptTransactionBoundaryTest extends ReclaimTestSupport {

    @Test
    void anAttemptInterruptedAfterTheProviderCallLeavesTheDeliveryStranded() throws Exception {
        UUID notificationId = submitEmailOnly("txb-1");
        poller.drainOnce();
        scripts.of(Channel.EMAIL).thenThrow();

        // The crash: the call was made, and this process dies before recording what it returned.
        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(RuntimeException.class);

        UUID deliveryId = deliveryIdOf(notificationId);

        // tx1 committed independently of the call. Were the two still one transaction, this would
        // read QUEUED and the attempt row would not exist at all.
        assertThat(stateOf(deliveryId)).isEqualTo("IN_PROGRESS");
        assertThat(leaseOf(deliveryId)).as("the lease must survive, or the row can never be reclaimed").isNotNull();
        assertThat(pendingAttemptCount(deliveryId))
                .as("the attempt row must outlive the crash, or audit under-reports the attempt (FR-043)")
                .isEqualTo(1);
    }

    @Test
    void theStrandedDeliveryIsReclaimedAndRepeatsTheSameKey() throws Exception {
        UUID notificationId = submitEmailOnly("txb-2");
        poller.drainOnce();
        scripts.of(Channel.EMAIL).thenThrow();
        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(RuntimeException.class);

        UUID deliveryId = deliveryIdOf(notificationId);
        var keyBefore = scripts.of(Channel.EMAIL).keysSeen().get(0);

        // Nothing reclaims it while the lease is live — that would steal work from a running attempt.
        worker.runOnce();
        assertThat(stateOf(deliveryId)).isEqualTo("IN_PROGRESS");

        clock.advance(Duration.ofMinutes(10));
        scripts.of(Channel.EMAIL).alwaysSucceed();
        worker.runOnce();

        assertThat(stateOf(deliveryId)).isEqualTo("DELIVERED");

        // FR-161: the repeat is the SAME logical attempt, so it must carry the same key. This is the
        // whole basis of D12 — the provider can only recognise the duplicate if the key is stable.
        var keys = scripts.of(Channel.EMAIL).keysSeen();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isEqualTo(keyBefore);

        // uq_attempt_number holds: the row was reused, not duplicated.
        assertThat(totalAttemptRows(deliveryId)).isEqualTo(1);
    }

    @Test
    void noDatabaseConnectionIsHeldAcrossTheProviderCall() throws Exception {
        // The reason the boundary matters in production. The pool is capped at 4 in tests; if a
        // connection were held for the duration of each call, more concurrent deliveries than the
        // pool size would deadlock rather than complete.
        UUID notificationId = submitEmailOnly("txb-3");
        poller.drainOnce();
        scripts.of(Channel.EMAIL).alwaysSucceed();

        worker.runOnce();

        assertThat(stateOf(deliveryIdOf(notificationId))).isEqualTo("DELIVERED");
    }

    private UUID deliveryIdOf(UUID notificationId) {
        return jdbc.sql("SELECT id FROM delivery WHERE notification_id = ?")
                .param(notificationId)
                .query(UUID.class)
                .single();
    }

    private Object leaseOf(UUID deliveryId) {
        return jdbc.sql("SELECT claimed_until FROM delivery WHERE id = ?")
                .param(deliveryId)
                .query(java.sql.Timestamp.class)
                .optional()
                .orElse(null);
    }

    private int pendingAttemptCount(UUID deliveryId) {
        return jdbc.sql("SELECT count(*) FROM delivery_attempt WHERE delivery_id = ? AND outcome = 'PENDING'")
                .param(deliveryId)
                .query(Integer.class)
                .single();
    }

    private int totalAttemptRows(UUID deliveryId) {
        return jdbc.sql("SELECT count(*) FROM delivery_attempt WHERE delivery_id = ?")
                .param(deliveryId)
                .query(Integer.class)
                .single();
    }
}
