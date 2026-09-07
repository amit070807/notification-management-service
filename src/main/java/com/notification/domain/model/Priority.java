package com.notification.domain.model;

/**
 * Notification priority (source 4.1).
 *
 * <p>Captured, stored and returned, but drives no behaviour in this iteration: the source lists
 * priority as a mandatory submission field yet states no effect for it anywhere, and 4.3 names
 * severity — not priority — as the routing factor (spec G-15). Giving it behaviour would be
 * inventing a requirement.
 */
public enum Priority {
    LOW,
    NORMAL,
    HIGH
}
