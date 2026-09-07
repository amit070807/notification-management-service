package com.notification.domain.model;

/** Notification type (source 4.1). Closed set; the source enumerates no values (spec G-18). */
public enum NotificationType {
    ALERT,
    REMINDER,
    TRANSACTIONAL,
    SYSTEM
}
