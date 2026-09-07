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
 * T060 — routing selecting nothing is a real, visible outcome (FR-024, spec G-20).
 *
 * <p>The source does not address the empty-selection case. Leaving such a notification pending
 * forever would be the worst outcome: the caller would wait on something the system had already
 * decided never to attempt.
 */
class EmptyRoutingTest extends SubmissionTestSupport {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void severityBelowEveryChannelFloorProducesNoDeliveries() throws Exception {
        // SMS is gated at HIGH by the configured policy; requesting only SMS at LOW leaves
        // nothing selectable.
        String json = validSubmission("empty-1")
                .replace("\"severity\": \"HIGH\"", "\"severity\": \"LOW\"")
                .replace("[\"EMAIL\", \"SMS\"]", "[\"SMS\"]");

        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        JsonNode status = statusOf(id);
        assertThat(status.get("deliveries")).isEmpty();
        assertThat(status.get("selectedChannels")).isEmpty();
    }

    @Test
    void theExclusionReasonIsStillRecordedSoTheOutcomeIsExplainable() throws Exception {
        String json = validSubmission("empty-2")
                .replace("\"severity\": \"HIGH\"", "\"severity\": \"LOW\"")
                .replace("[\"EMAIL\", \"SMS\"]", "[\"SMS\"]");

        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        // Nothing was delivered, but WHY must still be answerable (FR-022, SC-010).
        JsonNode outcomes = statusOf(id).get("channelOutcomes");
        assertThat(outcomes).isNotEmpty();
        assertThat(outcomes.toString()).contains("POLICY_EXCLUDED");
    }

    @Test
    void aNotificationWithNoDeliveriesIsNotReportedAsInProgressForever() throws Exception {
        String json = validSubmission("empty-3")
                .replace("\"severity\": \"HIGH\"", "\"severity\": \"LOW\"")
                .replace("[\"EMAIL\", \"SMS\"]", "[\"SMS\"]");

        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        // Rollup rule 1: no deliveries exist. It must reach a settled answer, not IN_PROGRESS.
        assertThat(statusOf(id).get("state").asText()).isNotEqualTo("IN_PROGRESS");
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
