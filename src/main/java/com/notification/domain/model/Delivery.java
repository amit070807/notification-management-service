package com.notification.domain.model;

import com.notification.domain.retry.FailureClassification;
import com.notification.domain.state.DeliveryState;
import java.time.Instant;
import java.util.UUID;

/**
 * One unit of work: a single recipient on a single channel.
 *
 * <p>This granularity is forced by source 4.2, which requires "delivery status by recipient and
 * channel". A per-notification state would not satisfy it (FR-014).
 */
public record Delivery(
        UUID id,
        UUID notificationId,
        UUID recipientId,
        RecipientRef recipientRef,
        Channel channel,
        DeliveryState state,
        int attemptCount,
        Instant nextAttemptAt,
        FailureClassification lastFailureClassification,
        Instant stateChangedAt) {}
