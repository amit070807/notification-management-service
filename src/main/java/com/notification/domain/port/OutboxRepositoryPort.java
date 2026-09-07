package com.notification.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * T012 — the transactional handoff (Principle II).
 *
 * <p>{@link #append} is called inside the acceptance transaction, so the work item cannot be lost
 * or phantom-created if the process dies between commit and enqueue.
 */
public interface OutboxRepositoryPort {

    void append(UUID notificationId, Instant at);

    List<UUID> claimUnprocessed(Instant leaseUntil, int limit);

    void markProcessed(UUID notificationId, Instant at);
}
