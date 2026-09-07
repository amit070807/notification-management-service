package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.state.DeliveryState;
import com.notification.domain.state.IllegalStateTransitionException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T016 — the delivery transition truth table (Principle IV).
 *
 * <p>Covers every legal transition from data-model.md AND every illegal one: Principle IV requires
 * an illegal transition to raise an error, never be silently applied, so the negative cases carry
 * as much weight as the positive ones.
 */
class DeliveryStateTransitionTest {

    static List<org.junit.jupiter.params.provider.Arguments> legalTransitions() {
        return List.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.PENDING, DeliveryState.QUEUED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.PENDING, DeliveryState.UNDELIVERABLE),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.PENDING, DeliveryState.EXPIRED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.QUEUED, DeliveryState.IN_PROGRESS),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.QUEUED, DeliveryState.EXPIRED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.IN_PROGRESS, DeliveryState.DELIVERED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.IN_PROGRESS, DeliveryState.RETRY_SCHEDULED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.IN_PROGRESS, DeliveryState.FAILED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.IN_PROGRESS, DeliveryState.EXHAUSTED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.IN_PROGRESS, DeliveryState.EXPIRED),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.RETRY_SCHEDULED, DeliveryState.IN_PROGRESS),
                org.junit.jupiter.params.provider.Arguments.of(
                        DeliveryState.RETRY_SCHEDULED, DeliveryState.EXPIRED));
    }

    @ParameterizedTest(name = "{0} -> {1} is legal")
    @MethodSource("legalTransitions")
    void legalTransitionsAreAccepted(DeliveryState from, DeliveryState to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        from.checkTransitionTo(to); // must not throw
    }

    @Test
    void everyTransitionNotInTheTableIsIllegalAndThrows() {
        Set<String> legal =
                legalTransitions().stream()
                        .map(a -> a.get()[0] + "->" + a.get()[1])
                        .collect(java.util.stream.Collectors.toSet());

        for (DeliveryState from : DeliveryState.values()) {
            for (DeliveryState to : DeliveryState.values()) {
                if (legal.contains(from + "->" + to)) {
                    continue;
                }
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s must be illegal", from, to)
                        .isFalse();
                assertThatThrownBy(() -> from.checkTransitionTo(to))
                        .as("%s -> %s must throw, never be silently applied", from, to)
                        .isInstanceOf(IllegalStateTransitionException.class);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = DeliveryState.class,
            names = {"DELIVERED", "FAILED", "EXHAUSTED", "EXPIRED", "UNDELIVERABLE"})
    void terminalStatesHaveNoOutgoingTransitions(DeliveryState terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (DeliveryState to : DeliveryState.values()) {
            assertThat(terminal.canTransitionTo(to)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = DeliveryState.class,
            names = {"PENDING", "QUEUED", "IN_PROGRESS", "RETRY_SCHEDULED"})
    void nonTerminalStatesAreNotTerminal(DeliveryState s) {
        assertThat(s.isTerminal()).isFalse();
    }

    @Test
    void expiredIsReachableFromEveryNonTerminalState() {
        // FR-034: expiry outranks the retry budget, so it must be reachable from backoff too.
        for (DeliveryState s : DeliveryState.values()) {
            if (!s.isTerminal()) {
                assertThat(s.canTransitionTo(DeliveryState.EXPIRED))
                        .as("expiry must be reachable from %s", s)
                        .isTrue();
            }
        }
    }

    @Test
    void exhaustedIsDistinctFromFailed() {
        // FR-044: a budget-exhausted delivery must be distinguishable from a first-attempt
        // permanent failure.
        assertThat(DeliveryState.EXHAUSTED).isNotEqualTo(DeliveryState.FAILED);
    }
}
