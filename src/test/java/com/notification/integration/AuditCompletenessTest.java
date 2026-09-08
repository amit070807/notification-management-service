package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.domain.model.AuditEventType;
import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T097 — one run produces every significant action (source 4.9, FR-046, FR-053, SC-008).
 *
 * <p>Eight action types are named by 4.9; DELIVERY_EXPIRED and RETRY_BUDGET_EXHAUSTED are added
 * under FR-053, which 4.9 permits by introducing its list with "such as". All ten correspond to
 * real terminal or transitional states the machine produces, so an unreachable one would mean the
 * audit vocabulary had drifted from actual behaviour.
 */
class AuditCompletenessTest extends RetryTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void aFullExerciseProducesAllTenEventTypes() throws Exception {
        // 1. A rejection — no notification row is created, so this record is keyed by correlation
        //    identifier alone (FR-006 vs FR-007).
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission("audit-rej").replace("[\"user-1\", \"user-2\"]", "[]")))
                .andExpect(status().isBadRequest());

        // 2. Accepted, routed, queued, attempted, failed, retried, then exhausted.
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID exhausted = submitEmailOnly("audit-exhausted");
        poller.drainOnce();
        runUntilSettled(10);

        // 3. A success.
        scripts.of(Channel.EMAIL).alwaysSucceed();
        UUID delivered = submitEmailOnly("audit-delivered");
        poller.drainOnce();
        worker.runOnce();

        // 4. An expiry, reached from backoff.
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID expired = submitEmailOnlyExpiringIn("audit-expired", Duration.ofMinutes(30));
        poller.drainOnce();
        worker.runOnce();
        clock.advance(Duration.ofHours(1));
        worker.runOnce();

        List<String> recorded =
                jdbc.sql("SELECT DISTINCT event_type FROM audit_event").query(String.class).list();

        Set<String> expected =
                java.util.Arrays.stream(AuditEventType.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());

        assertThat(recorded)
                .as("every declared audit event type must be reachable in a real run")
                .containsAll(expected);
    }

    @Test
    void aRejectionIsRecordedEvenThoughNoNotificationExists() throws Exception {
        String correlationId = "corr-audit-rej-2";
        String json =
                validSubmission("audit-rej-2")
                        .replace("\"corr-audit-rej-2\"", "\"" + correlationId + "\"")
                        .replace("\"correlationId\": \"corr-audit-rej-2\"", "\"correlationId\": \"" + correlationId + "\"")
                        .replace("[\"user-1\", \"user-2\"]", "[]");

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());

        Integer rejections =
                jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'NOTIFICATION_REJECTED'")
                        .query(Integer.class)
                        .single();
        assertThat(rejections).isPositive();

        // FR-007 still holds: the rejection created no notification.
        Integer orphans =
                jdbc.sql(
                                "SELECT count(*) FROM audit_event WHERE event_type = 'NOTIFICATION_REJECTED' "
                                        + "AND notification_id IS NOT NULL")
                        .query(Integer.class)
                        .single();
        assertThat(orphans).isZero();
    }

    @Test
    void onlyRejectionsMayOmitTheNotificationReference() {
        // The CHECK constraint added in V3. Without it, any future bug that failed to set
        // notification_id would silently produce an unattributable audit record.
        Integer unattributed =
                jdbc.sql(
                                "SELECT count(*) FROM audit_event WHERE notification_id IS NULL "
                                        + "AND event_type <> 'NOTIFICATION_REJECTED'")
                        .query(Integer.class)
                        .single();
        assertThat(unattributed).isZero();
    }

    private UUID submitEmailOnlyExpiringIn(String clientId, Duration ttl) throws Exception {
        String json =
                validSubmission(clientId)
                        .replace("[\"EMAIL\", \"SMS\"]", "[\"EMAIL\"]")
                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"user-1\"]")
                        .replace(
                                "\"createdAt\": \"2026-09-07T10:00:00Z\"",
                                "\"createdAt\": \"2026-09-07T10:00:00Z\",\"expiresAt\": \"" + clock.now().plus(ttl) + "\"");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
