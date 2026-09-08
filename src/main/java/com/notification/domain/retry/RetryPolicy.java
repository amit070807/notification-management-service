package com.notification.domain.retry;

import java.time.Duration;

/**
 * T089 — bounded retry (source 4.5, FR-036, FR-038).
 *
 * <p>A pure function of its declared inputs. Jitter arrives as a caller-supplied factor rather
 * than being drawn here, so this class needs no {@code RandomPort} and stays reproducible in a
 * unit test — the architecture gate forbids randomness inside the domain for exactly this reason.
 *
 * <p>Source 4.5 requires the strategy to be "bounded" but supplies no numbers (spec G-08). The
 * defaults come from the constitution's Declared Operating Defaults and are configurable; they are
 * engineering assumptions, not source requirements.
 *
 * @param maxAttempts total attempts including the first, so 5 means 1 initial plus 4 retries
 * @param jitterRatio fraction of the delay to vary by, spreading retries so a recovering provider
 *     is not hit by every delivery at once
 */
public record RetryPolicy(
        int maxAttempts, Duration baseDelay, double multiplier, Duration ceiling, double jitterRatio) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1; 'bounded' cannot mean zero");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitterRatio must be within [0, 1]");
        }
    }

    /** @return true when another attempt is permitted after {@code attemptsMade} have been made. */
    public boolean hasBudgetAfter(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /**
     * @param attemptsMade attempts already completed; the first retry is computed with 1
     * @param jitterFactor value in [-1, 1] supplied by the caller from a RandomPort
     */
    public Duration delayAfter(int attemptsMade, double jitterFactor) {
        double millis = baseDelay.toMillis() * Math.pow(multiplier, Math.max(0, attemptsMade - 1));
        millis = Math.min(millis, ceiling.toMillis());

        double jittered = millis * (1 + jitterRatio * clamp(jitterFactor));
        // Never negative, and never beyond the ceiling even after jitter pushes upward.
        long bounded = (long) Math.max(0, Math.min(jittered, ceiling.toMillis()));
        return Duration.ofMillis(bounded);
    }

    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
