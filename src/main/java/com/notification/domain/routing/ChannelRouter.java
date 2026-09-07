package com.notification.domain.routing;

import com.notification.domain.model.Channel;
import com.notification.domain.model.RecipientRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * T064 — channel selection (source 4.3, scoped by spec D2).
 *
 * <p>A pure function of {@code (RoutingRequest, RoutingPolicy)}. It reads no clock, draws no
 * randomness and performs no I/O, which is what makes it a truth table in the unit tests and makes
 * a recorded decision reproducible during incident analysis (FR-023).
 *
 * <p>Identifiers and the timestamp are supplied by the caller rather than generated here, so this
 * class needs neither a clock nor an id port — the architecture gate would reject either.
 *
 * <p><b>Three factors, not four.</b> There is no parameter, field or import here through which a
 * recipient attribute could reach a decision. Source 4.3's recipient-preference factor is deferred
 * (G-26) because the document supplies no preference data, and the preference-independence test
 * fails if that ever changes quietly (FR-019a, SC-013).
 */
public final class ChannelRouter {

    private ChannelRouter() {}

    public static RoutingDecision route(
            UUID decisionId,
            UUID notificationId,
            Instant decidedAt,
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

    /**
     * Evaluation order is the precedence of FR-020a, and is load-bearing:
     *
     * <ol>
     *   <li>A channel the policy has disabled is never selected, whatever was requested.
     *   <li>Severity escalation can add an unrequested channel, but only where the policy
     *       declares it — otherwise "not requested" wins.
     *   <li>A requested channel must still clear the policy's severity floor.
     * </ol>
     */
    private static ChannelOutcome evaluate(
            RecipientRef recipient, Channel channel, RoutingRequest request, RoutingPolicy policy) {

        if (!policy.isEnabled(channel)) {
            return new ChannelOutcome(recipient, channel, false, RoutingReasonCode.CHANNEL_DISABLED);
        }

        boolean requested = request.requestedChannels().contains(channel);

        if (!requested) {
            // Escalation is the only route by which an unrequested channel is selected, and it
            // applies only where the policy explicitly declares it (spec register item 5).
            if (policy.escalatesTo(channel, request.severity())) {
                return new ChannelOutcome(recipient, channel, true, RoutingReasonCode.SEVERITY_ESCALATED);
            }
            return new ChannelOutcome(recipient, channel, false, RoutingReasonCode.NOT_REQUESTED);
        }

        if (!policy.meetsSeverityFloor(channel, request.severity())) {
            return new ChannelOutcome(recipient, channel, false, RoutingReasonCode.POLICY_EXCLUDED);
        }
        return new ChannelOutcome(recipient, channel, true, RoutingReasonCode.REQUESTED_AND_ALLOWED);
    }
}
