package com.notification.domain.routing;

/**
 * Why a candidate channel was selected or excluded (FR-022).
 *
 * <p>Source 4.9 requires recording that a routing decision was made, and section 6 requires
 * decisions to be defensible — a decision recorded without its reason satisfies neither.
 *
 * <p>There is deliberately no address-related or preference-related code here. After spec D5 the
 * system holds no address, and after D2 it holds no preference, so it cannot and must not route on
 * either (FR-019a).
 */
public enum RoutingReasonCode {
    REQUESTED_AND_ALLOWED,
    NOT_REQUESTED,
    POLICY_EXCLUDED,
    SEVERITY_ESCALATED,
    CHANNEL_DISABLED
}
