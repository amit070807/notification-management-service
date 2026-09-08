package com.notification.integration;

import com.notification.domain.model.Channel;
import com.notification.domain.port.ChannelProviderPort;
import com.notification.fixtures.ScriptedChannelProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the configured simulators with programmable ones for tests.
 *
 * <p>Both implement the same {@link ChannelProviderPort} as any real adapter, so the failure
 * taxonomy is exercised through the production code path rather than a parallel one (Principle
 * VI). ADR-015 keeps the running service configuration-driven; this is the test-side half.
 */
@TestConfiguration
public class TestChannelConfig {

    /** Handle tests use to script outcomes per channel. */
    public static class Scripts {
        private final Map<Channel, ScriptedChannelProvider> byChannel = new EnumMap<>(Channel.class);

        Scripts() {
            for (Channel c : Channel.values()) {
                byChannel.put(c, new ScriptedChannelProvider(c));
            }
        }

        public ScriptedChannelProvider of(Channel channel) {
            return byChannel.get(channel);
        }

        public List<ChannelProviderPort> providers() {
            return List.copyOf(byChannel.values());
        }

        /** Every test must start from a known script, or outcomes leak between tests. */
        public void resetAll() {
            byChannel.values().forEach(ScriptedChannelProvider::reset);
        }
    }

    @Bean
    public Scripts channelScripts() {
        return new Scripts();
    }

    @Bean
    @Primary
    public List<ChannelProviderPort> testChannelProviders(Scripts scripts) {
        return scripts.providers();
    }
}
