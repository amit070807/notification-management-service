package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.retry.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** T089 — the backoff schedule, as a pure function (FR-036, FR-038, FR-045). */
class RetryPolicyTest {

    private static final RetryPolicy POLICY =
            new RetryPolicy(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), 0.2);

    @Test
    void budgetIsBoundedByMaxAttempts() {
        assertThat(POLICY.hasBudgetAfter(1)).isTrue();
        assertThat(POLICY.hasBudgetAfter(4)).isTrue();
        assertThat(POLICY.hasBudgetAfter(5)).isFalse();
        assertThat(POLICY.hasBudgetAfter(99)).isFalse();
    }

    @Test
    void delayGrowsExponentiallyWithoutJitter() {
        assertThat(POLICY.delayAfter(1, 0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(POLICY.delayAfter(2, 0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(POLICY.delayAfter(3, 0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(POLICY.delayAfter(4, 0)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void delayNeverExceedsTheCeiling() {
        // Even at extreme attempt counts and maximum upward jitter.
        assertThat(POLICY.delayAfter(50, 1.0)).isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void jitterVariesTheDelayInBothDirections() {
        Duration low = POLICY.delayAfter(3, -1.0);
        Duration mid = POLICY.delayAfter(3, 0.0);
        Duration high = POLICY.delayAfter(3, 1.0);

        assertThat(low).isLessThan(mid);
        assertThat(high).isGreaterThan(mid);
        // +/-20% of 4s
        assertThat(low).isEqualTo(Duration.ofMillis(3200));
        assertThat(high).isEqualTo(Duration.ofMillis(4800));
    }

    @Test
    void delayIsNeverNegative() {
        assertThat(POLICY.delayAfter(1, -5.0)).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void jitterFactorIsClampedRatherThanTrusted() {
        // A RandomPort implementation returning out-of-range values must not produce a wild delay.
        assertThat(POLICY.delayAfter(3, 100.0)).isEqualTo(POLICY.delayAfter(3, 1.0));
    }

    @Test
    void boundedCannotMeanZeroAttempts() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), 0.2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jitterRatioMustBeAFraction() {
        assertThatThrownBy(() -> new RetryPolicy(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
