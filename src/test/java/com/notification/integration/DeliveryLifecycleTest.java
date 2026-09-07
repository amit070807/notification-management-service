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

/**
 * T068 — submit, drain the outbox, attempt, deliver, observe (source 3.1, FR-028, FR-030).
 *
 * <p>The worker is invoked directly rather than waited on. Principle VI forbids sleeping or
 * polling in tests: a suite that waits for a scheduler is slow when it passes and flaky when it
 * fails, and it proves timing rather than behaviour.
 */
class DeliveryLifecycleTest extends SubmissionTestSupport {

    @Autowired private OutboxPoller poller;
    @Autowired private DeliveryWorker worker;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptedNotificationIsDeliveredAndVisibleInStatus() throws Exception {
        UUID id = submitAndGetId("life-1");

        assertThat(statusOf(id).get("state").asText()).isEqualTo("ACCEPTED");

        poller.drainOnce();   // outbox -> QUEUED
        worker.runOnce();     // QUEUED -> IN_PROGRESS -> DELIVERED

        JsonNode after = statusOf(id);
        assertThat(after.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(after.get("deliveries")).allSatisfy(d -> assertThat(d.get("state").asText()).isEqualTo("DELIVERED"));
        assertThat(after.get("deliveries")).allSatisfy(d -> assertThat(d.get("attemptCount").asInt()).isEqualTo(1));
    }

    @Test
    void submissionReturnsBeforeAnyAttemptIsMade() throws Exception {
        // FR-029 / SC-004: the acceptance path must not touch a provider. Immediately after
        // submission, before the worker runs, every delivery is still PENDING.
        UUID id = submitAndGetId("life-2");

        assertThat(statusOf(id).get("deliveries"))
                .allSatisfy(d -> assertThat(d.get("state").asText()).isEqualTo("PENDING"));
        assertThat(statusOf(id).get("deliveries"))
                .allSatisfy(d -> assertThat(d.get("attemptCount").asInt()).isZero());
    }

    @Test
    void drainingTheOutboxQueuesWithoutAttempting() throws Exception {
        UUID id = submitAndGetId("life-3");

        poller.drainOnce();

        assertThat(statusOf(id).get("deliveries"))
                .allSatisfy(d -> assertThat(d.get("state").asText()).isEqualTo("QUEUED"));
        assertThat(statusOf(id).get("state").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void everyAttemptIsSeparatelyObservable() throws Exception {
        // FR-043: 4.9 requires "delivery attempted" as its own recorded action, so an attempt row
        // must exist per try rather than only a running counter.
        UUID id = submitAndGetId("life-4");
        poller.drainOnce();
        worker.runOnce();

        Integer attempts =
                jdbcAttemptCount(id);
        assertThat(attempts).isEqualTo(4); // 2 recipients x 2 channels, one attempt each
    }

    @Test
    void outboxIsNotReprocessedOnASecondDrain() throws Exception {
        UUID id = submitAndGetId("life-5");
        poller.drainOnce();
        poller.drainOnce();
        worker.runOnce();

        assertThat(jdbcAttemptCount(id)).isEqualTo(4);
    }

    private Integer jdbcAttemptCount(UUID id) {
        return jdbc.sql(
                        "SELECT count(*) FROM delivery_attempt a JOIN delivery d ON a.delivery_id = d.id "
                                + "WHERE d.notification_id = ?")
                .param(id)
                .query(Integer.class)
                .single();
    }

    @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private JsonNode statusOf(UUID id) throws Exception {
        return mapper.readTree(
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
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
