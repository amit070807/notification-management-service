package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.config.RetryPolicySelector;
import com.notification.config.RetryProperties;
import com.notification.domain.model.Channel;
import com.notification.domain.retry.RetryPolicy;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T029 at unit level — per-channel retry schedules, and the bound they may not escape (FR-132).
 *
 * <p>The distinction this pins down: a provider may differ in <b>schedule</b> — push backs off more
 * patiently than email, which is the substantive part of "handling" a rate limit per ADR-018 — but no
 * provider may opt out of being <b>bounded</b>. Section 4.5 requires bounded retry, and a channel with
 * unlimited attempts would look entirely plausible in a configuration file.
 */
class RetryPolicySelectorTest {

    private static RetryProperties props(Map<Channel, RetryProperties.Override> overrides) {
        return new RetryProperties(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), 0.2, overrides);
    }

    @Test
    void aChannelWithoutAnOverrideGetsTheDefault() {
        RetryPolicySelector selector = new RetryPolicySelector(props(Map.of()));

        for (Channel channel : Channel.values()) {
            assertThat(selector.forChannel(channel)).isEqualTo(selector.defaultPolicy());
        }
    }

    @Test
    void anOverrideChangesOnlyTheChannelItNames() {
        RetryPolicySelector selector =
                new RetryPolicySelector(
                        props(
                                Map.of(
                                        Channel.PUSH,
                                        new RetryProperties.Override(null, Duration.ofSeconds(5), null, null, null))));

        assertThat(selector.forChannel(Channel.PUSH).baseDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(selector.forChannel(Channel.EMAIL).baseDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(selector.forChannel(Channel.SMS).baseDelay()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aPartialOverrideInheritsEveryFieldItDoesNotName() {
        // The failure this prevents: an override that changes the delay and, by omitting
        // maxAttempts, accidentally unbounds the channel.
        RetryPolicySelector selector =
                new RetryPolicySelector(
                        props(
                                Map.of(
                                        Channel.PUSH,
                                        new RetryProperties.Override(
                                                null, Duration.ofSeconds(10), 3.0, null, null))));

        RetryPolicy push = selector.forChannel(Channel.PUSH);
        assertThat(push.baseDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(push.multiplier()).isEqualTo(3.0);
        assertThat(push.maxAttempts()).as("inherited, not unbounded").isEqualTo(5);
        assertThat(push.ceiling()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void pushCanBackOffMorePatientlyThanEmail() {
        // The concrete point of FR-132: a rate-limited push provider should be given more room than
        // a flaky SMTP server, without either escaping its bound.
        RetryPolicySelector selector =
                new RetryPolicySelector(
                        props(
                                Map.of(
                                        Channel.PUSH,
                                        new RetryProperties.Override(
                                                null, Duration.ofSeconds(30), null, Duration.ofMinutes(10), null))));

        assertThat(selector.forChannel(Channel.PUSH).delayAfter(1, 0))
                .isGreaterThan(selector.forChannel(Channel.EMAIL).delayAfter(1, 0));
    }

    @Test
    void everyPerChannelPolicyIsStillBounded() {
        RetryPolicySelector selector =
                new RetryPolicySelector(
                        props(
                                Map.of(
                                        Channel.PUSH,
                                        new RetryProperties.Override(8, null, null, null, null))));

        for (Channel channel : Channel.values()) {
            RetryPolicy policy = selector.forChannel(channel);
            assertThat(policy.maxAttempts()).isPositive();
            assertThat(policy.hasBudgetAfter(policy.maxAttempts())).isFalse();
        }
    }

    @Test
    void anOverrideAboveTheAllowedMaximumFailsAtStartupNotDuringADelivery() {
        // Eager resolution is the point. A bad override discovered mid-incident, on the one channel
        // that happened to fail, is the worst time to find it.
        assertThatThrownBy(
                        () ->
                                new RetryPolicySelector(
                                        props(
                                                Map.of(
                                                        Channel.PUSH,
                                                        new RetryProperties.Override(
                                                                RetryProperties.MAX_ALLOWED_ATTEMPTS + 1,
                                                                null,
                                                                null,
                                                                null,
                                                                null)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bounded");
    }

    @Test
    void anOverrideAtExactlyTheAllowedMaximumIsAccepted() {
        assertThat(
                        new RetryPolicySelector(
                                        props(
                                                Map.of(
                                                        Channel.SMS,
                                                        new RetryProperties.Override(
                                                                RetryProperties.MAX_ALLOWED_ATTEMPTS,
                                                                null,
                                                                null,
                                                                null,
                                                                null))))
                                .forChannel(Channel.SMS)
                                .maxAttempts())
                .isEqualTo(RetryProperties.MAX_ALLOWED_ATTEMPTS);
    }

    @Test
    void everyChannelInTheEnumResolvesToAPolicy() {
        // A channel added to the enum must not silently fall through to null and NPE on first use.
        RetryPolicySelector selector = new RetryPolicySelector(props(Map.of()));
        for (Channel channel : Channel.values()) {
            assertThat(selector.forChannel(channel)).isNotNull();
        }
    }
}
