package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.fixtures.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * T041 — submission-level deduplication, flag on (US3, FR-140 to FR-144).
 *
 * <p>The first assertion in {@link #aDuplicateSubmissionReturns200NotAccepted()} is the status code
 * <b>alone</b>. That is deliberate and is the whole point of ADR-021: a consumer that ignores
 * response fields it does not recognise sees only the code, so if 202 were returned with a
 * {@code suppressed: true} field the caller would believe its notification was accepted and never
 * find out otherwise. Asserting the body first would let that regression pass.
 */
@TestPropertySource(properties = "notification.features.dedup-submission=true")
class DuplicateSuppressionTest extends SubmissionTestSupport {

    private static final Instant START = Instant.parse("2026-09-07T12:00:00Z");

    @Autowired private JdbcClient jdbc;
    @Autowired private MutableClock clock;

    /**
     * The clock is a context-wide singleton and the window tests move it. Resetting here rather than
     * trusting execution order is the same reason the base class resets the scripted providers.
     */
    @BeforeEach
    void resetClock() {
        clock.setTo(START);
    }

    @Test
    void aDuplicateSubmissionReturns200NotAccepted() throws Exception {
        submitAccepted("dedup-status");

        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission("dedup-status")))
                .andExpect(status().isOk());
    }

    @Test
    void theSuppressionResponseNamesTheOriginalNotification() throws Exception {
        UUID original = submitAccepted("dedup-body");

        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission("dedup-body")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).contains("\"suppressed\":true").contains(original.toString());
    }

    @Test
    void aSuppressedSubmissionCreatesNoNotificationAndNoDelivery() throws Exception {
        UUID original = submitAccepted("dedup-no-work");
        int deliveriesBefore = deliveryCount(original);

        submitSuppressed("dedup-no-work");

        assertThat(notificationsFor("corr-dedup-no-work")).isEqualTo(1);
        assertThat(deliveryCount(original)).isEqualTo(deliveriesBefore);
    }

    @Test
    void suppressionIsAuditedAgainstTheOriginalNotification() throws Exception {
        UUID original = submitAccepted("dedup-audit");
        submitSuppressed("dedup-audit");

        // FR-143. Attributed to the ORIGINAL because the suppressed submission never became a
        // notification, so there is no other identity to hang it on.
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_event WHERE notification_id = ? "
                                                + "AND event_type = 'NOTIFICATION_SUPPRESSED'")
                                .param(original)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void suppressionIsRecordedInItsOwnTable() throws Exception {
        UUID original = submitAccepted("dedup-record");
        submitSuppressed("dedup-record");

        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM notification_suppression "
                                                + "WHERE source_system = 'billing' AND correlation_id = ? "
                                                + "AND original_notification_id = ?")
                                .param("corr-dedup-record")
                                .param(original)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void aDifferentCorrelationIdIsNotADuplicate() throws Exception {
        submitAccepted("dedup-other-a");
        // Different clientId means a different correlationId in the fixture, so a different boundary.
        submitAccepted("dedup-other-b");
    }

    @Test
    void aRepeatAfterTheWindowIsAcceptedAgain() throws Exception {
        submitAccepted("dedup-window");

        // FR-141b. Just past 24 hours: the window is inclusive at its edge, so this must be the
        // first instant outside it rather than exactly at it.
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        submitAccepted("dedup-window");
        assertThat(notificationsFor("corr-dedup-window")).isEqualTo(2);
    }

    @Test
    void aTerminallyFailedOriginalDoesNotSuppressARetrySubmission() throws Exception {
        UUID original = submitAccepted("dedup-failed");
        jdbc.sql("UPDATE notification SET state = 'FAILED' WHERE id = ?").param(original).update();

        // FR-141c. Suppressing here would leave the caller with no way to get the notification sent
        // at all: its own submission is refused and the thing it collided with never arrived.
        submitAccepted("dedup-failed");
    }

    private UUID submitAccepted(String clientId) throws Exception {
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

    private void submitSuppressed(String clientId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission(clientId)))
                .andExpect(status().isOk());
    }

    private int notificationsFor(String correlationId) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE correlation_id = ?")
                .param(correlationId)
                .query(Integer.class)
                .single();
    }

    private int deliveryCount(UUID notificationId) {
        return jdbc.sql("SELECT count(*) FROM delivery WHERE notification_id = ?")
                .param(notificationId)
                .query(Integer.class)
                .single();
    }
}
