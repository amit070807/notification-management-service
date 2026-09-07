package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * T036 — the six boundary rules from data-model.md.
 *
 * <p>Includes the two that exist only because spec D3 split source 4.1's single
 * "scheduling/expiration timestamp" into two independently optional fields: a window that can
 * never open (FR-003d), and an expiry that has already passed (FR-003e).
 */
class SubmissionValidationTest extends SubmissionTestSupport {

    @Test
    void rejectsNotBeforeAtOrAfterExpiry() throws Exception {
        // FR-003d: describes a delivery window that can never open. Only reachable because D3
        // resolved the single source field into two.
        String json =
                validSubmission("val-1")
                        .replace(
                                "\"createdAt\": \"2026-09-07T10:00:00Z\"",
                                "\"createdAt\": \"2026-09-07T10:00:00Z\","
                                        + "\"notBefore\": \"2030-01-02T00:00:00Z\","
                                        + "\"expiresAt\": \"2030-01-01T00:00:00Z\"");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).contains("notBefore");
    }

    @Test
    void rejectsExpiryAlreadyInThePast() throws Exception {
        // FR-003e: accepting work that provably cannot be performed would leave the EXPIRED
        // terminal state ambiguous between "expired in flight" and "expired on arrival".
        String json =
                validSubmission("val-2")
                        .replace(
                                "\"createdAt\": \"2026-09-07T10:00:00Z\"",
                                "\"createdAt\": \"2026-09-07T10:00:00Z\","
                                        + "\"expiresAt\": \"2000-01-01T00:00:00Z\"");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).contains("expiresAt");
    }

    @Test
    void acceptsNotBeforeWithoutExpiry() throws Exception {
        // FR-003c: all four combinations of the two optional timestamps are valid.
        String json =
                validSubmission("val-3")
                        .replace(
                                "\"createdAt\": \"2026-09-07T10:00:00Z\"",
                                "\"createdAt\": \"2026-09-07T10:00:00Z\",\"notBefore\": \"2030-01-01T00:00:00Z\"");
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptsExpiryWithoutNotBefore() throws Exception {
        String json =
                validSubmission("val-4")
                        .replace(
                                "\"createdAt\": \"2026-09-07T10:00:00Z\"",
                                "\"createdAt\": \"2026-09-07T10:00:00Z\",\"expiresAt\": \"2030-01-01T00:00:00Z\"");
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptsNeitherTimestamp() throws Exception {
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission("val-5")))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectsEmptyContentBody() throws Exception {
        // FR-058: content is mandatory. The source lists no content field at all, so this is an
        // owner decision (D1/G-23), not source text.
        String json = validSubmission("val-6").replace("\"body\": \"payload-val-6\"", "\"body\": \"\"");
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedContentPayload() throws Exception {
        // FR-059: an unbounded caller-supplied payload is a stability risk. The specific figure is
        // an engineering assumption (G-24), not a source requirement.
        String huge = "x".repeat(70_000);
        String json = validSubmission("val-7").replace("payload-val-7", huge);
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingMandatoryFields() throws Exception {
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
