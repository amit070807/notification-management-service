package com.notification.domain.model;

/**
 * Closed channel set (ADR-005).
 *
 * <p>The source document never enumerates channels (spec G-18). EMAIL and SMS were chosen in phase
 * 1 as the minimum that still demonstrates per-recipient-per-channel status; PUSH was added by the
 * brownfield scenario's Option 1 (feature 002 FR-110).
 * Values must match {@code contracts/openapi.yaml} exactly — asserted by the enum parity test.
 */
public enum Channel {
    EMAIL,
    SMS,
    PUSH
}
