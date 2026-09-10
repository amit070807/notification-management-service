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
 * T060 — scheduling a retry and executing one are distinct recorded actions (FR-150, SC-110).
 *
 * <p>The gap this closes (spec G-51): phase 1 recorded RETRY_SCHEDULED and DELIVERY_ATTEMPTED, and a
 * reader could not tell whether an attempt was a first try or the execution of a scheduled retry —
 * DELIVERY_ATTEMPTED looks identical either way. Section 4.9 asks for significant actions to be
 * recorded, and executing a retry is a different action from deciding to.
 */
class RetryAuditTest extends RetryTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void aRetryableFailureProducesBothASchedulingAndAnExecutionRecord() throws Exception {
        UUID id = submitEmailOnly("retry-audit-1");
        poller.drainOnce();
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        runUntilSettled(3);

        assertThat(countOf(id, "RETRY_SCHEDULED")).isEqualTo(1);
        assertThat(countOf(id, "RETRY_EXECUTED")).isEqualTo(1);
    }

    @Test
    void aFirstAttemptIsNotRecordedAsARetryExecution() throws Exception {
        UUID id = submitEmailOnly("retry-audit-2");
        poller.drainOnce();
        scripts.of(Channel.EMAIL).alwaysSucceed();

        worker.runOnce();

        // If every attempt emitted RETRY_EXECUTED, the record would be worthless: an operator counting
        // retries would count first attempts too, and the retry history would overstate itself by one
        // on every single delivery.
        assertThat(countOf(id, "RETRY_EXECUTED")).isZero();
        assertThat(countOf(id, "DELIVERY_ATTEMPTED")).isEqualTo(1);
    }

    @Test
    void theExecutionRecordNamesTheAttemptItIs() throws Exception {
        UUID id = submitEmailOnly("retry-audit-3");
        poller.drainOnce();
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        runUntilSettled(3);

        List<String> attemptNumbers =
                jdbc.sql(
                                "SELECT payload->>'attemptNumber' FROM audit_event "
                                        + "WHERE notification_id = ? AND event_type = 'RETRY_EXECUTED'")
                        .param(id)
                        .query(String.class)
                        .list();
        assertThat(attemptNumbers).containsExactly("2");
    }

    @Test
    void aNonRetryableFailureSchedulesAndExecutesNothing() throws Exception {
        UUID id = submitEmailOnly("retry-audit-4");
        poller.drainOnce();
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.INVALID_RECIPIENT);

        runUntilSettled(3);

        assertThat(countOf(id, "RETRY_SCHEDULED")).isZero();
        assertThat(countOf(id, "RETRY_EXECUTED")).isZero();
    }

    private int countOf(UUID notificationId, String eventType) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE notification_id = ? AND event_type = ?")
                .param(notificationId)
                .param(eventType)
                .query(Integer.class)
                .single();
    }
}
