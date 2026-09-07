package com.notification.worker;

import com.notification.application.DeliveryProcessingService;
import com.notification.domain.model.Delivery;
import com.notification.domain.port.ClockPort;
import com.notification.domain.port.DeliveryRepositoryPort;
import java.time.Duration;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T079 — claims due deliveries and processes them.
 *
 * <p>{@link #runOnce()} is public and driven directly by tests. Principle VI forbids waiting on a
 * scheduler: a test that sleeps proves timing rather than behaviour, is slow when it passes, and
 * is flaky when it fails.
 *
 * <p>Each delivery is processed in its own transaction, so one poisonous delivery cannot roll back
 * the progress of its batch-mates.
 */
@Component
public class DeliveryWorker {

    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final int BATCH = 50;

    private final DeliveryRepositoryPort deliveries;
    private final DeliveryProcessingService processing;
    private final ClockPort clock;

    public DeliveryWorker(
            DeliveryRepositoryPort deliveries, DeliveryProcessingService processing, ClockPort clock) {
        this.deliveries = deliveries;
        this.processing = processing;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification.worker.poll-interval-ms:1000}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        List<Delivery> due = deliveries.claimDue(clock.now(), clock.now().plus(LEASE), BATCH);
        for (Delivery delivery : due) {
            processing.process(delivery);
        }
        return due.size();
    }
}
