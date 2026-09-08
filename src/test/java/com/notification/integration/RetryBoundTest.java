package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T084 and T085 — the bound is real, and exhaustion is distinguishable from failure
 * (FR-038, FR-044, SC-006).
 */
class RetryBoundTest extends RetryTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void noDeliveryEverExceedsTheConfiguredMaximum() throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID id = submitEmailOnly("bound-1");
        poller.drainOnce();

        // Run far more rounds than the budget allows. "Bounded" means additional rounds change
        // nothing once the budget is spent.
        runUntilSettled(20);

        assertThat(firstDelivery(id).get("attemptCount").asInt()).isEqualTo(5);
    }

    @Test
    void noDeliveryInTheWholeDatabaseExceedsTheBound() {
        // SC-006 is stated across a full run, not per notification.
        Integer over =
                jdbc.sql("SELECT count(*) FROM delivery WHERE attempt_count > 5").query(Integer.class).single();
        assertThat(over).isZero();
    }

    @Test
    void exhaustedIsDistinguishableFromAFirstAttemptPermanentFailure() throws Exception {
        // FR-044. Both end unsuccessfully, but they mean very different things operationally:
        // one provider is flaky, the other rejected the message outright.
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID exhausted = submitEmailOnly("bound-exhausted");
        poller.drainOnce();
        runUntilSettled(10);

        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.PERMANENT_PROVIDER_REJECTION);
        UUID failed = submitEmailOnly("bound-failed");
        poller.drainOnce();
        runUntilSettled(3);

        assertThat(firstDelivery(exhausted).get("state").asText()).isEqualTo("EXHAUSTED");
        assertThat(firstDelivery(failed).get("state").asText()).isEqualTo("FAILED");
        assertThat(firstDelivery(exhausted).get("state").asText())
                .isNotEqualTo(firstDelivery(failed).get("state").asText());
    }

    @Test
    void aScheduledRetryReportsWhenItWillNextBeAttempted() throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID id = submitEmailOnly("bound-next");
        poller.drainOnce();
        worker.runOnce(); // one attempt, then backoff

        var delivery = firstDelivery(id);
        assertThat(delivery.get("state").asText()).isEqualTo("RETRY_SCHEDULED");
        assertThat(delivery.get("nextAttemptAt")).isNotNull();
    }
}
