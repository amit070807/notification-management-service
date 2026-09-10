package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T035 — a delivery stranded mid-attempt is reclaimed and reaches a terminal state (FR-159, B-13).
 *
 * <p>B-13 is the defect this closes: {@code claimDue} claimed only {@code QUEUED} and
 * {@code RETRY_SCHEDULED}, so an {@code IN_PROGRESS} row whose worker died was never picked up again
 * by anything. Not a duplicate send — a silent permanent loss, which is worse, because the status
 * endpoint reports it as still in progress forever.
 */
@TestPropertySource(properties = "notification.features.delivery-reclaim=true")
class StrandedDeliveryReclaimTest extends ReclaimTestSupport {

    @Test
    void aStrandedDeliveryIsReclaimedAndDelivered() throws Exception {
        UUID notificationId = submitEmailOnly("reclaim-1");
        poller.drainOnce();
        UUID deliveryId = strandMidAttempt(notificationId);
        scripts.of(Channel.EMAIL).alwaysSucceed();

        worker.runOnce();

        assertThat(stateOf(deliveryId)).isEqualTo("DELIVERED");
    }

    @Test
    void theReclaimIsAudited() throws Exception {
        UUID notificationId = submitEmailOnly("reclaim-2");
        poller.drainOnce();
        strandMidAttempt(notificationId);
        scripts.of(Channel.EMAIL).alwaysSucceed();

        worker.runOnce();

        // FR-159 requires the recovery to be visible. Without this record an operator sees a delivery
        // that succeeded on "attempt 1" and no trace that it was ever stuck, which hides both the
        // crash and the fact that the provider may have been called twice.
        assertThat(reclaimAuditCount(notificationId)).isEqualTo(1);
    }

    @Test
    void aLiveLeaseIsNotReclaimed() throws Exception {
        UUID notificationId = submitEmailOnly("reclaim-3");
        poller.drainOnce();
        UUID deliveryId = strandMidAttempt(notificationId);

        // Push the lease into the future: this is a worker still working, not a crash.
        jdbc.sql("UPDATE delivery SET claimed_until = ? WHERE id = ?")
                .param(java.sql.Timestamp.from(clock.now().plus(java.time.Duration.ofMinutes(10))))
                .param(deliveryId)
                .update();

        worker.runOnce();

        // The failure mode this guards is the one B-13 was mistaken for: reclaiming an ACTIVE attempt
        // would produce a genuine concurrent double send, turning a loss bug into a duplication bug.
        assertThat(stateOf(deliveryId)).isEqualTo("IN_PROGRESS");
        assertThat(reclaimAuditCount(notificationId)).isZero();
    }
}
