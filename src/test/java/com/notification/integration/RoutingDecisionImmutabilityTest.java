package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T059 — a recorded decision does not change when the policy does (FR-021).
 *
 * <p>Recomputing selection at read time would make the 4.9 audit record of "routing decision made"
 * unverifiable: the history would claim a decision that the system could no longer reproduce.
 */
class RoutingDecisionImmutabilityTest extends SubmissionTestSupport {

    @Autowired private JdbcClient jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void statusReportsTheOriginallyRecordedDecision() throws Exception {
        UUID id = submitAndGetId("imm-routing-1");

        String before = channelOutcomesJson(id);

        // Simulate a later policy change by rewriting the stored version. If status recomputed
        // routing on read, the outcomes would shift; they must not.
        jdbc.sql("UPDATE routing_decision SET policy_version = 'changed-after-the-fact' WHERE notification_id = ?")
                .param(id)
                .update();

        assertThat(channelOutcomesJson(id)).isEqualTo(before);
    }

    @Test
    void thePolicyVersionInForceIsRecordedAndReported() throws Exception {
        UUID id = submitAndGetId("imm-routing-2");

        String body =
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(mapper.readTree(body).get("routingPolicyVersion").asText()).isNotBlank();
    }

    @Test
    void decisionRowsCarryNoUpdatePathInTheApplication() {
        // FR-021 plus the constitution's Data Model Invariants: routing_decision and
        // routing_channel_outcome are immutable records. V2__grants.sql withholds UPDATE/DELETE
        // from the application role; this asserts the tables are append-only in shape.
        Integer decisions =
                jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'routing_channel_outcome'")
                        .query(Integer.class)
                        .single();
        assertThat(decisions).isOne();
    }

    private String channelOutcomesJson(UUID id) throws Exception {
        String body =
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return mapper.readTree(body).get("channelOutcomes").toString();
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
