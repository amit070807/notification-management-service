package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T050 — delivery status broken down by recipient AND channel (FR-012, SC-003).
 *
 * <p>Source 4.2 requires this granularity explicitly, so 2 recipients across 2 channels must
 * produce 4 independently varying entries. A per-notification status, or a per-channel one, would
 * not satisfy it.
 */
class StatusMatrixTest extends SubmissionTestSupport {

    @Autowired private JdbcClient jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void twoRecipientsTimesTwoChannelsYieldsFourEntries() throws Exception {
        UUID id = submitAndGetId("mtx-1");

        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(4));
    }

    @Test
    void everyRecipientChannelPairIsPresentExactlyOnce() throws Exception {
        UUID id = submitAndGetId("mtx-2");

        String body =
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode deliveries = mapper.readTree(body).get("deliveries");

        List<String> pairs =
                java.util.stream.StreamSupport.stream(deliveries.spliterator(), false)
                        .map(d -> d.get("recipientRef").asText() + "/" + d.get("channel").asText())
                        .toList();

        assertThat(pairs)
                .containsExactlyInAnyOrder("user-1/EMAIL", "user-1/SMS", "user-2/EMAIL", "user-2/SMS");
    }

    @Test
    void entriesVaryIndependently() throws Exception {
        UUID id = submitAndGetId("mtx-3");

        // Drive one pair to a different state directly; the others must be unaffected. This is
        // what "by recipient and channel" means in practice — the states are not derived from a
        // single notification-level value.
        jdbc.sql(
                        """
                        UPDATE delivery SET state = 'DELIVERED', state_changed_at = now()
                        WHERE notification_id = ? AND channel = 'EMAIL'
                          AND recipient_id = (SELECT id FROM recipient
                                              WHERE notification_id = ? AND recipient_ref = 'user-1')
                        """)
                .param(id)
                .param(id)
                .update();

        String body =
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode root = mapper.readTree(body);

        long delivered =
                java.util.stream.StreamSupport.stream(root.get("deliveries").spliterator(), false)
                        .filter(d -> "DELIVERED".equals(d.get("state").asText()))
                        .count();
        assertThat(delivered).isOne();

        // Rollup rule 2: any non-terminal delivery means the notification is still in progress.
        assertThat(root.get("state").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void recipientReferenceIsEchoedUnmaskedToTheSubmitter() throws Exception {
        // FR-052 asymmetry: the reference is masked in audit and logs, but a status response goes
        // back to the system that supplied it, so masking there would be useless and unhelpful.
        UUID id = submitAndGetId("mtx-4");
        mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(jsonPath("$.deliveries[0].recipientRef").value("user-1"));
    }

    private UUID submitAndGetId(String clientId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission(clientId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
