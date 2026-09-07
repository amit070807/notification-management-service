package com.notification.domain.port;

import com.notification.domain.model.AuditRecord;
import java.util.List;
import java.util.UUID;

/**
 * T013 — audit persistence (Principle V).
 *
 * <p>There is deliberately no {@code update} and no {@code delete}. Section 4.9 calls this "Audit
 * History", and a rewritable history is not a history (FR-050). Rather than trusting a reviewer to
 * notice a stray UPDATE, there is no method to call: changing that requires adding one here, which
 * is a visible and arguable change in a pull request. The database grant withholds UPDATE and
 * DELETE as a second layer.
 */
public interface AuditRepositoryPort {

    void append(AuditRecord record);

    List<AuditRecord> findByNotificationId(UUID notificationId);

    List<AuditRecord> findByCorrelationId(String correlationId);
}
