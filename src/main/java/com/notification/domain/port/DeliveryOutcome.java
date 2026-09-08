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

    public static final int MAX_DIAGNOSTIC_LENGTH = 32;

    /**
     * A diagnostic must be a short, code-shaped token — never prose.
     *
     * <p>Bounding the LENGTH alone was not enough. A provider that echoes the submitted message in
     * its error text (which real providers do) would otherwise carry that content through the port
     * and into audit. The privacy test caught precisely this.
     *
     * <p>Requiring a code shape makes the leak structurally impossible for any realistic message
     * body: prose contains spaces and punctuation outside this set, so it cannot pass.
     */
    private static final java.util.regex.Pattern SAFE_DIAGNOSTIC =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.:-]{1,32}$");

    static final String REDACTED = "REDACTED_UNSAFE_DIAGNOSTIC";

    public DeliveryOutcome {
        if (success && classification != null) {
            throw new IllegalArgumentException("a successful outcome must not carry a classification");
        }
        if (!success && classification == null) {
            throw new IllegalArgumentException(
                    "a failed outcome must be classified; use UNKNOWN if it cannot be mapped");
        }
        // Fails closed rather than throwing: an adapter returning unexpected text is a bug worth
        // seeing, but it must not abort a delivery, and it must never leak. The redaction marker
        // makes the bug visible in operational data without carrying the offending value.
        if (diagnostic != null && !SAFE_DIAGNOSTIC.matcher(diagnostic).matches()) {
            diagnostic = REDACTED;
        }
    }

    public static DeliveryOutcome succeeded() {
        return new DeliveryOutcome(true, null, null);
    }

    public static DeliveryOutcome failed(FailureClassification classification, String diagnostic) {
        return new DeliveryOutcome(false, classification, diagnostic);
    }
}
