package com.notification.domain.routing;

import com.notification.domain.model.Channel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The recorded outcome of channel selection — immutable once written (FR-021).
 *
 * <p>Persisted at decision time and reported unchanged by later status reads. A subsequent policy
 * change must not retroactively alter it, which is why {@code policyVersion} is captured here
 * (FR-026, SC-010).
 */
public record RoutingDecision(
        UUID id, UUID notificationId, String policyVersion, Instant decidedAt, List<ChannelOutcome> outcomes) {

    public List<Channel> selectedChannels() {
        return outcomes.stream().filter(ChannelOutcome::selected).map(ChannelOutcome::channel).distinct().toList();
    }

    public boolean isSelected(com.notification.domain.model.RecipientRef recipient, Channel channel) {
        return outcomes.stream()
                .anyMatch(o -> o.selected() && o.channel() == channel && o.recipient().equals(recipient));
    }
}
