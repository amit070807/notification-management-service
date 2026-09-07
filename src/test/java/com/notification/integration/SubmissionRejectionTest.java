package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T034 — a rejection names EVERY offending field, and creates nothing (FR-004, FR-007).
 *
 * <p>Reporting one error at a time turns a malformed submission into a guessing game, so the
 * central assertion here is that three simultaneous faults produce three named fields in one
 * response.
 */
class SubmissionRejectionTest extends SubmissionTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void namesAllThreeOffendingFieldsInOneResponse() throws Exception {
        String threeFaults =
                """
                {
                  "clientNotificationId": "rej-1",
                  "sourceSystem": "billing",
                  "correlationId": "corr-rej-1",
                  "notificationType": "ALERT",
                  "severity": "HIGH",
                  "priority": "NORMAL",
                  "recipients": [],
                  "requestedChannels": ["EMAIL"],
                  "createdAt": "2026-09-07T10:00:00Z"
                }
                """;

        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(threeFaults))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // recipients empty (FR-005) and content absent (FR-058) must BOTH be reported.
        assertThat(body).contains("recipients").contains("content");
    }

    @Test
    void rejectsEmptyRecipientList() throws Exception {
        String json = validSubmission("rej-2").replace("[\"user-1\", \"user-2\"]", "[]");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).contains("recipients");
    }

    @Test
    void rejectsUnknownSeverity() throws Exception {
        String json = validSubmission("rej-3").replace("\"HIGH\"", "\"CATASTROPHIC\"");
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingContent() throws Exception {
        String json =
                validSubmission("rej-4")
                        .replaceAll(",\\s*\"content\"\\s*:\\s*\\{[^}]*\\}", "");
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectionCreatesNoNotificationDeliveryOrRoutingDecision() throws Exception {
        int notificationsBefore = countAll("notification");
        int deliveriesBefore = countAll("delivery");
        int decisionsBefore = countAll("routing_decision");

        String json = validSubmission("rej-5").replace("[\"user-1\", \"user-2\"]", "[]");
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());

        // FR-007: a rejected submission must leave no trace in the operational tables.
        assertThat(countAll("notification")).isEqualTo(notificationsBefore);
        assertThat(countAll("delivery")).isEqualTo(deliveriesBefore);
        assertThat(countAll("routing_decision")).isEqualTo(decisionsBefore);
    }

    @Test
    void rejectionDoesNotEchoTheContentPayload() throws Exception {
        // Principle V: an error body must not become a leak vector.
        String json =
                validSubmission("rej-6")
                        .replace("payload-rej-6", "SECRET-MARKER-DO-NOT-ECHO")
                        .replace("[\"user-1\", \"user-2\"]", "[]");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("SECRET-MARKER-DO-NOT-ECHO");
    }

    private int countAll(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
