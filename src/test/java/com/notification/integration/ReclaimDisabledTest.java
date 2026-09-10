package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * With reclaim off, a stranded delivery stays stranded — the phase-1 baseline (FR-105, B-13).
 *
 * <p>Asserting a <b>defect</b> is preserved looks perverse, and it is the point: FR-105 makes flags-off
 * mean bit-for-bit phase-1 behaviour, so this is the test that proves the flag is really the switch.
 * If reclaim leaked past it, the rollback path would not exist.
 */
@TestPropertySource(properties = "notification.features.delivery-reclaim=false")
class ReclaimDisabledTest extends ReclaimTestSupport {

    @Test
    void aStrandedDeliveryIsLeftAlone() throws Exception {
        UUID notificationId = submitEmailOnly("no-reclaim");
        poller.drainOnce();
        UUID deliveryId = strandMidAttempt(notificationId);

        worker.runOnce();

        assertThat(stateOf(deliveryId)).isEqualTo("IN_PROGRESS");
        assertThat(reclaimAuditCount(notificationId)).isZero();
    }
}
