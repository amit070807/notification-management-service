package com.notification.config;

import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Channel configuration, including the simulated-failure controls from ADR-015.
 *
 * <p>{@code connectTimeout} and {@code readTimeout} are not optional garnish: the constitution
 * requires every outbound provider call to be bounded, because without a timeout the
 * {@code TIMEOUT} classification of source 4.5 could never be produced at all.
 */
@ConfigurationProperties(prefix = "notification.channel")
public record ChannelProperties(
        Duration connectTimeout, Duration readTimeout, Map<String, Simulation> simulate) {

    public ChannelProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        simulate = simulate == null ? Map.of() : simulate;
    }

    /**
     * @param failWith null means the channel always succeeds
     * @param failFirstAttempts fail only the first N attempts, then succeed
     */
    public record Simulation(FailureClassification failWith, Integer failFirstAttempts) {
        public int firstAttempts() {
            return failFirstAttempts == null ? 0 : failFirstAttempts;
        }
    }
}
