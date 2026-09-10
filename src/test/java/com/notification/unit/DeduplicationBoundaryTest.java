package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.dedup.DeduplicationDecision;
import com.notification.domain.dedup.DeduplicationKey;
import com.notification.domain.state.NotificationState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T040 — the deduplication boundary (FR-141, FR-141b, FR-141c; spec D9, D14).
 *
 * <p>The source delegates this decision outright ("design a deduplication strategy"), so every rule
 * here is a recorded choice rather than a derivation, and each is asserted because a wrong one
 * suppresses notifications that should have been delivered — this feature's most damaging failure.
 *
 * <p>Kept as a pure function so the rules can be stated as a table. The window and the terminal-state
 * exclusion are the two that change user-visible behaviour.
 */
class DeduplicationBoundaryTest {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private static final UUID ORIGINAL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static DeduplicationKey key(String source, String correlation) {
        return new DeduplicationKey(source, correlation);
    }

    private static DeduplicationDecision.Existing existing(
            Instant receivedAt, NotificationState state) {
        return new DeduplicationDecision.Existing(ORIGINAL, receivedAt, state);
    }

    @Test
    void theKeyIsSourceSystemAndCorrelationIdentifier() {
        // Spec D9. Not the client notification identifier, which phase-1 D4 deliberately left
        // non-unique, and not the recipient — suppression is per notification.
        assertThat(key("billing", "evt-1")).isEqualTo(key("billing", "evt-1"));
        assertThat(key("billing", "evt-1")).isNotEqualTo(key("crm", "evt-1"));
        assertThat(key("billing", "evt-1")).isNotEqualTo(key("billing", "evt-2"));
    }

    @Test
    void noPriorNotificationMeansNoSuppression() {
        assertThat(DeduplicationDecision.evaluate(Optional.empty(), NOW, WINDOW).suppress()).isFalse();
    }

    @Test
    void aRecentInFlightOriginalSuppresses() {
        // In-flight must count, or the window has a hole exactly where duplicates are most likely:
        // two submissions moments apart.
        var decision =
                DeduplicationDecision.evaluate(
                        Optional.of(existing(NOW.minus(Duration.ofMinutes(5)), NotificationState.IN_PROGRESS)),
                        NOW,
                        WINDOW);

        assertThat(decision.suppress()).isTrue();
        assertThat(decision.originalNotificationId()).contains(ORIGINAL);
    }

    @Test
    void aRecentCompletedOriginalSuppresses() {
        assertThat(
                        DeduplicationDecision.evaluate(
                                        Optional.of(
                                                existing(NOW.minus(Duration.ofHours(1)), NotificationState.COMPLETED)),
                                        NOW,
                                        WINDOW)
                                .suppress())
                .isTrue();
    }

    @Test
    void anOriginalOlderThanTheWindowDoesNotSuppress() {
        assertThat(
                        DeduplicationDecision.evaluate(
                                        Optional.of(
                                                existing(
                                                        NOW.minus(Duration.ofHours(25)),
                                                        NotificationState.COMPLETED)),
                                        NOW,
                                        WINDOW)
                                .suppress())
                .isFalse();
    }

    @Test
    void theWindowBoundaryIsInclusiveAtExactlyTheEdge() {
        // Stated rather than left to chance: an original at exactly 24h still suppresses. An
        // off-by-one here is invisible in normal use and only shows up as an occasional duplicate.
        assertThat(
                        DeduplicationDecision.evaluate(
                                        Optional.of(existing(NOW.minus(WINDOW), NotificationState.COMPLETED)),
                                        NOW,
                                        WINDOW)
                                .suppress())
                .isTrue();
    }

    @Test
    void aTerminallyFailedOriginalDoesNotSuppress() {
        // FR-141c. Suppressing after a permanent failure would convert a delivery problem into
        // unrecoverable data loss: the caller's only remedy is to resubmit, and this would deny it.
        assertThat(
                        DeduplicationDecision.evaluate(
                                        Optional.of(existing(NOW.minusSeconds(60), NotificationState.FAILED)),
                                        NOW,
                                        WINDOW)
                                .suppress())
                .isFalse();
    }

    @Test
    void aPartiallyFailedOriginalStillSuppresses() {
        // Something reached someone. Resubmitting would duplicate for the recipients who did receive
        // it, which is worse than the caller retrying deliberately with a new event identifier.
        assertThat(
                        DeduplicationDecision.evaluate(
                                        Optional.of(
                                                existing(NOW.minusSeconds(60), NotificationState.PARTIALLY_FAILED))
                                                ,
                                        NOW,
                                        WINDOW)
                                .suppress())
                .isTrue();
    }

    @Test
    void anExpiredOriginalDoesNotSuppress() {
        // Expiry means nothing was delivered and nothing will be. Treating it like a success would
        // silently drop the caller's second, still-valid attempt.
        assertThat(
                        DeduplicationDecision.evaluate(
                                        Optional.of(existing(NOW.minusSeconds(60), NotificationState.EXPIRED)),
                                        NOW,
                                        WINDOW)
                                .suppress())
                .isFalse();
    }

    @Test
    void anAcceptedButUnstartedOriginalSuppresses() {
        assertThat(
                        DeduplicationDecision.evaluate(
                                        Optional.of(existing(NOW.minusSeconds(1), NotificationState.ACCEPTED)),
                                        NOW,
                                        WINDOW)
                                .suppress())
                .isTrue();
    }

    @Test
    void theDecisionIsDeterministic() {
        var existing = Optional.of(existing(NOW.minusSeconds(60), NotificationState.COMPLETED));
        var first = DeduplicationDecision.evaluate(existing, NOW, WINDOW);
        for (int i = 0; i < 50; i++) {
            assertThat(DeduplicationDecision.evaluate(existing, NOW, WINDOW)).isEqualTo(first);
        }
    }

    @Test
    void aBlankKeyComponentIsRejected() {
        // The key is built from caller-supplied fields. A blank correlation identifier would make
        // every blank-id submission a duplicate of every other, which is catastrophic and silent.
        assertThatThrownBy(() -> key("billing", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key(" ", "evt-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key(null, "evt-1")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNonPositiveWindowIsRejected() {
        assertThatThrownBy(
                        () ->
                                DeduplicationDecision.evaluate(
                                        Optional.of(existing(NOW, NotificationState.COMPLETED)), NOW, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
