package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.state.DeliveryState;
import com.notification.domain.state.NotificationState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T018 — the overall-status rollup (FR-016).
 *
 * <p>Source 4.2 requires both an overall status and per-recipient-per-channel statuses, but never
 * relates them. The six rules below are design-derived and must be deterministic.
 *
 * <p>The rule ordering matters: rule 4 (all expired) is checked before rule 6 (none delivered), so
 * a wholly expired notification reports EXPIRED rather than FAILED. Expiry is not a failure
 * (FR-034).
 */
class NotificationStateRollupTest {

    @Test
    void rule1_noDeliveriesAndNotExpired_isAccepted() {
        assertThat(NotificationState.rollup(List.of(), false)).isEqualTo(NotificationState.ACCEPTED);
    }

    @Test
    void rule1_noDeliveriesAndExpired_isExpired() {
        assertThat(NotificationState.rollup(List.of(), true)).isEqualTo(NotificationState.EXPIRED);
    }

    @Test
    void rule2a_allPending_isAcceptedNotInProgress() {
        // Found by StatusImmediacyTest: a freshly accepted notification had nothing attempted yet,
        // but the original rule 2 reported IN_PROGRESS, leaving ACCEPTED effectively unreachable.
        assertThat(NotificationState.rollup(List.of(DeliveryState.PENDING, DeliveryState.PENDING), false))
                .isEqualTo(NotificationState.ACCEPTED);
    }

    @Test
    void rule2b_onePendingOneQueued_isInProgress() {
        // Once ANY delivery has moved past PENDING, work has genuinely begun.
        assertThat(NotificationState.rollup(List.of(DeliveryState.PENDING, DeliveryState.QUEUED), false))
                .isEqualTo(NotificationState.IN_PROGRESS);
    }

    @Test
    void rule2_anyNonTerminal_isInProgress() {
        assertThat(NotificationState.rollup(List.of(DeliveryState.DELIVERED, DeliveryState.QUEUED), false))
                .isEqualTo(NotificationState.IN_PROGRESS);
        assertThat(NotificationState.rollup(List.of(DeliveryState.RETRY_SCHEDULED), false))
                .isEqualTo(NotificationState.IN_PROGRESS);
    }

    @Test
    void rule3_allDelivered_isCompleted() {
        assertThat(NotificationState.rollup(List.of(DeliveryState.DELIVERED, DeliveryState.DELIVERED), false))
                .isEqualTo(NotificationState.COMPLETED);
    }

    @Test
    void rule4_allExpired_isExpiredNotFailed() {
        // The load-bearing case: expiry is not a failure (FR-034).
        assertThat(NotificationState.rollup(List.of(DeliveryState.EXPIRED, DeliveryState.EXPIRED), true))
                .isEqualTo(NotificationState.EXPIRED);
    }

    @Test
    void rule5_someDeliveredSomeNot_isPartiallyFailed() {
        assertThat(NotificationState.rollup(List.of(DeliveryState.DELIVERED, DeliveryState.FAILED), false))
                .isEqualTo(NotificationState.PARTIALLY_FAILED);
        assertThat(NotificationState.rollup(List.of(DeliveryState.DELIVERED, DeliveryState.EXPIRED), false))
                .isEqualTo(NotificationState.PARTIALLY_FAILED);
    }

    @Test
    void rule6_terminalNoneDelivered_isFailed() {
        assertThat(NotificationState.rollup(List.of(DeliveryState.FAILED, DeliveryState.EXHAUSTED), false))
                .isEqualTo(NotificationState.FAILED);
        assertThat(NotificationState.rollup(List.of(DeliveryState.UNDELIVERABLE), false))
                .isEqualTo(NotificationState.FAILED);
    }

    @Test
    void rule4OutranksRule6_mixedExpiredAndFailedIsFailed() {
        // Rule 4 requires ALL expired. A mix is still a failure.
        assertThat(NotificationState.rollup(List.of(DeliveryState.EXPIRED, DeliveryState.FAILED), false))
                .isEqualTo(NotificationState.FAILED);
    }

    @Test
    void rollupIsDeterministic() {
        List<DeliveryState> input = List.of(DeliveryState.DELIVERED, DeliveryState.FAILED);
        NotificationState first = NotificationState.rollup(input, false);
        for (int i = 0; i < 50; i++) {
            assertThat(NotificationState.rollup(input, false)).isEqualTo(first);
        }
    }
}
