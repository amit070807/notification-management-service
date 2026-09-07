package com.notification.domain.retry;

import java.util.EnumSet;
import java.util.Set;

/**
 * T021 — the single place where retryability is declared (FR-039, FR-040).
 *
 * <p>Source 4.5 requires a bounded retry strategy for "retryable delivery failures" and names five
 * failure kinds, but never says which of them are retryable. The partition below is therefore
 * design-derived, not source text, and lives in exactly one place so it cannot drift between the
 * worker, the scheduler and the tests.
 *
 * <p>{@code AUTH_ERROR} is non-retryable on purpose: it indicates a service configuration fault
 * rather than anything about the recipient, so retrying cannot succeed until configuration
 * changes. It additionally raises an operational signal so it is not silently absorbed into a
 * per-recipient failure count (FR-042).
 */
public final class Retryability {

    private static final Set<FailureClassification> RETRYABLE =
            EnumSet.of(
                    FailureClassification.TRANSIENT_PROVIDER_FAILURE,
                    FailureClassification.TIMEOUT,
                    // FR-041: fails closed — retried, but never recorded as success.
                    FailureClassification.UNKNOWN);

    private Retryability() {}

    public static boolean isRetryable(FailureClassification classification) {
        return RETRYABLE.contains(classification);
    }

    /** @return true when the classification indicates a service fault an operator must see. */
    public static boolean raisesOperationalSignal(FailureClassification classification) {
        return classification == FailureClassification.AUTH_ERROR;
    }
}
