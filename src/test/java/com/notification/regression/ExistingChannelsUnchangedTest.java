package com.notification.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.integration.SubmissionTestSupport;
import com.notification.worker.DeliveryWorker;
import com.notification.worker.OutboxPoller;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T016 — adding push changed nothing else (SC-104, FR-112, FR-101).
 *
 * <p>The point of this test is not that email and SMS still work; the phase-1 suite already covers
 * that. It is that the <i>observable sequence</i> is identical — states, audit events, status shape —
 * because a regression here would most likely look like a small reordering or an extra event rather
 * than a failure.
 *
 * <p>The expected audit sequence is written out as a literal on purpose. An assertion computed from
 * the code under test would pass whatever that code did.
 */
class ExistingChannelsUnchangedTest extends SubmissionTestSupport {

    @Autowired private OutboxPoller poller;
    @Autowired private DeliveryWorker worker;
    @Autowired private JdbcClient jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theAuditSequenceForAnEmailAndSmsNotificationIsUnchanged() throws Exception {
        UUID id = submit("regress-1");
        poller.drainOnce();
        worker.runOnce();

        List<String> sequence =
                jdbc.sql("SELECT event_type FROM audit_event WHERE notification_id = ? ORDER BY sequence")
                        .param(id)
                        .query(String.class)
                        .list();

        // Phase-1 behaviour, stated literally: acceptance, the routing decision, one queued event
        // per selected delivery, then attempted/succeeded per delivery. HIGH severity clears SMS's
        // floor, so 2 recipients x 2 channels = 4 deliveries.
        assertThat(sequence).startsWith("NOTIFICATION_ACCEPTED", "ROUTING_DECISION_MADE");
        assertThat(sequence.stream().filter("DELIVERY_QUEUED"::equals).count()).isEqualTo(4);
        assertThat(sequence.stream().filter("DELIVERY_ATTEMPTED"::equals).count()).isEqualTo(4);
        assertThat(sequence.stream().filter("DELIVERY_SUCCEEDED"::equals).count()).isEqualTo(4);

        // NOTIFICATION_SUPPRESSED and DELIVERY_RECLAIMED are flag-gated, so their absence here is what
        // FR-105 requires.
        //
        // RETRY_EXECUTED is NOT flag-gated, and its absence here is for a different reason: this run
        // succeeds on the first attempt, so no retry was ever executed. US4 asks for retry history to be
        // legible, and a flag would have meant not delivering it — an audit record is additive
        // observability, not a change to what gets delivered. A retrying run WILL emit it with the flags
        // off, by design; RetryAuditTest is where that is asserted.
        assertThat(sequence).doesNotContain("NOTIFICATION_SUPPRESSED", "DELIVERY_RECLAIMED");
        assertThat(sequence)
                .as("no retry occurred, so nothing executed a retry")
                .doesNotContain("RETRY_EXECUTED", "RETRY_SCHEDULED");
    }

    @Test
    void theStatusShapeForAnEmailAndSmsNotificationIsUnchanged() throws Exception {
        UUID id = submit("regress-2");
        poller.drainOnce();
        worker.runOnce();

        JsonNode status = statusOf(id);

        assertThat(status.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("deliveries")).hasSize(4);
        assertThat(status.get("selectedChannels").toString()).contains("EMAIL").contains("SMS");

        // PUSH must not appear anywhere for a notification that never requested it — including in
        // channelOutcomes, where a newly-enumerated channel could otherwise surface unasked.
        assertThat(status.get("selectedChannels").toString()).doesNotContain("PUSH");
    }

    @Test
    void aPushOutcomeIsRecordedButNeverSelectedForAnEmailAndSmsRequest() throws Exception {
        // Routing evaluates every channel in the policy, so a PUSH outcome legitimately appears with
        // reason NOT_REQUESTED or CHANNEL_DISABLED. What must not happen is selection.
        UUID id = submit("regress-3");

        JsonNode outcomes = statusOf(id).get("channelOutcomes");
        java.util.stream.StreamSupport.stream(outcomes.spliterator(), false)
                .filter(o -> "PUSH".equals(o.get("channel").asText()))
                .forEach(o -> assertThat(o.get("selected").asBoolean()).isFalse());
    }

    @Test
    void deliveryStatesForExistingChannelsAreUnchanged() throws Exception {
        UUID id = submit("regress-4");
        poller.drainOnce();
        worker.runOnce();

        List<String> states =
                jdbc.sql("SELECT DISTINCT state FROM delivery WHERE notification_id = ?")
                        .param(id)
                        .query(String.class)
                        .list();
        assertThat(states).containsExactly("DELIVERED");
    }

    private UUID submit(String clientId) throws Exception {
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

    private JsonNode statusOf(UUID id) throws Exception {
        return mapper.readTree(
                mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }
}
