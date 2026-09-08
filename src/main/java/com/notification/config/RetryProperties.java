package com.notification.config;

import com.notification.domain.retry.RetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T090 — retry bounds from configuration.
 *
 * <p>The constitution requires these to be configurable so an operational fault can be mitigated
 * without a deploy. The values are constitutional defaults, not source requirements: section 4.5
 * says "bounded" and supplies no numbers (spec G-08).
 */
@ConfigurationProperties(prefix = "notification.retry")
public record RetryProperties(
        Integer maxAttempts, Duration baseDelay, Double multiplier, Duration ceiling, Double jitterRatio) {

    public RetryPolicy toPolicy() {
        return new RetryPolicy(
                maxAttempts == null ? 5 : maxAttempts,
                baseDelay == null ? Duration.ofSeconds(1) : baseDelay,
                multiplier == null ? 2.0 : multiplier,
                ceiling == null ? Duration.ofSeconds(60) : ceiling,
                jitterRatio == null ? 0.2 : jitterRatio);
    }
}
