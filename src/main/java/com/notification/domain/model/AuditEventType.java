package com.notification.domain.model;

/**
 * The significant actions recorded by audit history.
 *
 * <p>The first eight are named by source 4.9. {@code DELIVERY_EXPIRED} and
 * {@code RETRY_BUDGET_EXHAUSTED} are added under FR-053 — 4.9 introduces its list with "such as",
 * which makes it non-exhaustive, and both correspond to real terminal states the state machine
 * produces.
 */
public enum AuditEventType {
    NOTIFICATION_ACCEPTED,
    NOTIFICATION_REJECTED,
    ROUTING_DECISION_MADE,
    DELIVERY_QUEUED,
    DELIVERY_ATTEMPTED,
    DELIVERY_SUCCEEDED,
    DELIVERY_FAILED,
    RETRY_SCHEDULED,
    DELIVERY_EXPIRED,
    RETRY_BUDGET_EXHAUSTED,

    // Added by feature 002.
    /** A submission suppressed as a duplicate (FR-143). Suppression must never be silent. */
    NOTIFICATION_SUPPRESSED,
    /**
     * A scheduled retry actually running (FR-150). Distinct from RETRY_SCHEDULED, which is only the
     * decision to try again; without both, a reader cannot tell a retry that ran from one that was
     * merely planned.
     */
    RETRY_EXECUTED,
    /** A delivery stranded mid-attempt, recovered once its lease expired (FR-159). */
    DELIVERY_RECLAIMED
}
