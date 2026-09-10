package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * T059 — with deduplication off, the phase-1 behaviour is unchanged (FR-105).
 *
 * <p>The flag is set explicitly rather than relying on the default. Reading the default here would
 * make this test pass for the wrong reason the day someone flips the shipped default: it would then
 * be asserting the new behaviour under a name that promises the old.
 *
 * <p>Scoped to what {@link DuplicateSubmissionTest} cannot cover: that flag-off leaves no trace of
 * the new machinery, and that 200 — the suppression signal — is never the status. The identity and
 * delivery-independence assertions live there and are not repeated here.
 */
@TestPropertySource(properties = "notification.features.dedup-submission=false")
class DedupDisabledTest extends SubmissionTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void theSuppressionStatusCodeIsNeverReturned() throws Exception {
        // 200 is the entire signal (ADR-021), so this single assertion is what a caller relies on to
        // know the flag is off. Asserting 202 explicitly, not merely "not an error": a 2xx family
        // check would pass on the very status this test exists to forbid.
        submitExpectingAccepted("off-1");
        submitExpectingAccepted("off-1");

        assertThat(notificationsFor("corr-off-1")).isEqualTo(2);
    }

    @Test
    void nothingIsSuppressedAndNothingIsRecorded() throws Exception {
        submitExpectingAccepted("off-2");
        submitExpectingAccepted("off-2");

        assertThat(suppressionsFor("corr-off-2")).isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_event WHERE correlation_id = ? "
                                                + "AND event_type = 'NOTIFICATION_SUPPRESSED'")
                                .param("corr-off-2")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    private void submitExpectingAccepted(String clientId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validSubmission(clientId)))
                .andExpect(status().isAccepted());
    }

    private int notificationsFor(String correlationId) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE correlation_id = ?")
                .param(correlationId)
                .query(Integer.class)
                .single();
    }

    private int suppressionsFor(String correlationId) {
        return jdbc.sql("SELECT count(*) FROM notification_suppression WHERE correlation_id = ?")
                .param(correlationId)
                .query(Integer.class)
                .single();
    }
}
