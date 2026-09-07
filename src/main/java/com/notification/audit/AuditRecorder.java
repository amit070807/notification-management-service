package com.notification.audit;

import com.notification.audit.payload.AuditPayload;
import com.notification.domain.model.AuditEventType;
import com.notification.domain.model.AuditRecord;
import com.notification.domain.port.AuditRepositoryPort;
import com.notification.domain.port.ClockPort;
import com.notification.domain.port.IdPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes audit events from the typed allowlist.
 *
 * <p>Deliberately the only way to create an {@link AuditRecord}: the payload must come from a
 * sealed {@link AuditPayload}, so no caller can add an arbitrary field and no code path can put
 * the content payload into audit by accident (FR-054).
 */
@Component
public class AuditRecorder {

    private final AuditRepositoryPort repository;
    private final ClockPort clock;
    private final IdPort ids;

    public AuditRecorder(AuditRepositoryPort repository, ClockPort clock, IdPort ids) {
        this.repository = repository;
        this.clock = clock;
        this.ids = ids;
    }

    public void record(UUID notificationId, String correlationId, AuditEventType type, AuditPayload payload) {
        repository.append(
                new AuditRecord(ids.newId(), notificationId, correlationId, type, clock.now(), payload.fields()));
    }
}
