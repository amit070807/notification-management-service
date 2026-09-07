package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.domain.model.AuditEventType;
import com.notification.domain.port.AuditRepositoryPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T033 — acceptance is durable, and reports no delivery outcome (FR-029, SC-004, FR-015).
 *
 * <p>The property under test is the one Principle II exists to protect: by the time the caller
 * holds a 202, the notification, its recipients, its routing decision, its deliveries, the audit
 * events and the outbox row are all committed — and no provider has been touched.
 */
class SubmissionAcceptanceTest extends SubmissionTestSupport {

    @Autowired private JdbcClient jdbc;
    @Autowired private AuditRepositoryPort audit;

    @Test
    void acceptsAndReturnsServerIssuedIdentity() throws Exception {
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission("acc-1")))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.clientNotificationId").value("acc-1"))
                .andExpect(jsonPath("$.state").value("ACCEPTED"));
    }

    @Test
    void acceptanceResponseReportsNoDeliveryOutcome() throws Exception {
        // FR-029 / SC-004: acceptance is not delivery. Nothing in the body may suggest an outcome.
        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission("acc-2")))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContain("DELIVERED")
                .doesNotContain("FAILED")
                .doesNotContain("deliveries");
    }

    @Test
    void everythingIsDurablyCommittedBeforeTheCallerIsTold() throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission("acc-3")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        assertThat(count("SELECT count(*) FROM notification WHERE id = ?", id)).isOne();
        assertThat(count("SELECT count(*) FROM recipient WHERE notification_id = ?", id)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM routing_decision WHERE notification_id = ?", id)).isOne();
        // 2 recipients x 2 channels — the granularity source 4.2 requires (FR-014).
        assertThat(count("SELECT count(*) FROM delivery WHERE notification_id = ?", id)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM outbox WHERE notification_id = ?", id)).isOne();

        // No attempt has been made, so nothing is in progress or terminal.
        assertThat(
                        jdbc.sql("SELECT DISTINCT state FROM delivery WHERE notification_id = ?")
                                .param(id)
                                .query(String.class)
                                .list())
                .containsExactly("PENDING");
    }

    @Test
    void acceptanceIsAudited() throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission("acc-4")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        assertThat(audit.findByNotificationId(id))
                .extracting(r -> r.eventType())
                .contains(AuditEventType.NOTIFICATION_ACCEPTED, AuditEventType.ROUTING_DECISION_MADE);
    }

    private int count(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(Integer.class).single();
    }
}
