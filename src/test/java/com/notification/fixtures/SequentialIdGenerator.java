package com.notification.fixtures;

import com.notification.domain.port.IdPort;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T014 — predictable identifiers, so a failing test names the same entity every run.
 */
public final class SequentialIdGenerator implements IdPort {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public UUID newId() {
        return new UUID(0L, counter.incrementAndGet());
    }
}
