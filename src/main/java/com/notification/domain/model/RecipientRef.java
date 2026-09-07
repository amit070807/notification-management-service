package com.notification.domain.model;

import java.util.Objects;

/**
 * An opaque recipient reference — and nothing else (spec D5).
 *
 * <p>Source 4.1 requires "one or more recipients" but states no field of a recipient anywhere. An
 * earlier draft assumed submitter-supplied per-channel addresses; that assumption was withdrawn
 * because 4.5 frames recipient validity as a provider verdict at delivery time, and because
 * assuming addresses while deferring preferences (D2) treated the same class of data two ways.
 *
 * <p>This type therefore carries no address, contact value, or channel-specific destination.
 * Adding one is a scope change, not a refinement. How a real provider resolves this reference to
 * a destination is undefined by the source — see spec G-32, a recorded limitation.
 *
 * <p>Assumed to carry personal data: masked in audit, logs and metric labels (FR-052), echoed
 * unmasked only in status responses, to the system that supplied it.
 */
public record RecipientRef(String value) {
    public RecipientRef {
        Objects.requireNonNull(value, "recipient reference must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("recipient reference must not be blank");
        }
    }

    @Override
    public String toString() {
        // Defensive: a stray toString() in a log line must not leak the reference.
        return "RecipientRef[masked]";
    }
}
