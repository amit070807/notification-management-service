package com.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T001 — per-enhancement switches (ADR-016, FR-104).
 *
 * <p>Every flag defaults to <b>false</b>. FR-105 requires that with all flags off the system is
 * indistinguishable from the phase-1 baseline, which is what makes a flag a rollback mechanism
 * rather than a setting.
 *
 * <p>Configuration properties rather than a flag library: the existing {@code RetryProperties} and
 * {@code ChannelProperties} already provide per-deployment switching, which is what "gradual
 * rollout" means here (spec G-39). A library would add a dependency for capability nobody asked
 * for.
 *
 * <p>Deliberately <b>not</b> runtime-toggleable. Suppression is irreversible — notifications
 * suppressed while deduplication was on were never delivered and cannot be recovered — so flipping
 * it mid-flight would suppress across two states of the world with no clean boundary.
 *
 * <p>The push channel has no flag here on purpose: it is switched by the routing policy's existing
 * per-channel {@code enabled} setting (ADR-017), which also records <i>why</i> a channel was not
 * selected. A second switch would create two sources of truth for one question.
 *
 * @param dedupSubmission suppress duplicate submissions at the boundary (FR-140)
 * @param dedupDelivery send a stable idempotency key on every provider call (FR-161)
 * @param deliveryReclaim recover deliveries stranded mid-attempt (FR-159)
 */
@ConfigurationProperties(prefix = "notification.features")
public record FeatureFlags(
        Boolean dedupSubmission, Boolean dedupDelivery, Boolean deliveryReclaim) {

    public FeatureFlags {
        dedupSubmission = dedupSubmission != null && dedupSubmission;
        dedupDelivery = dedupDelivery != null && dedupDelivery;
        deliveryReclaim = deliveryReclaim != null && deliveryReclaim;
    }

    /** All flags off — the phase-1 baseline. */
    public static FeatureFlags allOff() {
        return new FeatureFlags(false, false, false);
    }
}
