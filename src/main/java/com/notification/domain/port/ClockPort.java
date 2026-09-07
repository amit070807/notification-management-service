package com.notification.domain.port;

import java.time.Instant;

/**
 * The domain's only source of time (Principle III).
 *
 * <p>Direct calls to {@code Instant.now()} inside {@code com.notification.domain} are forbidden
 * and are rejected by the architecture gate. Injecting time is what makes bounded retry and
 * expiry testable without real waiting (Principle VI).
 */
public interface ClockPort {
    Instant now();
}
