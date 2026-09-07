package com.notification.domain.port;

import java.util.UUID;

/**
 * The domain's only source of identifiers (Principle III).
 *
 * <p>Issues the server-side notification identity that is the status retrieval key (spec D4).
 * Direct {@code UUID.randomUUID()} calls inside the domain are rejected by the architecture gate.
 */
public interface IdPort {
    UUID newId();
}
