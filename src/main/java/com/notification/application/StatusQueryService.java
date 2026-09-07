package com.notification.application;

import com.notification.api.NotificationNotFoundException;
import com.notification.api.dto.NotificationStatusResponse;
import com.notification.api.dto.NotificationStatusResponse.ChannelOutcomeDto;
import com.notification.api.dto.NotificationStatusResponse.DeliveryStatusDto;
import com.notification.domain.model.Channel;
import com.notification.domain.model.Notification;
import com.notification.domain.port.NotificationRepositoryPort;
import com.notification.domain.port.ClockPort;
import com.notification.domain.state.NotificationState;
import com.notification.persistence.JdbcStatusRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T053 — assembles the status response (source 4.2).
 *
 * <p>The overall state is computed by the rollup function rather than read from the stored column.
 * The two can disagree while deliveries are progressing: the stored value is updated by the worker
 * as a checkpoint, but the authoritative answer is always the deterministic function of the
 * current delivery states (FR-016). Reporting the derived value keeps status truthful even if a
 * worker died mid-update.
 */
@Service
public class StatusQueryService {

    private final NotificationRepositoryPort notifications;
    private final JdbcStatusRepository statuses;
    private final ClockPort clock;

    public StatusQueryService(
            NotificationRepositoryPort notifications, JdbcStatusRepository statuses, ClockPort clock) {
        this.notifications = notifications;
        this.statuses = statuses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationStatusResponse statusOf(UUID id) {
        Notification n = notifications.findById(id).orElseThrow(() -> new NotificationNotFoundException(id));

        List<DeliveryStatusDto> deliveries = statuses.deliveriesFor(id);
        List<ChannelOutcomeDto> outcomes = statuses.outcomesFor(id);

        NotificationState rolledUp =
                NotificationState.rollup(
                        deliveries.stream().map(DeliveryStatusDto::state).toList(), n.isExpiredAt(clock.now()));

        List<Channel> selected =
                outcomes.stream().filter(ChannelOutcomeDto::selected).map(ChannelOutcomeDto::channel).distinct().toList();

        return new NotificationStatusResponse(
                n.id(),
                n.clientNotificationId(),
                n.correlationId(),
                rolledUp,
                selected,
                statuses.policyVersionFor(id).orElse(null),
                outcomes,
                deliveries,
                n.receivedAt(),
                n.notBefore(),
                n.expiresAt(),
                n.stateChangedAt());
    }
}
