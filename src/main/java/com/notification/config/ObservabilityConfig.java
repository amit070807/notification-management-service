package com.notification.config;

import com.notification.domain.port.ClockPort;
import com.notification.domain.port.IdPort;
import com.notification.domain.port.RandomPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T031 — wires the domain ports to real implementations, and registers metrics.
 *
 * <p>This is the only place system time and randomness enter the application. The domain sees
 * {@link ClockPort} and {@link RandomPort}, never {@code Instant.now()} — enforced by the
 * architecture gate rather than by convention.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ClockPort clockPort() {
        Clock system = Clock.systemUTC();
        return system::instant;
    }

    @Bean
    public IdPort idPort() {
        return UUID::randomUUID;
    }

    @Bean
    public RandomPort randomPort() {
        return () -> ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
    }

    @Bean
    public NotificationMetrics notificationMetrics(MeterRegistry registry) {
        return new NotificationMetrics(registry);
    }

    /**
     * Counters required by the constitution's Observability section.
     *
     * <p>Note what is deliberately absent from the tag sets: no recipient reference, no content,
     * no correlation identifier. Metric labels are unbounded-cardinality leak vectors, and
     * Principle V forbids sensitive values there as firmly as in audit.
     */
    public static class NotificationMetrics {
        private final MeterRegistry registry;

        public NotificationMetrics(MeterRegistry registry) {
            this.registry = registry;
        }

        public void notificationAccepted() {
            registry.counter("notifications.accepted").increment();
        }

        public void notificationRejected() {
            registry.counter("notifications.rejected").increment();
        }

        public void deliveryTerminal(String terminalState, String channel) {
            Counter.builder("deliveries.terminal")
                    .tag("state", terminalState)
                    .tag("channel", channel)
                    .register(registry)
                    .increment();
        }

        public void attemptFailed(String classification, String channel) {
            Counter.builder("delivery.attempts.failed")
                    .tag("classification", classification)
                    .tag("channel", channel)
                    .register(registry)
                    .increment();
        }

        public void retryScheduled(String channel) {
            Counter.builder("delivery.retries.scheduled").tag("channel", channel).register(registry).increment();
        }

        /**
         * FR-042: an auth failure is a service configuration fault, not a recipient fault. It gets
         * its own counter so it is visible to an operator rather than buried in the generic
         * failure count.
         */
        public void authErrorRaised(String channel) {
            Counter.builder("delivery.auth.errors").tag("channel", channel).register(registry).increment();
        }

        public Instant nowForTimer(ClockPort clock) {
            return clock.now();
        }
    }
}
