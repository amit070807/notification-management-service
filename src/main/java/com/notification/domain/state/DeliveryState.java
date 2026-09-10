package com.notification.domain.state;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * T017 — delivery lifecycle (ADR-004, ADR-007).
 *
 * <p>The source names no states at all (spec G-09); section 4.2 explicitly permits "a different
 * state model if it is documented and defensible", which is the licence this vocabulary uses. The
 * transition table below is the documentation.
 *
 * <p>Three distinctions are load-bearing:
 *
 * <ul>
 *   <li>{@code EXHAUSTED} is not {@code FAILED} — a spent retry budget must be distinguishable
 *       from a first-attempt permanent rejection (FR-044).
 *   <li>{@code EXPIRED} is not a failure state at all — expiry outranks the retry budget and is
 *       not something that went wrong (FR-034).
 *   <li>{@code UNDELIVERABLE} covers routing selecting no channel, which the source does not
 *       address (FR-024, spec G-20).
 * </ul>
 */
public enum DeliveryState {
    PENDING,
    QUEUED,
    IN_PROGRESS,
    RETRY_SCHEDULED,
    DELIVERED,
    FAILED,
    EXHAUSTED,
    EXPIRED,
    UNDELIVERABLE;

    private static final Set<DeliveryState> TERMINAL =
            EnumSet.of(DELIVERED, FAILED, EXHAUSTED, EXPIRED, UNDELIVERABLE);

    private Set<DeliveryState> allowed = Collections.emptySet();

    static {
        PENDING.allowed = EnumSet.of(QUEUED, UNDELIVERABLE, EXPIRED);
        QUEUED.allowed = EnumSet.of(IN_PROGRESS, EXPIRED);
        // QUEUED is reachable from IN_PROGRESS for reclaim (feature 002 FR-159): a worker or
        // provider that stopped mid-attempt leaves the row here, and without this transition it is
        // stranded forever — never terminal, and reported IN_PROGRESS indefinitely by the rollup.
        IN_PROGRESS.allowed = EnumSet.of(DELIVERED, RETRY_SCHEDULED, FAILED, EXHAUSTED, EXPIRED, QUEUED);
        RETRY_SCHEDULED.allowed = EnumSet.of(IN_PROGRESS, EXPIRED);
        // Terminal states keep the empty set.
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(DeliveryState target) {
        return allowed.contains(target);
    }

    /** @throws IllegalStateTransitionException if the transition is not in the table. */
    public void checkTransitionTo(DeliveryState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
    }

    public Set<DeliveryState> allowedTransitions() {
        return Collections.unmodifiableSet(allowed);
    }
}
