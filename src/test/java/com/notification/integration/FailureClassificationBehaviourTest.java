package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T083 — each of the six classifications behaves per the taxonomy (SC-005, FR-039, FR-040).
 *
 * <p>Source 4.5 names five failure kinds and requires retry for "retryable" ones, but never says
 * which are retryable. FR-040 records that partition as design-derived; these tests are what make
 * it observable end to end rather than only in a unit truth table.
 */
class FailureClassificationBehaviourTest extends RetryTestSupport {

    @ParameterizedTest(name = "{0} is retried")
    @EnumSource(
            value = FailureClassification.class,
            names = {"TRANSIENT_PROVIDER_FAILURE", "TIMEOUT", "UNKNOWN"})
    void retryableKindsAreRetriedThenExhausted(FailureClassification classification) throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(classification);
        UUID id = submitEmailOnly("cls-retry-" + classification);
        poller.drainOnce();
        runUntilSettled(8);

        var delivery = firstDelivery(id);
        assertThat(delivery.get("state").asText()).isEqualTo("EXHAUSTED");
        assertThat(delivery.get("attemptCount").asInt()).isEqualTo(5);
        assertThat(delivery.get("lastFailureClassification").asText()).isEqualTo(classification.name());
    }

    @ParameterizedTest(name = "{0} is terminal on the first attempt")
    @EnumSource(
            value = FailureClassification.class,
            names = {"PERMANENT_PROVIDER_REJECTION", "INVALID_RECIPIENT", "AUTH_ERROR"})
    void nonRetryableKindsStopImmediately(FailureClassification classification) throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(classification);
        UUID id = submitEmailOnly("cls-stop-" + classification);
        poller.drainOnce();
        runUntilSettled(8);

        var delivery = firstDelivery(id);
        assertThat(delivery.get("state").asText()).isEqualTo("FAILED");
        // The whole point of the partition: no budget was spent on a failure that cannot succeed.
        assertThat(delivery.get("attemptCount").asInt()).isOne();
        assertThat(delivery.get("lastFailureClassification").asText()).isEqualTo(classification.name());
    }

    @Test
    void aTransientFailureFollowedBySuccessDelivers() throws Exception {
        // The case the whole retry mechanism exists for.
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        UUID id = submitEmailOnly("cls-recover");
        poller.drainOnce();
        runUntilSettled(6);

        var delivery = firstDelivery(id);
        assertThat(delivery.get("state").asText()).isEqualTo("DELIVERED");
        assertThat(delivery.get("attemptCount").asInt()).isEqualTo(3);
        assertThat(statusOf(id).get("state").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void everyAttemptIsRecordedWithItsOwnClassification() throws Exception {
        // FR-043: each attempt separately observable, carrying its own outcome.
        scripts.of(Channel.EMAIL)
                .thenFail(FailureClassification.TIMEOUT)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        UUID id = submitEmailOnly("cls-attempts");
        poller.drainOnce();
        runUntilSettled(6);

        var rows =
                jdbc.sql(
                                """
                                SELECT a.attempt_number, a.outcome, coalesce(a.failure_classification, '-') AS cls
                                FROM delivery_attempt a JOIN delivery d ON a.delivery_id = d.id
                                WHERE d.notification_id = ? ORDER BY a.attempt_number
                                """)
                        .param(id)
                        .query((rs, n) -> rs.getInt(1) + ":" + rs.getString(2) + ":" + rs.getString(3))
                        .list();

        assertThat(rows)
                .containsExactly("1:FAILURE:TIMEOUT", "2:FAILURE:TRANSIENT_PROVIDER_FAILURE", "3:SUCCESS:-");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
}
