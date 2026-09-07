package com.notification.domain.model;

import com.notification.domain.state.NotificationState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The submitted notification (source 4.1).
 *
 * @param id server-issued identity, and THE key for status retrieval (spec D4)
 * @param clientNotificationId the caller's identifier. Descriptive, not identifying, and
 *     deliberately NOT unique — two submissions sharing one are two independent notifications
 *     (FR-008a, FR-008b)
 * @param notBefore optional; delivery must not be attempted before this (FR-003a)
 * @param expiresAt optional; delivery must not be attempted after this (FR-003b). Source 4.1
 *     writes these as a single "scheduling/expiration timestamp"; spec D3 resolved that into two
 *     independently optional fields
 */
public record Notification(
        UUID id,
        String clientNotificationId,
        String sourceSystem,
        String correlationId,
        NotificationType type,
        Severity severity,
        Priority priority,
        ContentRef content,
        List<RecipientRef> recipients,
        List<Channel> requestedChannels,
        Instant createdAtClient,
        Instant receivedAt,
        Instant notBefore,
        Instant expiresAt,
        NotificationState state,
        Instant stateChangedAt) {

    /** FR-032: expiry is re-evaluated immediately before every attempt, not only at submission. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** FR-031: a not-before time is a floor, not a punctuality guarantee (spec G-29). */
    public boolean isReleasedAt(Instant now) {
        return notBefore == null || !now.isBefore(notBefore);
    }
}
