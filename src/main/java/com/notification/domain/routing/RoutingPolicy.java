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
 * <p>Immutable, and stamped with a version recorded against every decision so a past decision
 * stays explicable after the policy changes (FR-026, SC-010).
 *
 * <p>There is deliberately no per-recipient rule of any kind. Source 4.3's fourth factor is
 * deferred (spec D2/G-26) and FR-019a forbids a proxy for it — a policy keyed by recipient would
 * be exactly that.
 *
 * @param minimumSeverity per-channel severity floor; absent means no floor
 * @param escalateAtSeverity per-channel severity at or above which the channel is selected even
 *     when it was not requested. Escalation applies only where declared (spec register item 5).
 */
public record RoutingPolicy(
        String version,
        Set<Channel> enabledChannels,
        Map<Channel, Severity> minimumSeverity,
        Map<Channel, Severity> escalateAtSeverity) {

    public RoutingPolicy {
        enabledChannels = Set.copyOf(enabledChannels);
        minimumSeverity = Map.copyOf(minimumSeverity);
        escalateAtSeverity = Map.copyOf(escalateAtSeverity);
    }

    /** The permissive policy used by tests that are not about policy evaluation. */
    public static RoutingPolicy allowAll(String version) {
        return new RoutingPolicy(version, Set.of(Channel.values()), Map.of(), Map.of());
    }

    public boolean isEnabled(Channel channel) {
        return enabledChannels.contains(channel);
    }

    /** @return true when the notification's severity clears this channel's configured floor. */
    public boolean meetsSeverityFloor(Channel channel, Severity severity) {
        Severity floor = minimumSeverity.get(channel);
        return floor == null || severity.ordinal() >= floor.ordinal();
    }

    /** @return true when severity alone justifies selecting a channel the submitter did not ask for. */
    public boolean escalatesTo(Channel channel, Severity severity) {
        Severity threshold = escalateAtSeverity.get(channel);
        return threshold != null && severity.ordinal() >= threshold.ordinal();
    }

    public List<Channel> channelOrder() {
        return List.of(Channel.values());
    }
}
