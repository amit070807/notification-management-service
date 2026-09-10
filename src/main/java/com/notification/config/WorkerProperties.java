package com.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker tuning from configuration (ADR-030).
 *
 * <p>The batch size was the constant {@code DeliveryWorker.BATCH = 50}. Severity ordering is only
 * observable when the backlog exceeds one batch (FR-201), so asserting it needed either a configurable
 * batch or a fifty-one delivery fixture per test — and the latter would have made those tests about the
 * batch boundary rather than about severity, while being slow enough to get deleted later.
 *
 * <p>The default is unchanged, so no deployment behaviour moves. That is the whole condition under which
 * turning a constant into a property is a refactor rather than a behaviour change, and it is asserted
 * in both places: here and against the committed configuration file.
 *
 * <p>The worker <b>lease</b> is deliberately not moved here. Feature 002 tied it to the provider read
 * timeout — a lease shorter than the timeout means a merely slow call gets reclaimed mid-flight — and
 * relocating that relationship is not this feature's business.
 *
 * @param batchSize how many due deliveries one worker run claims
 */
@ConfigurationProperties(prefix = "notification.worker")
public record WorkerProperties(Integer batchSize) {

    private static final int DEFAULT_BATCH_SIZE = 50;

    public WorkerProperties {
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        if (batchSize < 1) {
            // A zero or negative batch claims nothing, so the worker would loop forever doing no work
            // and report success on every run. Refusing to start turns a configuration typo into a
            // crash instead of a silent stall that looks like an empty queue.
            throw new IllegalArgumentException(
                    "notification.worker.batch-size must be at least 1, but was " + batchSize
                            + ". A worker with a batch size below one claims nothing and stalls silently.");
        }
    }
}
