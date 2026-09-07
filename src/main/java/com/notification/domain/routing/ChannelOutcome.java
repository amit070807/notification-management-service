package com.notification.domain.routing;

import com.notification.domain.model.Channel;
import com.notification.domain.model.RecipientRef;

/** One channel's fate for one recipient, with the reason recorded (FR-022). */
public record ChannelOutcome(
        RecipientRef recipient, Channel channel, boolean selected, RoutingReasonCode reasonCode) {}
