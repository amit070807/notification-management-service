package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.model.Severity;
import com.notification.domain.routing.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T056 — the routing truth table (source 4.3, scoped by spec D2).
 *
 * <p>Every combination of requested channels x severity x policy rule, asserting both the selected
 * set and the reason recorded for each channel. Routing is a pure function, which is exactly what
 * makes a table like this possible — and what makes a recorded decision reproducible during
 * incident analysis (FR-023).
 */
class ChannelRouterTest {

    private static final RecipientRef R = new RecipientRef("user-1");
    private static final Instant AT = Instant.parse("2026-09-07T10:00:00Z");

    /** EMAIL open to everything; SMS gated at HIGH; both enabled. */
    private static RoutingPolicy standardPolicy() {
        return new RoutingPolicy(
                "test-v1",
                java.util.Set.of(Channel.EMAIL, Channel.SMS),
                Map.of(Channel.SMS, Severity.HIGH),
                Map.of());
    }

    static List<Arguments> truthTable() {
        return List.of(
                // requested,                    severity,          channel,      selected, reason
                Arguments.of(List.of(Channel.EMAIL, Channel.SMS), Severity.LOW, Channel.EMAIL, true,
                        RoutingReasonCode.REQUESTED_AND_ALLOWED),
                Arguments.of(List.of(Channel.EMAIL, Channel.SMS), Severity.LOW, Channel.SMS, false,
                        RoutingReasonCode.POLICY_EXCLUDED),
                Arguments.of(List.of(Channel.EMAIL, Channel.SMS), Severity.HIGH, Channel.SMS, true,
                        RoutingReasonCode.REQUESTED_AND_ALLOWED),
                Arguments.of(List.of(Channel.EMAIL, Channel.SMS), Severity.CRITICAL, Channel.SMS, true,
                        RoutingReasonCode.REQUESTED_AND_ALLOWED),
                Arguments.of(List.of(Channel.EMAIL), Severity.CRITICAL, Channel.SMS, false,
                        RoutingReasonCode.NOT_REQUESTED),
                Arguments.of(List.of(Channel.SMS), Severity.MEDIUM, Channel.EMAIL, false,
                        RoutingReasonCode.NOT_REQUESTED),
                Arguments.of(List.of(Channel.SMS), Severity.MEDIUM, Channel.SMS, false,
                        RoutingReasonCode.POLICY_EXCLUDED));
    }

    @ParameterizedTest(name = "requested={0} severity={1} -> {2} selected={3} because {4}")
    @MethodSource("truthTable")
    void routingTruthTable(
            List<Channel> requested,
            Severity severity,
            Channel channel,
            boolean expectedSelected,
            RoutingReasonCode expectedReason) {

        RoutingDecision decision = route(requested, severity, standardPolicy());
        ChannelOutcome outcome = outcomeFor(decision, channel);

        assertThat(outcome.selected()).isEqualTo(expectedSelected);
        assertThat(outcome.reasonCode()).isEqualTo(expectedReason);
    }

    @Test
    void disabledChannelIsNeverSelectedRegardlessOfRequest() {
        // FR-020a: the policy is the deciding authority. A requested channel is a proposal it may
        // decline, not an instruction it must honour.
        RoutingPolicy emailOnly =
                new RoutingPolicy("test-v1", java.util.Set.of(Channel.EMAIL), Map.of(), Map.of());

        ChannelOutcome sms =
                outcomeFor(route(List.of(Channel.EMAIL, Channel.SMS), Severity.CRITICAL, emailOnly), Channel.SMS);

        assertThat(sms.selected()).isFalse();
        assertThat(sms.reasonCode()).isEqualTo(RoutingReasonCode.CHANNEL_DISABLED);
    }

    @Test
    void severityEscalationSelectsAnUnrequestedChannelWhenPolicyDeclaresIt() {
        // Spec register item 5: escalation adds channels ONLY where the policy explicitly declares
        // it. Without that declaration, an unrequested channel stays NOT_REQUESTED.
        RoutingPolicy escalating =
                new RoutingPolicy(
                        "test-v1",
                        java.util.Set.of(Channel.EMAIL, Channel.SMS),
                        Map.of(),
                        Map.of(Channel.SMS, Severity.CRITICAL));

        ChannelOutcome atCritical =
                outcomeFor(route(List.of(Channel.EMAIL), Severity.CRITICAL, escalating), Channel.SMS);
        assertThat(atCritical.selected()).isTrue();
        assertThat(atCritical.reasonCode()).isEqualTo(RoutingReasonCode.SEVERITY_ESCALATED);

        ChannelOutcome belowThreshold =
                outcomeFor(route(List.of(Channel.EMAIL), Severity.HIGH, escalating), Channel.SMS);
        assertThat(belowThreshold.selected()).isFalse();
        assertThat(belowThreshold.reasonCode()).isEqualTo(RoutingReasonCode.NOT_REQUESTED);
    }

    @Test
    void everyCandidateChannelGetsAnOutcomeSoTheDecisionIsExplainable() {
        // FR-022: a decision recorded without its reason satisfies neither 4.9 nor section 6.
        RoutingDecision d = route(List.of(Channel.EMAIL), Severity.LOW, standardPolicy());
        assertThat(d.outcomes()).hasSize(Channel.values().length);
        assertThat(d.outcomes()).allSatisfy(o -> assertThat(o.reasonCode()).isNotNull());
    }

    @Test
    void selectedChannelsMatchTheOutcomes() {
        RoutingDecision d = route(List.of(Channel.EMAIL, Channel.SMS), Severity.HIGH, standardPolicy());
        assertThat(d.selectedChannels()).containsExactlyInAnyOrder(Channel.EMAIL, Channel.SMS);
    }

    @Test
    void policyVersionIsCarriedOnTheDecision() {
        // FR-026 / SC-010: what makes a past decision explicable after the policy changes.
        assertThat(route(List.of(Channel.EMAIL), Severity.LOW, standardPolicy()).policyVersion())
                .isEqualTo("test-v1");
    }

    private static RoutingDecision route(List<Channel> requested, Severity severity, RoutingPolicy policy) {
        return ChannelRouter.route(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AT,
                new RoutingRequest(severity, requested, List.of(R)),
                policy);
    }

    private static ChannelOutcome outcomeFor(RoutingDecision d, Channel c) {
        return d.outcomes().stream().filter(o -> o.channel() == c).findFirst().orElseThrow();
    }
}
