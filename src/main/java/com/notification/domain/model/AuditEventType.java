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
    RETRY_BUDGET_EXHAUSTED
}
