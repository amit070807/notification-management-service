package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T035 — duplicate client identifiers are two independent notifications (FR-008b, spec D4).
 *
 * <p>US3 changes this, so the flag is pinned off here rather than inherited from the shipped default.
 * Kept rather than deleted (T058): this is the record of what the service did before deduplication
 * existed, and FR-105 makes it a live requirement, not history — with the flag off the behaviour must
 * still be exactly this. Deleting the class would have removed the only place that says so.
 *
 * <p>Note what did <b>not</b> change: a repeated {@code clientNotificationId} is still not the
 * deduplication boundary. That boundary is {@code (sourceSystem, correlationId)} (spec D9), and the
 * fixture happens to derive the correlation identifier from the client identifier, which is why the
 * flag matters to these cases at all. A client identifier remains descriptive, not identifying
 * (FR-008a).
 *
 * <p>With the flag <b>on</b>, {@link DuplicateSuppressionTest} asserts the reverse.
 */
@org.springframework.test.context.TestPropertySource(
        properties = "notification.features.dedup-submission=false")
class DuplicateSubmissionTest extends SubmissionTestSupport {

    @Autowired private JdbcClient jdbc;

    @Test
    void bothSubmissionsAreAcceptedWithDistinctIdentities() throws Exception {
        UUID first = submit("dup-1");
        UUID second = submit("dup-1");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void eachDuplicateGetsItsOwnDeliveriesAndAuditHistory() throws Exception {
        UUID first = submit("dup-2");
        UUID second = submit("dup-2");

        assertThat(deliveryCount(first)).isEqualTo(4);
        assertThat(deliveryCount(second)).isEqualTo(4);
        assertThat(auditCount(first)).isPositive();
        assertThat(auditCount(second)).isPositive();

        // Two rows share the client identifier — the schema permits it on purpose.
        assertThat(
                        jdbc.sql("SELECT count(*) FROM notification WHERE client_notification_id = 'dup-2'")
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void theSecondSubmissionIsNotSuppressed() throws Exception {
        // The failure mode this guards: deduplication leaking past its flag. Before US3 this stood
        // against an "optimisation" that returned the stored state; now it stands against the real
        // feature applying when it was switched off.
        int before = deliveriesForClientId("dup-3");
        submit("dup-3");
        submit("dup-3");
        assertThat(deliveriesForClientId("dup-3")).isEqualTo(before + 8);
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

    private int deliveryCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM delivery WHERE notification_id = ?").param(id).query(Integer.class).single();
    }

    private int auditCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE notification_id = ?").param(id).query(Integer.class).single();
    }

    private int deliveriesForClientId(String clientId) {
        return jdbc.sql(
                        "SELECT count(*) FROM delivery d JOIN notification n ON d.notification_id = n.id "
                                + "WHERE n.client_notification_id = ?")
                .param(clientId)
                .query(Integer.class)
                .single();
    }
}
