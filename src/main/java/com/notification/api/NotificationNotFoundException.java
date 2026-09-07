package com.notification.api;

import java.util.UUID;

/** Raised when a server-issued identity was never issued (FR-018). */
public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(UUID id) {
        super("No notification with identity " + id);
    }
}
