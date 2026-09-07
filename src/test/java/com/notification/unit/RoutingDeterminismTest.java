package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.model.Severity;
import com.notification.domain.routing.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T057 — identical inputs always yield identical selections (FR-023, SC-012). */
class RoutingDeterminismTest {

    private static final RoutingPolicy POLICY =
            new RoutingPolicy("det-v1", Set.of(Channel.EMAIL, Channel.SMS), Map.of(Channel.SMS, Severity.HIGH), Map.of());

    @Test
    void repeatedRoutingProducesIdenticalOutcomes() {
        RoutingRequest request =
                new RoutingRequest(
                        Severity.HIGH,
                        List.of(Channel.EMAIL, Channel.SMS),
                        List.of(new RecipientRef("user-1"), new RecipientRef("user-2")));

        List<ChannelOutcome> first = route(request).outcomes();
        for (int i = 0; i < 100; i++) {
            assertThat(route(request).outcomes()).isEqualTo(first);
        }
    }

    @Test
    void outcomeOrderingIsStable() {
        // Stable ordering matters because the outcomes are persisted positionally alongside
        // generated ids; a reordering would silently mis-associate reasons with channels.
        RoutingRequest request =
                new RoutingRequest(
                        Severity.LOW,
                        List.of(Channel.EMAIL),
                        List.of(new RecipientRef("b"), new RecipientRef("a")));

        List<String> firstOrder = order(route(request));
        for (int i = 0; i < 20; i++) {
            assertThat(order(route(request))).isEqualTo(firstOrder);
        }
    }

    private static RoutingDecision route(RoutingRequest request) {
        return ChannelRouter.route(
                UUID.nameUUIDFromBytes("d".getBytes()),
                UUID.nameUUIDFromBytes("n".getBytes()),
                Instant.parse("2026-09-07T10:00:00Z"),
                request,
                POLICY);
    }

    private static List<String> order(RoutingDecision d) {
        return d.outcomes().stream().map(o -> o.recipient().value() + "/" + o.channel()).toList();
    }
}
