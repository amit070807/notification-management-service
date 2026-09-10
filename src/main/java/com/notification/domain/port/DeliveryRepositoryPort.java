package com.notification.domain.port;

import com.notification.domain.model.Delivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** T012 — delivery persistence and the worker claim (Principle II). */
public interface DeliveryRepositoryPort {

    void saveAll(List<Delivery> deliveries);

    List<Delivery> findByNotificationId(UUID notificationId);

    /**
     * Claims up to {@code limit} deliveries that are due, taking a lease until {@code leaseUntil}.
     *
     * <p>Implementations MUST make this safe for concurrent workers — two workers must never claim
     * the same delivery. The concurrency test proves it.
     */
    List<Delivery> claimDue(Instant now, Instant leaseUntil, int limit);

    void update(Delivery delivery);

    /**
     * Persists a delivery while <b>keeping</b> its worker lease.
     *
     * <p>{@link #update} clears the lease, which is right for every transition that ends an attempt:
     * the work is finished and the row should be claimable again. It is wrong for the move into
     * {@code IN_PROGRESS}, because the attempt is still running and the lease is the only thing
     * marking it as owned.
     *
     * <p>Clearing it there had two consequences. A concurrent worker could see an unleased row, and —
     * more damagingly — a delivery orphaned mid-attempt could never be recognised as stranded, since
     * recovery keys on an <i>expired</i> lease and a null one never expires.
     */
    void updateKeepingLease(Delivery delivery);
}
