package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.worker.DeliveryWorker;
import com.notification.worker.OutboxPoller;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * T070 and T071 — the two timestamp constraints (FR-031, FR-032, SC-007, SC-014).
 *
 * <p>Both exist only because spec D3 resolved source 4.1's single "scheduling/expiration
 * timestamp" into two independently optional fields. Under the single-field reading, only one of
 * these behaviours would be expressible.
 *
 * <p>Time is advanced through the injected clock. Waiting for a not-before window in real time
 * would make this test as slow as the window itself.
 */
class NotBeforeAndExpiryTest extends SubmissionTestSupport {

    @Autowired private OutboxPoller poller;
    @Autowired private DeliveryWorker worker;
    @Autowired private com.notification.fixtures.MutableClock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void noAttemptOccursBeforeTheNotBeforeTime() throws Exception {
        UUID id = submitWithWindow("nb-1", clock.now().plus(Duration.ofHours(2)), null);
        poller.drainOnce();
        worker.runOnce();

        // Queued, but untouched: the window has not opened.
        assertThat(statusOf(id).get("deliveries"))
                .allSatisfy(d -> assertThat(d.get("attemptCount").asInt()).isZero());
    }

    @Test
    void theNotificationBecomesEligibleOnceNotBeforePasses() throws Exception {
        UUID id = submitWithWindow("nb-2", clock.now().plus(Duration.ofHours(2)), null);
        poller.drainOnce();
        worker.runOnce();
        assertThat(statusOf(id).get("state").asText()).isNotEqualTo("COMPLETED");

        clock.advance(Duration.ofHours(3));
        worker.runOnce();

        assertThat(statusOf(id).get("state").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void noAttemptOccursAfterExpiry() throws Exception {
        UUID id = submitWithWindow("nb-3", null, clock.now().plus(Duration.ofMinutes(30)));
        poller.drainOnce();

        clock.advance(Duration.ofHours(1)); // expiry passes before the worker runs
        worker.runOnce();

        JsonNode status = statusOf(id);
        assertThat(status.get("deliveries"))
                .allSatisfy(d -> assertThat(d.get("state").asText()).isEqualTo("EXPIRED"));
        assertThat(status.get("deliveries"))
                .allSatisfy(d -> assertThat(d.get("attemptCount").asInt()).isZero());
        // Expiry is not a failure (FR-034).
        assertThat(status.get("state").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void bothTimestampsAbsentDeliversImmediately() throws Exception {
        UUID id = submitWithWindow("nb-4", null, null);
        poller.drainOnce();
        worker.runOnce();
        assertThat(statusOf(id).get("state").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void bothTimestampsPresentAndOpenDelivers() throws Exception {
        UUID id =
                submitWithWindow(
                        "nb-5", clock.now().minus(Duration.ofMinutes(5)), clock.now().plus(Duration.ofHours(1)));
        poller.drainOnce();
        worker.runOnce();
        assertThat(statusOf(id).get("state").asText()).isEqualTo("COMPLETED");
    }

    private UUID submitWithWindow(String clientId, java.time.Instant notBefore, java.time.Instant expiresAt)
            throws Exception {
        StringBuilder extra = new StringBuilder();
        if (notBefore != null) {
            extra.append(",\"notBefore\":\"").append(notBefore).append('"');
        }
        if (expiresAt != null) {
            extra.append(",\"expiresAt\":\"").append(expiresAt).append('"');
        }
        String json =
                validSubmission(clientId)
                        .replace("\"createdAt\": \"2026-09-07T10:00:00Z\"", "\"createdAt\": \"2026-09-07T10:00:00Z\"" + extra);

        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private JsonNode statusOf(UUID id) throws Exception {
        return mapper.readTree(
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }
}
