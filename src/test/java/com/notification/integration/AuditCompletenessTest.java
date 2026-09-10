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
 * T097, extended by T062/T064 — one run produces every significant action (source 4.9, FR-046,
 * FR-053, FR-152, SC-008).
 *
 * <p>Eight action types are named by 4.9; DELIVERY_EXPIRED and RETRY_BUDGET_EXHAUSTED are added
 * under FR-053, which 4.9 permits by introducing its list with "such as". Feature 002 adds three
 * more — NOTIFICATION_SUPPRESSED, RETRY_EXECUTED, DELIVERY_RECLAIMED — for thirteen. All thirteen
 * correspond to real terminal or transitional states the machine produces, so an unreachable one
 * would mean the audit vocabulary had drifted from actual behaviour.
 *
 * <p>Both feature flags are enabled here, and that is a finding rather than a convenience. Two of the
 * three new types describe behaviour that only exists when its flag is on, so with flags off the
 * declared vocabulary is <b>not</b> fully reachable. The claim worth making is the narrower true one:
 * every declared type is reachable when the behaviour it describes is switched on. Leaving the flags
 * off and trimming the expected set would have let a genuinely dead event type slip in unnoticed.
 */
@org.springframework.test.context.TestPropertySource(
        properties = {
            "notification.features.dedup-submission=true",
            "notification.features.delivery-reclaim=true"
        })
class AuditCompletenessTest extends RetryTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void aFullExerciseProducesEveryDeclaredEventType() throws Exception {
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

        // 4. A suppressed duplicate (FR-143). Same client identifier, so the fixture derives the same
        //    (sourceSystem, correlationId) boundary.
        scripts.of(Channel.EMAIL).alwaysSucceed();
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission("audit-delivered").replace("[\"EMAIL\", \"SMS\"]", "[\"EMAIL\"]")
                                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"user-1\"]")))
                .andExpect(status().isOk());

        // 5. A delivery stranded mid-attempt and reclaimed (FR-159). The row state is written directly:
        //    killing a worker mid-transaction would roll it back and leave nothing stranded.
        UUID stranded = submitEmailOnly("audit-reclaimed");
        poller.drainOnce();
        UUID strandedDelivery =
                jdbc.sql("SELECT id FROM delivery WHERE notification_id = ?")
                        .param(stranded)
                        .query(UUID.class)
                        .single();
        jdbc.sql("UPDATE delivery SET state = 'IN_PROGRESS', claimed_until = ? WHERE id = ?")
                .param(java.sql.Timestamp.from(clock.now().minus(Duration.ofMinutes(5))))
                .param(strandedDelivery)
                .update();
        worker.runOnce();

        // 6. An expiry, reached from backoff.
        scripts.of(Channel.EMAIL).alwaysFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        UUID expired = submitEmailOnlyExpiringIn("audit-expired", Duration.ofMinutes(30));
        poller.drainOnce();
        worker.runOnce();
        clock.advance(Duration.ofHours(1));
        worker.runOnce();

        // Scoped to this test's own correlation identifiers. A global DISTINCT would pass on rows other
        // classes left in the shared container — and since two of the thirteen types are produced only
        // by the flag-on dedup and reclaim tests, a completeness gate reading globally could report
        // green while this exercise reached neither. The prefix covers the rejection too, which has no
        // notification to be keyed by.
        List<String> recorded =
                jdbc.sql("SELECT DISTINCT event_type FROM audit_event WHERE correlation_id LIKE 'corr-audit-%'")
                        .query(String.class)
                        .list();

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

    /**
     * T064 — FR-151 needs no new work, and this asserts that rather than reimplementing it.
     *
     * <p>4.9 asks for the routing decision to be recorded. Phase 1 already writes it, per recipient and
     * channel, with the reason a channel was not selected. Adding an audit event for it would create a
     * second, weaker record of the same fact — weaker because an event payload is flat strings, while
     * the routing tables carry the structure. The finding is that nothing is missing.
     */
    @Test
    void theRoutingDecisionIsAlreadyRecordedWithoutANewEventType() throws Exception {
        UUID id = submitEmailOnly("audit-routing");

        Integer selected =
                jdbc.sql(
                                """
                                SELECT count(*) FROM routing_channel_outcome o
                                JOIN routing_decision rd ON o.routing_decision_id = rd.id
                                WHERE rd.notification_id = ? AND o.selected = true
                                """)
                        .param(id)
                        .query(Integer.class)
                        .single();
        assertThat(selected).isPositive();

        // Every non-selected channel carries a reason code. A blank one would make the record unusable for
        // answering "why did this recipient not get an SMS", which is what 4.9 asks the trail to support.
        Integer unexplained =
                jdbc.sql(
                                """
                                SELECT count(*) FROM routing_channel_outcome o
                                JOIN routing_decision rd ON o.routing_decision_id = rd.id
                                WHERE rd.notification_id = ? AND o.selected = false
                                  AND coalesce(o.reason_code, '') = ''
                                """)
                        .param(id)
                        .query(Integer.class)
                        .single();
        assertThat(unexplained).isZero();
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
