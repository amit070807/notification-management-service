package com.notification.config;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.RetryPolicy;
import java.util.EnumMap;
import java.util.Map;

/**
 * T032 — resolves the retry policy for a channel (FR-132, ADR-022).
 *
 * <p>Resolved once at construction rather than per attempt, so an invalid override fails at startup
 * rather than during a delivery. A configuration error that only surfaces when a particular channel
 * happens to fail is the kind that reaches production.
 *
 * <p>Exists as a distinct type rather than a {@code Map} injected directly so that the bound check in
 * {@link RetryProperties#policyFor} runs for every channel eagerly — including channels that no
 * delivery has yet used.
 */
public class RetryPolicySelector {

    private final Map<Channel, RetryPolicy> byChannel;
    private final RetryPolicy fallback;

    public RetryPolicySelector(RetryProperties properties) {
        this.fallback = properties.toPolicy();
        // policiesByChannel() calls policyFor() for every channel, which is where the
        // MAX_ALLOWED_ATTEMPTS guard fires. Eager resolution turns a bad override into a failed
        // startup rather than a surprise mid-incident.
        this.byChannel = new EnumMap<>(properties.policiesByChannel());
    }

    public RetryPolicy forChannel(Channel channel) {
        return byChannel.getOrDefault(channel, fallback);
    }

    /** The policy applied to any channel without an override. */
    public RetryPolicy defaultPolicy() {
        return fallback;
    }
}
