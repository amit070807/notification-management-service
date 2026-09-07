package com.notification.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A handle to the opaque content payload (spec D1).
 *
 * <p>The domain never sees the payload bytes — only this handle and a non-reversible
 * {@code payloadRef} used when audit needs to refer to content without containing it (FR-051).
 */
public record ContentRef(UUID id, String payloadRef, int sizeBytes) {
    public ContentRef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(payloadRef);
    }
}
