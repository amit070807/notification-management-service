package com.notification.domain.dedup;

import com.notification.domain.state.NotificationState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * T051 — whether a submission is a duplicate (FR-141, FR-141b, FR-141c).
 *
 * <p>A pure function so the rules can be stated as a table and asserted exhaustively. It reads no
 * clock: the caller supplies {@code now}, keeping the domain deterministic per Principle III.
 *
 * <p>Two rules carry the weight:
 *
 * <ul>
 *   <li><b>The window</b> (FR-141b, spec D14). 24 hours by default, configurable, and an engineering
 *       assumption rather than a source requirement — neither document states a duration.
 *   <li><b>Terminal-unsuccessful originals do not suppress</b> (FR-141c). Suppressing after a
 *       permanent failure or an expiry would convert a delivery problem into unrecoverable data loss:
 *       the caller's only remedy is to resubmit, and suppression would deny it.
 * </ul>
 *
 * <p>{@code PARTIALLY_FAILED} <i>does</i> suppress, which is the one asymmetry worth stating. Something
 * reached someone, so resubmitting would duplicate for the recipients who did receive it — worse than
 * requiring the caller to retry deliberately under a new event identifier.
 */
public record DeduplicationDecision(boolean suppress, Optional<UUID> originalNotificationId) {

    /**
     * States in which an original has definitively delivered nothing and never will, so a
     * resubmission must be allowed through.
     */
    private static final Set<NotificationState> DOES_NOT_SUPPRESS =
            Set.of(NotificationState.FAILED, NotificationState.EXPIRED);

    /** An existing notification sharing the deduplication key. */
    public record Existing(UUID notificationId, Instant receivedAt, NotificationState state) {}

    public static DeduplicationDecision notSuppressed() {
        return new DeduplicationDecision(false, Optional.empty());
    }

    /**
     * @param existing the most recent notification sharing the key, if any
     * @param now supplied rather than read, so the decision stays a pure function
     * @param window how long an original suppresses later submissions; must be positive
     */
    public static DeduplicationDecision evaluate(
            Optional<Existing> existing, Instant now, Duration window) {

        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "deduplication window must be positive; a zero window would suppress nothing while"
                            + " appearing to be configured");
        }
        if (existing.isEmpty()) {
            return notSuppressed();
        }

        Existing original = existing.get();

        // Inclusive at the edge: an original at exactly the window boundary still suppresses. An
        // off-by-one here is invisible in normal use and surfaces only as an occasional duplicate.
        if (original.receivedAt().isBefore(now.minus(window))) {
            return notSuppressed();
        }
        if (DOES_NOT_SUPPRESS.contains(original.state())) {
            return notSuppressed();
        }
        return new DeduplicationDecision(true, Optional.of(original.notificationId()));
    }
}
