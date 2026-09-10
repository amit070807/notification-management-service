package com.notification.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * T055 — the body of a suppressed submission (FR-144, ADR-021, spec D13).
 *
 * <p>Returned with <b>200 OK</b>, not 202. {@code 202 Accepted} means accepted for processing, and a
 * suppressed submission creates no delivery — returning it would misreport what happened.
 *
 * <p>The status code carries the signal, not this body. FR-144a requires the response to be
 * distinguishable from an acceptance <i>without reading the body</i>, because a consumer that ignores
 * unfamiliar fields would otherwise see 202 and believe its notification was accepted. That is the
 * silent failure D13 exists to remove, and putting it in a field would have relocated it rather than
 * removed it.
 *
 * <p>{@code originalNotificationId} is what makes the response actionable: a caller that has reused an
 * event identifier can look up what it collided with (spec G-55).
 */
public record SubmissionSuppressed(
        boolean suppressed,
        UUID originalNotificationId,
        String clientNotificationId,
        Instant suppressedAt)
        implements SubmissionResponse {

    public static SubmissionSuppressed of(
            UUID originalNotificationId, String clientNotificationId, Instant suppressedAt) {
        return new SubmissionSuppressed(true, originalNotificationId, clientNotificationId, suppressedAt);
    }
}
