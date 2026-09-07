package com.notification.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One recorded significant action (source 4.9).
 *
 * <p>{@code payload} is built only by the typed allowlist in {@code com.notification.audit.payload}
 * — never from caller-supplied data. The content payload is structurally excluded: no allowlist
 * member carries it, and audit refers to content only through {@code ContentRef.payloadRef}
 * (FR-051, FR-054).
 */
public record AuditRecord(
        UUID id,
        UUID notificationId,
        String correlationId,
        AuditEventType eventType,
        Instant occurredAt,
        Map<String, String> payload) {}
