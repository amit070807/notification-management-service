package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T061 — across several retries, each execution pairs unambiguously with its scheduling (FR-150a).
 *
 * <p>Why this is a separate test from {@link RetryAuditTest}: with a single retry the pairing is
 * inferable from ordering, so a one-retry test passes even if the reference is a constant. Only
 * several retries can distinguish a real join key from a decorative field — and audit ordering is
 * explicitly not something to rest a correctness claim on, since audit rows carry no reliable order.
 */
class RetryPairingTest extends RetryTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void everyExecutionJoinsToExactlyOneScheduling() throws Exception {
        UUID id = submitEmailOnly("pairing-1");
        poller.drainOnce();
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        runUntilSettled(6);

        assertThat(refsFor(id, "RETRY_EXECUTED")).hasSize(3);

        // The join, expressed as the query an operator would actually write. Zero unmatched rows is the
        // whole requirement: a reference that named nothing would leave the trail unreadable in exactly
        // the multi-retry case it was added for.
        Integer unmatched =
                jdbc.sql(
                                """
                                SELECT count(*) FROM audit_event e
                                WHERE e.notification_id = ? AND e.event_type = 'RETRY_EXECUTED'
                                  AND NOT EXISTS (
                                    SELECT 1 FROM audit_event s
                                    WHERE s.notification_id = e.notification_id
                                      AND s.event_type = 'RETRY_SCHEDULED'
                                      AND s.payload->>'scheduledRef' = e.payload->>'scheduledRef')
                                """)
                        .param(id)
                        .query(Integer.class)
                        .single();
        assertThat(unmatched).isZero();
    }

    @Test
    void theReferencesAreDistinctAcrossRetries() throws Exception {
        UUID id = submitEmailOnly("pairing-2");
        poller.drainOnce();
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        runUntilSettled(5);

        // A constant reference would satisfy the join above while pairing every execution with every
        // scheduling — a many-to-many that answers no question.
        assertThat(refsFor(id, "RETRY_EXECUTED")).doesNotHaveDuplicates();
        assertThat(refsFor(id, "RETRY_SCHEDULED")).doesNotHaveDuplicates();
    }

    @Test
    void referencesFromDifferentDeliveriesDoNotCollide() throws Exception {
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();
        UUID first = submitEmailOnly("pairing-3a");
        poller.drainOnce();
        runUntilSettled(3);

        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();
        UUID second = submitEmailOnly("pairing-3b");
        poller.drainOnce();
        runUntilSettled(3);

        // Both are attempt 2. Scoping the reference to its delivery is what stops one notification's
        // retry history from bleeding into another's.
        assertThat(refsFor(first, "RETRY_EXECUTED")).doesNotContainAnyElementsOf(refsFor(second, "RETRY_EXECUTED"));
    }

    private List<String> refsFor(UUID notificationId, String eventType) {
        return jdbc.sql(
                        "SELECT payload->>'scheduledRef' FROM audit_event "
                                + "WHERE notification_id = ? AND event_type = ?")
                .param(notificationId)
                .param(eventType)
                .query(String.class)
                .list();
    }
}
