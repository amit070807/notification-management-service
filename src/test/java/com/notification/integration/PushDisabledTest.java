package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * T015 — push is inert when disabled (FR-105, ADR-017, quickstart scenario 2).
 *
 * <p>Uses the <b>shipped</b> policy deliberately: no property override, so this asserts the default a
 * deployment actually gets.
 *
 * <p>"Inert" here means more than "produces nothing". The routing decision records
 * {@code CHANNEL_DISABLED}, so an operator asking why a push notification was not delivered gets an
 * answer from the audit trail rather than silence. That is why ADR-017 reused the policy's existing
 * switch instead of adding a boolean elsewhere — a separate flag could turn the channel off but
 * could not explain itself in the decision.
 */
class PushDisabledTest extends SubmissionTestSupport {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aPushRequestIsAcceptedButProducesNoDelivery() throws Exception {
        UUID id = submitPushOnly("push-off-1");

        JsonNode status = statusOf(id);
        assertThat(status.get("deliveries")).isEmpty();
        assertThat(status.get("selectedChannels")).isEmpty();
    }

    @Test
    void theExclusionIsExplainedRatherThanSilent() throws Exception {
        UUID id = submitPushOnly("push-off-2");

        JsonNode outcomes = statusOf(id).get("channelOutcomes");
        assertThat(outcomes).isNotEmpty();

        JsonNode push =
                java.util.stream.StreamSupport.stream(outcomes.spliterator(), false)
                        .filter(o -> "PUSH".equals(o.get("channel").asText()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no PUSH outcome recorded"));

        assertThat(push.get("selected").asBoolean()).isFalse();
        assertThat(push.get("reasonCode").asText()).isEqualTo("CHANNEL_DISABLED");
    }

    @Test
    void aRequestIsStillAcceptedRatherThanRejected() throws Exception {
        // Requesting a disabled channel is not a caller error: the submission is valid and the
        // routing decision is what declines it. Rejecting at the boundary would conflate the two.
        String json =
                validSubmission("push-off-3")
                        .replace("[\"EMAIL\", \"SMS\"]", "[\"PUSH\"]")
                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"device-1\"]");

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());
    }

    private UUID submitPushOnly(String clientId) throws Exception {
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
