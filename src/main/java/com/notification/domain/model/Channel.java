package com.notification.domain.model;

/**
 * Closed channel set (ADR-005).
 *
 * <p>The source document never enumerates channels (spec G-18); these two were chosen by the
 * project owner as the minimum that still demonstrates per-recipient-per-channel status.
 * Values must match {@code contracts/openapi.yaml} exactly — asserted by the enum parity test.
 */
public enum Channel {
    EMAIL,
    SMS
}
