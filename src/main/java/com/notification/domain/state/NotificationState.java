package com.notification.domain.state;

import java.util.Collection;

/**
 * T019 — overall notification status, as a deterministic rollup of its deliveries (FR-016).
 *
 * <p>Source 4.2 requires both an overall status and per-recipient-per-channel statuses but never
 * relates the two. This rollup is the documented relation, permitted by 4.2's allowance for "a
 * different state model if it is documented and defensible".
 *
 * <p>Rule order is significant and is asserted by the unit test: the all-expired check runs before
 * the none-delivered check, so a wholly expired notification reports {@link #EXPIRED} rather than
 * {@link #FAILED}. Expiry is not a failure (FR-034).
 */
public enum NotificationState {
    ACCEPTED,
    IN_PROGRESS,
    COMPLETED,
    PARTIALLY_FAILED,
    FAILED,
    EXPIRED;

    /**
     * @param deliveryStates current state of every delivery for the notification
     * @param notificationExpired whether the notification's expiry time has passed
     */
    public static NotificationState rollup(
            Collection<DeliveryState> deliveryStates, boolean notificationExpired) {

        // Rule 1: nothing to deliver — routing selected no channel, or none exist yet.
        if (deliveryStates.isEmpty()) {
            return notificationExpired ? EXPIRED : ACCEPTED;
        }

        // Rule 2: still working.
        if (deliveryStates.stream().anyMatch(s -> !s.isTerminal())) {
            return IN_PROGRESS;
        }

        boolean anyDelivered = deliveryStates.stream().anyMatch(s -> s == DeliveryState.DELIVERED);
        boolean allDelivered = deliveryStates.stream().allMatch(s -> s == DeliveryState.DELIVERED);

        // Rule 3.
        if (allDelivered) {
            return COMPLETED;
        }

        // Rule 4 — deliberately before rule 6. A wholly expired notification is not a failure.
        if (deliveryStates.stream().allMatch(s -> s == DeliveryState.EXPIRED)) {
            return EXPIRED;
        }

        // Rule 5.
        if (anyDelivered) {
            return PARTIALLY_FAILED;
        }

        // Rule 6.
        return FAILED;
    }
}
