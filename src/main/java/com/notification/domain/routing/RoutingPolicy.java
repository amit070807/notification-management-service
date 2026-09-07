package com.notification.domain.routing;

import com.notification.domain.model.Channel;
import com.notification.domain.model.Severity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The governing rule set, and the deciding authority in routing (FR-020a).
 *
 * <p>The owner's answer to Q2 named three factors but not their ordering. FR-020a records the
 * precedence adopted: the policy decides; severity and the requested channels are inputs it
 * consults. A requested channel is therefore a proposal the policy may decline, not an
 * instruction it must honour.
 *
 * <p>Immutable, and stamped with a version that is recorded against every decision so a past
 * decision stays explicable after the policy changes (FR-026, SC-010).
 */
public record RoutingPolicy(
        String version, Set<Channel> enabledChannels, Map<Channel, Severity> minimumSeverity) {

    /** The permissive policy used before US3 introduces a configured one. */
    public static RoutingPolicy allowAll(String version) {
        return new RoutingPolicy(version, Set.of(Channel.values()), Map.of());
    }

    public boolean isEnabled(Channel channel) {
        return enabledChannels.contains(channel);
    }

    /** @return true when the notification's severity clears this channel's configured floor. */
    public boolean meetsSeverityFloor(Channel channel, Severity severity) {
        Severity floor = minimumSeverity.get(channel);
        return floor == null || severity.ordinal() >= floor.ordinal();
    }

    public List<Channel> channelOrder() {
        return List.of(Channel.values());
    }
}
