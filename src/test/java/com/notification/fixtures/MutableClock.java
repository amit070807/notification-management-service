package com.notification.fixtures;

import com.notification.domain.port.ClockPort;
import java.time.Duration;
import java.time.Instant;

/**
 * T014 — a clock tests can move.
 *
 * <p>Principle VI forbids {@code Thread.sleep}, {@code Awaitility} and any real waiting. Advancing
 * this instead is what lets the expiry-during-backoff case (FR-034) be asserted in milliseconds
 * rather than in the minute the real backoff would take.
 */
public final class MutableClock implements ClockPort {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public static MutableClock at(String iso8601) {
        return new MutableClock(Instant.parse(iso8601));
    }

    @Override
    public Instant now() {
        return now;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    public void setTo(Instant instant) {
        now = instant;
    }
}
