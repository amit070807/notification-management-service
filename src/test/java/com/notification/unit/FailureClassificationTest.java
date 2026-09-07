package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.retry.FailureClassification;
import com.notification.domain.retry.Retryability;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T020 — the retryability matrix (FR-039, FR-040).
 *
 * <p>Source 4.5 names five failure kinds and says retry applies to "retryable delivery failures",
 * but never states which kinds are retryable. FR-040 records the partition as design-derived; this
 * test is what pins it down.
 */
class FailureClassificationTest {

    @Test
    void everyClassificationHasADeclaredRetryability() {
        // No classification may be silently un-partitioned (FR-039).
        for (FailureClassification c : FailureClassification.values()) {
            assertThat(Retryability.isRetryable(c))
                    .as("classification %s must have a declared retryability", c)
                    .isIn(true, false);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = FailureClassification.class,
            names = {"TRANSIENT_PROVIDER_FAILURE", "TIMEOUT", "UNKNOWN"})
    void retryableKinds(FailureClassification c) {
        assertThat(Retryability.isRetryable(c)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = FailureClassification.class,
            names = {"PERMANENT_PROVIDER_REJECTION", "INVALID_RECIPIENT", "AUTH_ERROR"})
    void nonRetryableKinds(FailureClassification c) {
        assertThat(Retryability.isRetryable(c)).isFalse();
    }

    @Test
    void unknownFailsClosed() {
        // FR-041: an unmappable provider outcome is retried but is never treated as success.
        assertThat(Retryability.isRetryable(FailureClassification.UNKNOWN)).isTrue();
    }

    @Test
    void authErrorIsOperational() {
        // FR-042: an auth failure is a service configuration fault, not a recipient fault, so it
        // must be surfaced operationally rather than absorbed as just another delivery failure.
        assertThat(Retryability.raisesOperationalSignal(FailureClassification.AUTH_ERROR)).isTrue();
        EnumSet.complementOf(EnumSet.of(FailureClassification.AUTH_ERROR))
                .forEach(c -> assertThat(Retryability.raisesOperationalSignal(c)).isFalse());
    }

    @Test
    void taxonomyCoversTheFiveKindsNamedBySourceSection45() {
        assertThat(FailureClassification.values())
                .extracting(Enum::name)
                .contains(
                        "TRANSIENT_PROVIDER_FAILURE",
                        "PERMANENT_PROVIDER_REJECTION",
                        "INVALID_RECIPIENT",
                        "TIMEOUT",
                        "AUTH_ERROR");
    }
}
