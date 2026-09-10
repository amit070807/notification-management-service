package com.notification.config;

import com.notification.channel.SimulatedChannelProvider;
import com.notification.channel.SimulatedPushProvider;
import com.notification.domain.model.Channel;
import com.notification.domain.port.ChannelProviderPort;
import com.notification.domain.retry.FailureClassification;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires one provider per channel from configuration (ADR-015).
 *
 * <p>Adding a channel is a change here and in {@code Channel} — routing, retry, the state machine
 * and audit are untouched. That is the Principle VII claim made concrete, and the extensibility
 * test asserts it.
 */
@Configuration
public class ChannelConfig {

    @Bean
    public List<ChannelProviderPort> channelProviders(ChannelProperties properties) {
        // Still driven by Channel.values(), so a channel cannot exist in the enum and be silently
        // unwired. T023 adds only the push case; everything else is untouched, which is the
        // Principle VII claim holding up rather than being asserted.
        return Arrays.stream(Channel.values())
                .map(channel -> providerFor(channel, properties))
                .toList();
    }

    /**
     * Selects the adapter for a channel.
     *
     * <p>US2 (ADR-022) removed the {@code if (channel == Channel.PUSH)} branch that briefly lived here.
     * A branch per channel would grow with every channel added, and wiring is the wrong place for
     * channel knowledge to accumulate — an adapter knows which channel it serves, so it declares it.
     * The registry below is a lookup, not a decision.
     */
    private static ChannelProviderPort providerFor(Channel channel, ChannelProperties properties) {
        var sim = properties.simulate().get(channel.name());
        FailureClassification failWith = sim == null ? null : sim.failWith();
        int failFirst = sim == null ? 0 : sim.firstAttempts();

        ChannelProviderPort provider =
                switch (channel) {
                    // Push authenticates, so it needs a credential the others do not take (FR-115).
                    case PUSH ->
                            new SimulatedPushProvider(
                                    properties.credentialFor(channel.name()),
                                    failWith,
                                    failFirst,
                                    properties.connectTimeout(),
                                    properties.readTimeout());
                    case EMAIL, SMS ->
                            new SimulatedChannelProvider(
                                    channel,
                                    failWith,
                                    failFirst,
                                    properties.connectTimeout(),
                                    properties.readTimeout());
                };

        // An exhaustive switch means a channel added to the enum without an adapter fails to
        // compile, rather than silently receiving a default that delivers nothing.
        if (provider.channel() != channel) {
            throw new IllegalStateException(
                    "Adapter for %s declares channel %s".formatted(channel, provider.channel()));
        }
        return provider;
    }
}
