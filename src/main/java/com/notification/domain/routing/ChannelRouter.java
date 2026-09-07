package com.notification.domain.routing;

import com.notification.domain.model.Channel;
import com.notification.domain.model.RecipientRef;
import java.util.ArrayList;
import java.util.List;

/**
 * T040 — channel selection (source 4.3, scoped by spec D2).
 *
 * <p>A pure function of {@code (RoutingRequest, RoutingPolicy)}. It reads no clock, draws no
 * randomness and performs no I/O, which is what makes it a truth table in the unit tests and makes
 * a recorded decision reproducible during incident analysis (FR-023).
 *
 * <p>Identifiers and timestamps for the resulting {@link RoutingDecision} are supplied by the
 * caller rather than generated here, so this class needs neither a clock nor an id port — the
 * architecture gate would reject either.
 */
public final class ChannelRouter {

    private ChannelRouter() {}

    public static RoutingDecision route(
            java.util.UUID decisionId,
            java.util.UUID notificationId,
            java.time.Instant decidedAt,
            RoutingRequest request,
            RoutingPolicy policy) {

        List<ChannelOutcome> outcomes = new ArrayList<>();

        for (RecipientRef recipient : request.recipients()) {
            for (Channel channel : policy.channelOrder()) {
                outcomes.add(evaluate(recipient, channel, request, policy));
            }
        }
        return new RoutingDecision(decisionId, notificationId, policy.version(), decidedAt, List.copyOf(outcomes));
    }

    private static ChannelOutcome evaluate(
            RecipientRef recipient, Channel channel, RoutingRequest request, RoutingPolicy policy) {

        // Order matters and is documented: the policy decides first, because it is the deciding
        // authority (FR-020a). A channel the policy has disabled is never selected regardless of
        // what the submitter asked for.
        if (!policy.isEnabled(channel)) {
            return new ChannelOutcome(recipient, channel, false, RoutingReasonCode.CHANNEL_DISABLED);
        }
        if (!request.requestedChannels().contains(channel)) {
            return new ChannelOutcome(recipient, channel, false, RoutingReasonCode.NOT_REQUESTED);
        }
        if (!policy.meetsSeverityFloor(channel, request.severity())) {
            return new ChannelOutcome(recipient, channel, false, RoutingReasonCode.POLICY_EXCLUDED);
        }
        return new ChannelOutcome(recipient, channel, true, RoutingReasonCode.REQUESTED_AND_ALLOWED);
    }
}
