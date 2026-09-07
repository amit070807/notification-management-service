package com.notification.config;

import com.notification.channel.SimulatedChannelProvider;
import com.notification.domain.model.Channel;
import com.notification.domain.port.ChannelProviderPort;
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
        return Arrays.stream(Channel.values())
                .map(
                        channel -> {
                            var sim = properties.simulate().get(channel.name());
                            return (ChannelProviderPort)
                                    new SimulatedChannelProvider(
                                            channel,
                                            sim == null ? null : sim.failWith(),
                                            sim == null ? 0 : sim.firstAttempts());
                        })
                .toList();
    }
}
