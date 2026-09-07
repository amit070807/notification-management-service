package com.notification.api.dto;

import com.notification.domain.state.NotificationState;
import java.time.Instant;
import java.util.UUID;

/**
 * The 202 body.
 *
 * <p>{@code id} is the server-issued identity and the key for status retrieval (spec D4).
 * {@code clientNotificationId} is echoed back but is descriptive, not identifying (FR-008a).
 *
 * <p>Note what is absent: no delivery outcome of any kind. Acceptance is not delivery, and nothing
 * here may suggest otherwise (FR-029).
 */
public record SubmissionAcceptedResponse(
        UUID id, String clientNotificationId, NotificationState state, Instant receivedAt) {}
