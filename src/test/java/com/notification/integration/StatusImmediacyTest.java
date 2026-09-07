package com.notification.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * T049 — an acknowledged notification is retrievable immediately (FR-015, SC-002).
 *
 * <p>This is the property that makes acceptance meaningful. The classic failure of an
 * accept-then-process design is a 202 for something not yet durably stored, so that a status read
 * moments later says "unknown" — the API lying about work it claimed to have taken.
 */
class StatusImmediacyTest extends SubmissionTestSupport {

    @Test
    void statusIsAvailableTheInstantAcceptanceReturns() throws Exception {
        UUID id = submitAndGetId("imm-1");

        // No sleep, no retry, no polling. If this needs waiting, Principle II is broken.
        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.state").value("ACCEPTED"));
    }

    @Test
    void reportsNotYetDeliveredRatherThanUnknown() throws Exception {
        UUID id = submitAndGetId("imm-2");

        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                // Every delivery exists and is PENDING — nothing has been attempted yet.
                .andExpect(jsonPath("$.deliveries.length()").value(4))
                .andExpect(jsonPath("$.deliveries[0].state").value("PENDING"))
                .andExpect(jsonPath("$.deliveries[0].attemptCount").value(0));
    }

    @Test
    void reportsAllFourElementsRequiredBySection42() throws Exception {
        UUID id = submitAndGetId("imm-3");

        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").isNotEmpty())            // overall status
                .andExpect(jsonPath("$.selectedChannels").isArray())    // selected channels
                .andExpect(jsonPath("$.deliveries").isArray())          // per recipient AND channel
                .andExpect(jsonPath("$.receivedAt").isNotEmpty())       // relevant timestamps
                .andExpect(jsonPath("$.stateChangedAt").isNotEmpty());
    }

    @Test
    void statusDoesNotExposeTheContentPayload() throws Exception {
        // FR-056: content never appears in a status response.
        String json =
                validSubmission("imm-4").replace("payload-imm-4", "STATUS-LEAK-MARKER");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        String statusBody =
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(statusBody).doesNotContain("STATUS-LEAK-MARKER");
    }

    private UUID submitAndGetId(String clientId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission(clientId)))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
