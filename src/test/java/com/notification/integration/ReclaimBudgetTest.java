package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T036 — a reclaim consumes budget; it does not refund it (FR-159a).
 *
 * <p>The failure mode: a provider that crashes the worker on every call. If reclaim reset the attempt
 * count, each individual reclaim would look reasonable while the delivery attempted forever — retry
 * would be unbounded through a side door, which section 4.5 forbids. Bounding retries and then
 * exempting the recovery path would be an unbounded loop wearing a bound's clothes.
 */
@TestPropertySource(properties = "notification.features.delivery-reclaim=true")
class ReclaimBudgetTest extends ReclaimTestSupport {

    @Test
    void aReclaimDoesNotResetTheAttemptCount() throws Exception {
        UUID notificationId = submitEmailOnly("reclaim-budget-1");
        poller.drainOnce();
        UUID deliveryId = strandMidAttempt(notificationId);

        // Two attempts already spent before the crash.
        jdbc.sql("UPDATE delivery SET attempt_count = 2 WHERE id = ?").param(deliveryId).update();
        scripts.of(Channel.EMAIL).alwaysSucceed();

        worker.runOnce();

        // Attempt 3, not attempt 1: the reclaim carried the spend forward.
        assertThat(attemptCountOf(deliveryId)).isEqualTo(3);
    }

    @Test
    void repeatedStrandingStillTerminates() throws Exception {
        UUID notificationId = submitEmailOnly("reclaim-budget-2");
        poller.drainOnce();
        UUID deliveryId = strandMidAttempt(notificationId);
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);

        // Strand it again after each round, simulating a provider that kills the worker every time.
        for (int round = 0; round < 8; round++) {
            worker.runOnce();
            clock.advance(java.time.Duration.ofMinutes(5));
            if (stateOf(deliveryId).equals("RETRY_SCHEDULED")) {
                jdbc.sql("UPDATE delivery SET state = 'IN_PROGRESS', claimed_until = ? WHERE id = ?")
                        .param(java.sql.Timestamp.from(clock.now().minus(java.time.Duration.ofMinutes(5))))
                        .param(deliveryId)
                        .update();
            }
        }

        assertThat(stateOf(deliveryId)).isEqualTo("EXHAUSTED");
        assertThat(attemptCountOf(deliveryId)).isLessThanOrEqualTo(5);
    }
}
