package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.model.Severity;
import com.notification.domain.routing.*;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T058 — routing is provably independent of anything recipient-specific (SC-013).
 *
 * <p>This test exists to protect a deferral. Source 4.3 names recipient preferences as a routing
 * factor; spec D2 excludes them because the document supplies no preference data and defines no
 * preference source (G-05), and FR-019a forbids substituting a proxy under another name.
 *
 * <p>The risk is quiet erosion: someone later adds a per-recipient attribute — a channel list, a
 * suppression flag, a quiet-hours window — and routing starts consulting it while the
 * documentation still says preferences are out of scope. That would be worse than either doing
 * the work or not, because the deliverable would misdescribe itself.
 */
class RoutingPreferenceIndependenceTest {

    private static final RoutingPolicy POLICY =
            new RoutingPolicy("pref-v1", Set.of(Channel.EMAIL, Channel.SMS), Map.of(), Map.of());

    @Test
    void differentRecipientsProduceIdenticalChannelSelections() {
        List<Channel> a = selectionsFor(new RecipientRef("alice"));
        List<Channel> b = selectionsFor(new RecipientRef("bob"));
        List<Channel> c = selectionsFor(new RecipientRef("zzz-9999"));

        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    void differentRecipientsProduceIdenticalReasonCodes() {
        assertThat(reasonsFor(new RecipientRef("alice"))).isEqualTo(reasonsFor(new RecipientRef("bob")));
    }

    @Test
    void routingRequestExposesNoRecipientAttributeToConsult() {
        // The structural guard. RoutingRequest carries recipients only so an outcome can be
        // recorded per recipient; if a component ever appears here that could describe a
        // recipient's disposition, this fails and the reviewer has to justify it.
        List<String> components =
                java.util.Arrays.stream(RoutingRequest.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertThat(components)
                .as("RoutingRequest must carry exactly the three factors of spec D2")
                .containsExactlyInAnyOrder("severity", "requestedChannels", "recipients");
    }

    @Test
    void recipientRefItselfCarriesNothingButAnOpaqueValue() {
        // Spec D5: a recipient is an opaque reference. If an address, preference or channel list
        // is ever added to it, routing could consult it and G-26 would be eroded.
        List<String> components =
                java.util.Arrays.stream(RecipientRef.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertThat(components).containsExactly("value");
    }

    private static List<Channel> selectionsFor(RecipientRef recipient) {
        return route(recipient).selectedChannels();
    }

    private static List<RoutingReasonCode> reasonsFor(RecipientRef recipient) {
        return route(recipient).outcomes().stream().map(ChannelOutcome::reasonCode).toList();
    }

    private static RoutingDecision route(RecipientRef recipient) {
        return ChannelRouter.route(
                UUID.nameUUIDFromBytes("d".getBytes()),
                UUID.nameUUIDFromBytes("n".getBytes()),
                Instant.parse("2026-09-07T10:00:00Z"),
                new RoutingRequest(Severity.HIGH, List.of(Channel.EMAIL, Channel.SMS), List.of(recipient)),
                POLICY);
    }
}
