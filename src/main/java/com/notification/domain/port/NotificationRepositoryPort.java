package com.notification.domain.port;

import com.notification.domain.model.Notification;
import java.util.Optional;
import java.util.UUID;

/** T012 — notification persistence, addressed by the server-issued identity (spec D4). */
public interface NotificationRepositoryPort {

    void save(Notification notification);

    Optional<Notification> findById(UUID id);

    /** Non-unique by design: duplicates are independent notifications (FR-008b). */
    java.util.List<Notification> findByClientNotificationId(String clientNotificationId);

    void updateState(UUID id, com.notification.domain.state.NotificationState state, java.time.Instant at);
}
