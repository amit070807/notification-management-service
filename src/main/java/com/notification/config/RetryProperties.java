package com.notification.config;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.RetryPolicy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry bounds from configuration.
 *
 * <p>The constitution requires these to be configurable so an operational fault can be mitigated
 * without a deploy. The values are constitutional defaults, not source requirements: section 4.5
 * says "bounded" and supplies no numbers (spec G-08).
 *
 * <p>T005 adds per-channel overrides (FR-132). A provider may differ in <i>schedule</i> — push backs
 * off more patiently than email, which is the substantive part of "handling" rate limiting per
 * ADR-018 — but <b>no provider may opt out of the bound</b>. An override supplies only the fields it
 * changes; everything else falls back to the defaults, so a partial override cannot accidentally
 * unbound a channel by omitting {@code maxAttempts}.
 *
 * @param overrides per-channel partial overrides, keyed by channel name
 */
@ConfigurationProperties(prefix = "notification.retry")
public record RetryProperties(
        Integer maxAttempts,
        Duration baseDelay,
        Double multiplier,
        Duration ceiling,
        Double jitterRatio,
        Map<Channel, Override> overrides) {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final Duration DEFAULT_CEILING = Duration.ofSeconds(60);
    private static final double DEFAULT_JITTER_RATIO = 0.2;

    /**
     * A hard ceiling on any per-channel override. Without it, a configuration edit could make a
     * channel effectively unbounded, which section 4.5 forbids and which no test would notice
     * because the schedule would still look plausible.
     */
    public static final int MAX_ALLOWED_ATTEMPTS = 10;

    public RetryProperties {
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /** Every field optional — an override changes only what it names. */
    public record Override(
            Integer maxAttempts,
            Duration baseDelay,
            Double multiplier,
            Duration ceiling,
            Double jitterRatio) {}

    /** The default policy, applied to any channel without an override. */
    public RetryPolicy toPolicy() {
        return new RetryPolicy(
                maxAttempts == null ? 5 : maxAttempts,
                baseDelay == null ? Duration.ofSeconds(1) : baseDelay,
                multiplier == null ? 2.0 : multiplier,
                ceiling == null ? Duration.ofSeconds(60) : ceiling,
                jitterRatio == null ? 0.2 : jitterRatio);
    }
}
