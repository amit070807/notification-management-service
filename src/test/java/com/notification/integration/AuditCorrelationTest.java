package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.AuditRecord;
import com.notification.domain.port.AuditRepositoryPort;
import com.notification.domain.retry.FailureClassification;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T098 — the full history is reconstructable from the correlation identifier alone
 * (FR-048, FR-049, SC-010).
 *
 * <p>Section 6 asks for decisions to be defensible. That is only true if someone holding a single
 * identifier can reconstruct what happened and why, without access to the people who built the
 * system.
 */
class AuditCorrelationTest extends RetryTestSupport {

    @Autowired private AuditRepositoryPort audit;

    @Test
    void theWholeLifecycleIsRetrievableByCorrelationIdAlone() throws Exception {
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        UUID id = submitEmailOnly("corr-life");
        poller.drainOnce();
        runUntilSettled(4);

        String correlationId = statusOf(id).get("correlationId").asText();
        List<AuditRecord> history = audit.findByCorrelationId(correlationId);

        assertThat(history).isNotEmpty();
        assertThat(history)
                .extracting(r -> r.eventType().name())
                .contains(
                        "NOTIFICATION_ACCEPTED",
                        "ROUTING_DECISION_MADE",
                        "DELIVERY_QUEUED",
                        "DELIVERY_ATTEMPTED",
                        "DELIVERY_FAILED",
                        "RETRY_SCHEDULED",
                        "DELIVERY_SUCCEEDED");
    }

    @Test
    void everyRecordIsTimestamped() throws Exception {
        UUID id = submitEmailOnly("corr-ts");
        poller.drainOnce();
        worker.runOnce();

        assertThat(audit.findByNotificationId(id))
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.occurredAt()).isNotNull());
    }

    @Test
    void historyIsOrderedSoTheSequenceIsReadable() throws Exception {
        UUID id = submitEmailOnly("corr-order");
        poller.drainOnce();
        worker.runOnce();

        List<AuditRecord> history = audit.findByNotificationId(id);
        assertThat(history).isSortedAccordingTo(java.util.Comparator.comparing(AuditRecord::occurredAt));
        // Acceptance must come first: nothing can precede the decision to take the work.
        assertThat(history.get(0).eventType().name()).isEqualTo("NOTIFICATION_ACCEPTED");
    }

    @Test
    void theRoutingDecisionRecordExplainsWhichChannelsWereChosen() throws Exception {
        // SC-010: reconstructing "why each channel was chosen" from history alone.
        UUID id = submitEmailOnly("corr-why");

        AuditRecord routing =
                audit.findByNotificationId(id).stream()
                        .filter(r -> r.eventType().name().equals("ROUTING_DECISION_MADE"))
                        .findFirst()
                        .orElseThrow();

        assertThat(routing.payload()).containsKey("policyVersion");
        assertThat(routing.payload()).containsKey("selectedChannels");
        assertThat(routing.payload().get("selectedChannels")).contains("EMAIL");
    }
}
