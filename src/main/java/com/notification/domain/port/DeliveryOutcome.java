package com.notification.domain.port;

import com.notification.domain.retry.FailureClassification;

/**
 * The result of one delivery attempt, in the domain's vocabulary rather than a provider's.
 *
 * @param success whether the provider accepted the message
 * @param classification null on success; otherwise a member of the closed taxonomy. An adapter
 *     that cannot map an outcome must use {@code UNKNOWN}, never invent a value and never assume
 *     success (FR-041).
 * @param diagnostic short, sanitised, non-content text for operators. Never a provider response
 *     body (Principle V).
 */
public record DeliveryOutcome(
        boolean success, FailureClassification classification, String diagnostic) {

    public static final int MAX_DIAGNOSTIC_LENGTH = 200;

    public DeliveryOutcome {
        if (success && classification != null) {
            throw new IllegalArgumentException("a successful outcome must not carry a classification");
        }
        if (!success && classification == null) {
            throw new IllegalArgumentException(
                    "a failed outcome must be classified; use UNKNOWN if it cannot be mapped");
        }
        if (diagnostic != null && diagnostic.length() > MAX_DIAGNOSTIC_LENGTH) {
            diagnostic = diagnostic.substring(0, MAX_DIAGNOSTIC_LENGTH);
        }
    }

    public static DeliveryOutcome succeeded() {
        return new DeliveryOutcome(true, null, null);
    }

    public static DeliveryOutcome failed(FailureClassification classification, String diagnostic) {
        return new DeliveryOutcome(false, classification, diagnostic);
    }
}
