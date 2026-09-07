package com.notification.domain.retry;

/**
 * The closed failure taxonomy required by source 4.5, plus {@link #UNKNOWN}.
 *
 * <p>4.5 names five kinds. {@code UNKNOWN} is added because the list is closed as written but real
 * providers are not: an unmappable outcome must fail closed rather than be assumed successful
 * (FR-041).
 *
 * <p>Retryability is deliberately NOT declared here — see {@link Retryability}, which holds the
 * partition in exactly one place so it cannot drift.
 */
public enum FailureClassification {
    TRANSIENT_PROVIDER_FAILURE,
    PERMANENT_PROVIDER_REJECTION,
    INVALID_RECIPIENT,
    TIMEOUT,
    AUTH_ERROR,
    UNKNOWN
}
