package com.notification.domain.retry;

import java.util.UUID;

/**
 * T063 — the reference that pairs a retry execution with the scheduling that caused it (FR-150a).
 *
 * <p>Phase 1 already recorded both a {@code RETRY_SCHEDULED} and a {@code DELIVERY_ATTEMPTED}, but
 * nothing tied one to the other. With a single retry a reader can infer the pairing from ordering;
 * across several it cannot, and audit ordering is not something to lean on for a correctness claim.
 *
 * <p>Derived rather than stored. A foreign key to the audit row would have to be read back before the
 * execution could be recorded, so the pairing would be missing precisely when the run went wrong —
 * the run an operator is reading the audit trail to understand.
 *
 * <p>The derivation rests on one fact about the writer, asserted in {@code RetryAuditRefTest}: the
 * scheduling recorded after attempt N is what schedules attempt N+1.
 */
public final class RetryAuditRef {

    private RetryAuditRef() {}

    /** The ref a {@code RETRY_SCHEDULED} record recorded after {@code attemptNumber} is known by. */
    public static String forSchedulingAfterAttempt(UUID deliveryId, int attemptNumber) {
        return deliveryId + "#" + attemptNumber;
    }

    /**
     * The ref a {@code RETRY_EXECUTED} record for {@code attemptNumber} points back to.
     *
     * @throws IllegalArgumentException for attempt 1, which no retry scheduled. Refusing rather than
     *     returning a sentinel: a ref to a record that does not exist is worse than no ref, because it
     *     reads as a pairing.
     */
    public static String forExecutionOfAttempt(UUID deliveryId, int attemptNumber) {
        if (attemptNumber <= 1) {
            throw new IllegalArgumentException(
                    "Attempt "
                            + attemptNumber
                            + " is a first attempt, not a retry, so no scheduling preceded it. Do not"
                            + " record RETRY_EXECUTED for it.");
        }
        return forSchedulingAfterAttempt(deliveryId, attemptNumber - 1);
    }
}
