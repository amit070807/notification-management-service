package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * T086 — expiry outranks the retry budget (FR-033, FR-034, SC-007).
 *
 * <p>Source 4.1 forbids delivery after expiry; 4.5 requires bounded retry. Neither says which wins
 * when a delivery is sitting in backoff as expiry passes. FR-034 adopts expiry, because attempting
 * afterwards would violate 4.1 outright while stopping early merely forgoes an attempt.
 *
 * <p>The resulting state must be EXPIRED, not FAILED or EXHAUSTED: expiry is not a failure, and
 * conflating them would misreport why a recipient was never reached.
 */
class ExpiryOutranksRetryTest extends RetryTestSupport {

    @Test
    void aDeliveryInBackoffThatCrossesExpiryBecomesExpiredNotExhausted() throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);

        // Expires in 30 minutes — after the first attempt, well before the budget is spent.
        UUID id = submitWithExpiry("expiry-1", Duration.ofMinutes(30));
        poller.drainOnce();

        worker.runOnce(); // attempt 1 fails, retry scheduled
        assertThat(firstDelivery(id).get("state").asText()).isEqualTo("RETRY_SCHEDULED");
        assertThat(firstDelivery(id).get("attemptCount").asInt()).isOne();

        // The window closes while the delivery waits.
        clock.advance(Duration.ofHours(1));
        worker.runOnce();

        var delivery = firstDelivery(id);
        assertThat(delivery.get("state").asText()).isEqualTo("EXPIRED");
        // Budget remained — this is not exhaustion.
        assertThat(delivery.get("attemptCount").asInt()).isOne();
    }

    @Test
    void noFurtherAttemptIsMadeAfterExpiry() throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID id = submitWithExpiry("expiry-2", Duration.ofMinutes(30));
        poller.drainOnce();
        worker.runOnce();

        // Counted from THIS notification's attempt rows, not the provider's call counter: the
        // worker claims every due delivery in the database, so a shared counter also records
        // attempts belonging to other tests' notifications.
        int attemptsBefore = attemptCountFor(id);
        clock.advance(Duration.ofHours(2));
        runUntilSettled(5);

        // SC-007: zero attempts after expiry, no matter how many worker rounds run.
        assertThat(attemptCountFor(id)).isEqualTo(attemptsBefore);
    }

    @Test
    void anExpiredNotificationRollsUpAsExpiredNotFailed() throws Exception {
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID id = submitWithExpiry("expiry-3", Duration.ofMinutes(30));
        poller.drainOnce();
        worker.runOnce();
        clock.advance(Duration.ofHours(1));
        worker.runOnce();

        // Rollup rule 4 sits above rule 6 precisely so this reports EXPIRED.
        assertThat(statusOf(id).get("state").asText()).isEqualTo("EXPIRED");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private int attemptCountFor(UUID notificationId) {
        return jdbc.sql(
                        "SELECT count(*) FROM delivery_attempt a JOIN delivery d ON a.delivery_id = d.id "
                                + "WHERE d.notification_id = ?")
                .param(notificationId)
                .query(Integer.class)
                .single();
    }

    private UUID submitWithExpiry(String clientId, Duration ttl) throws Exception {
        String json =
                validSubmission(clientId)
                        .replace("[\"EMAIL\", \"SMS\"]", "[\"EMAIL\"]")
                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"user-1\"]")
                        .replace(
                                "\"createdAt\": \"2026-09-07T10:00:00Z\"",
                                "\"createdAt\": \"2026-09-07T10:00:00Z\",\"expiresAt\": \""
                                        + clock.now().plus(ttl)
                                        + "\"");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
