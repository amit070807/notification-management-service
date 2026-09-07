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
}
