package com.notification.domain.dedup;

import java.util.Objects;

/**
 * T051 — what makes two submissions duplicates (FR-141, spec D9).
 *
 * <p>The pair {@code (source system, event/correlation identifier)}. Deliberately <b>not</b> the client
 * notification identifier, which phase-1 D4 left explicitly non-unique, and not the recipient —
 * suppression is per notification, because D9's basis is that an event identifier is unique per
 * submitter, so a second submission under the same identifier is the same event and a differing
 * recipient list means the caller erred.
 *
 * <p><b>This rests on a caller contract the service cannot enforce</b> (spec G-55). Neither source
 * document requires the event identifier to be unique, and the database does not constrain it —
 * {@code correlation_id} is indexed, not unique. A caller that reuses one gets notifications
 * suppressed. That is mitigated, not removed, by reporting every suppression in the response (D13):
 * the failure is no longer silent, which was the damaging part.
 *
 * <p>Blank components are rejected rather than tolerated. A blank correlation identifier would make
 * every blank-id submission a duplicate of every other — catastrophic, and silent.
 */
public record DeduplicationKey(String sourceSystem, String correlationId) {

    public DeduplicationKey {
        Objects.requireNonNull(sourceSystem, "sourceSystem must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        if (sourceSystem.isBlank()) {
            throw new IllegalArgumentException("sourceSystem must not be blank");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException(
                    "correlationId must not be blank; a blank key would make every blank-id"
                            + " submission a duplicate of every other");
        }
    }
}
