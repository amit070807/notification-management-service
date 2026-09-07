package com.notification.api.dto;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import com.notification.domain.routing.RoutingReasonCode;
import com.notification.domain.state.DeliveryState;
import com.notification.domain.state.NotificationState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The status response — the four things source 4.2 requires, and nothing sensitive.
 *
 * <p>Deliberately absent: the content payload (FR-056). Deliberately present unmasked: the
 * recipient reference, because this response goes back to the system that supplied it. Masking is
 * for audit and logs, where the reader is not the submitter (FR-052).
 */
public record NotificationStatusResponse(
        UUID id,
        String clientNotificationId,
        String correlationId,
        NotificationState state,
        List<Channel> selectedChannels,
        String routingPolicyVersion,
        List<ChannelOutcomeDto> channelOutcomes,
        List<DeliveryStatusDto> deliveries,
        Instant receivedAt,
        Instant notBefore,
        Instant expiresAt,
        Instant stateChangedAt) {

    /** One entry per recipient AND channel — the granularity source 4.2 mandates (FR-012). */
    public record DeliveryStatusDto(
            String recipientRef,
            Channel channel,
            DeliveryState state,
            int attemptCount,
            FailureClassification lastFailureClassification,
            Instant nextAttemptAt,
            Instant stateChangedAt) {}

    /** Why each candidate channel was selected or excluded (FR-022). */
    public record ChannelOutcomeDto(
            String recipientRef, Channel channel, boolean selected, RoutingReasonCode reasonCode) {}
}
