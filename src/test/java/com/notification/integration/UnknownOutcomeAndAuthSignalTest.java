package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T087 and T088 — the two classifications that need behaviour beyond retry-or-not.
 */
class UnknownOutcomeAndAuthSignalTest extends RetryTestSupport {

    @Autowired private MeterRegistry meters;

    @Test
    void anUnclassifiableOutcomeIsNeverTreatedAsSuccess() throws Exception {
        // FR-041: source 4.5's list is closed as written, but real providers are not. Failing
        // closed means an unmappable response is retried and eventually exhausted — never
        // silently reported as delivered.
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.UNKNOWN);
        UUID id = submitEmailOnly("unknown-1");
        poller.drainOnce();
        runUntilSettled(10);

        var delivery = firstDelivery(id);
        assertThat(delivery.get("state").asText()).isNotEqualTo("DELIVERED");
        assertThat(delivery.get("state").asText()).isEqualTo("EXHAUSTED");
        assertThat(delivery.get("lastFailureClassification").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void authErrorIsTerminalAndRaisesAnOperationalSignal() throws Exception {
        // FR-042: an auth failure is a service configuration fault, not a recipient fault.
        // Absorbing it into the ordinary failure count would hide an outage affecting everyone.
        double before = counter("delivery.auth.errors");

        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.AUTH_ERROR);
        UUID id = submitEmailOnly("auth-1");
        poller.drainOnce();
        runUntilSettled(4);

        var delivery = firstDelivery(id);
        assertThat(delivery.get("state").asText()).isEqualTo("FAILED");
        assertThat(delivery.get("attemptCount").asInt()).isOne();
        assertThat(counter("delivery.auth.errors")).isGreaterThan(before);
    }

    @Test
    void otherClassificationsDoNotRaiseTheAuthSignal() throws Exception {
        double before = counter("delivery.auth.errors");

        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.INVALID_RECIPIENT);
        UUID id = submitEmailOnly("auth-2");
        poller.drainOnce();
        runUntilSettled(3);

        assertThat(counter("delivery.auth.errors")).isEqualTo(before);
    }

    private double counter(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }
}
