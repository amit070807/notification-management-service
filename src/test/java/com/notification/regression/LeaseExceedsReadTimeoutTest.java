package com.notification.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * T048 — the lease must exceed the provider read timeout (spec G-58).
 *
 * <p>This is a configuration invariant with a nasty failure mode. Reclaiming {@code IN_PROGRESS}
 * deliveries means one whose provider call is merely <b>slow</b> — longer than the lease — is
 * reclaimed while still in flight. Set the lease below the read timeout and reclaim stops recovering
 * crashed deliveries and starts racing every slow one, producing duplicate provider calls under
 * exactly the load conditions where a provider is slowest.
 *
 * <p>Asserted against the committed file rather than resolved beans, for the same reason as
 * {@code FlagsOffBaselineTest}: the file is what ships, and this check must not depend on a context
 * starting.
 */
class LeaseExceedsReadTimeoutTest {

    private static final Path CONFIG = Path.of("src/main/resources/application.yaml");

    @Test
    void theDeliveryLeaseExceedsTheProviderReadTimeout() throws IOException {
        Map<String, Object> notification = notificationBlock();

        @SuppressWarnings("unchecked")
        Duration lease = Duration.parse(
                (String) ((Map<String, Object>) notification.get("worker")).get("delivery-lease"));
        @SuppressWarnings("unchecked")
        Duration readTimeout = Duration.parse(
                (String) ((Map<String, Object>) notification.get("channel")).get("read-timeout"));

        assertThat(lease)
                .as(
                        "delivery-lease (%s) must exceed channel read-timeout (%s), or reclaim races every"
                                + " slow provider call instead of only crashed ones (G-58)",
                        lease, readTimeout)
                .isGreaterThan(readTimeout);
    }

    @Test
    void theMarginIsGenerousRatherThanMarginal() {
        // A lease a hair above the read timeout satisfies the letter of the rule and still races
        // under jitter, connection setup and scheduling delay. At least double.
        Map<String, Object> notification = notificationBlockQuietly();

        @SuppressWarnings("unchecked")
        Duration lease = Duration.parse(
                (String) ((Map<String, Object>) notification.get("worker")).get("delivery-lease"));
        @SuppressWarnings("unchecked")
        Duration readTimeout = Duration.parse(
                (String) ((Map<String, Object>) notification.get("channel")).get("read-timeout"));

        assertThat(lease.toMillis())
                .as("lease should be at least twice the read timeout to absorb setup and scheduling")
                .isGreaterThanOrEqualTo(readTimeout.toMillis() * 2);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> notificationBlock() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(CONFIG));
        return (Map<String, Object>) root.get("notification");
    }

    private static Map<String, Object> notificationBlockQuietly() {
        try {
            return notificationBlock();
        } catch (IOException e) {
            throw new AssertionError("could not read " + CONFIG, e);
        }
    }
}
