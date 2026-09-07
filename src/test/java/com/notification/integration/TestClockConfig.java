package com.notification.integration;

import com.notification.domain.port.ClockPort;
import com.notification.fixtures.MutableClock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the system clock with one tests can move (Principle VI).
 *
 * <p>Also disables the scheduled triggers: the tests drive {@code OutboxPoller.drainOnce()} and
 * {@code DeliveryWorker.runOnce()} directly, so a background scheduler racing them would make
 * assertions non-deterministic.
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-07T12:00:00Z"));
    }

    /**
     * Named differently from the production bean on purpose. Spring Boot forbids bean-definition
     * overriding by default, and @Primary does not resolve a NAME collision — only ambiguity
     * between distinct names. Enabling overriding globally would let a stray test config silently
     * replace production beans, which is worse than one awkward method name.
     */
    @Bean
    @Primary
    public ClockPort testClockPort(MutableClock clock) {
        return clock;
    }
}
