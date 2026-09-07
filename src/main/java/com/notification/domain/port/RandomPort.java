package com.notification.domain.port;

/**
 * Randomness used for retry backoff jitter (ADR-008).
 *
 * <p>Kept behind a port so {@code RetryPolicy} remains a pure function of its declared inputs and
 * so jitter is reproducible in tests.
 */
public interface RandomPort {
    /** @return a value in [-1.0, 1.0] used to scale the jitter band. */
    double nextJitterFactor();
}
