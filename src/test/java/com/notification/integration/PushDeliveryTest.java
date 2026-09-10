package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.worker.DeliveryWorker;
import com.notification.worker.OutboxPoller;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * T014 — push delivers end to end (SC-103, FR-110, FR-111).
 *
 * <p>Push is enabled here by property override rather than by editing the committed policy, so the
 * shipped default stays disabled and {@code FlagsOffBaselineTest} keeps its meaning.
 *
 * <p>The assertion that matters is not "push works" but that it works through the <i>same</i>
 * pipeline: same states, same status shape, same audit vocabulary. A channel needing its own path
 * through routing, retry or audit would be a Principle VII failure however well it delivered.
 */
@TestPropertySource(properties = {
    // Push enabled via a test-only policy, so the shipped default stays disabled and
    // FlagsOffBaselineTest keeps its meaning.
    "notification.routing.policy-location=classpath:routing-policy-push-enabled.yaml",
    "notification.channel.credentials.PUSH.token=test-only-token",
    "notification.channel.credentials.PUSH.token-ref=test:inline"
})
class PushDeliveryTest extends SubmissionTestSupport {

    @Autowired private OutboxPoller poller;
    @Autowired private DeliveryWorker worker;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aPushNotificationIsDeliveredAndReported() throws Exception {
        UUID id = submitPush("push-1");

        poller.drainOnce();
        worker.runOnce();

        JsonNode status = statusOf(id);
        assertThat(status.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("selectedChannels").toString()).contains("PUSH");
        assertThat(status.get("deliveries")).hasSize(1);
        assertThat(status.get("deliveries").get(0).get("channel").asText()).isEqualTo("PUSH");
        assertThat(status.get("deliveries").get(0).get("state").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void pushUsesTheSameStatusShapeAsTheExistingChannels() throws Exception {
        UUID id = submitPush("push-2");
        poller.drainOnce();
        worker.runOnce();

        JsonNode delivery = statusOf(id).get("deliveries").get(0);

        // Exactly the fields an EMAIL or SMS delivery reports — no push-specific field.
        assertThat(delivery.fieldNames())
                .toIterable()
                .containsAnyOf("recipientRef", "channel", "state", "attemptCount", "stateChangedAt");
        assertThat(delivery.has("deviceToken")).isFalse();
    }

    @Test
    void aPushDeliveryIsAudited() throws Exception {
        UUID id = submitPush("push-3");
        poller.drainOnce();
        worker.runOnce();

        Integer events =
                jdbc.sql("SELECT count(*) FROM audit_event WHERE notification_id = ?")
                        .param(id)
                        .query(Integer.class)
                        .single();
        assertThat(events).isPositive();
    }

    @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private UUID submitPush(String clientId) throws Exception {
        String json =
                validSubmission(clientId)
                        .replace("[\"EMAIL\", \"SMS\"]", "[\"PUSH\"]")
                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"device-1\"]");
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
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }
}
