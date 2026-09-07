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
 * <p>This is deliberately NOT idempotency, which constitution v2.0.0 register item 9 defers. If
 * the second submission ever returns the first one's identity or state, that is a defect, not an
 * optimisation.
 */
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
        // The failure mode this guards: an "optimisation" that returns the stored state instead of
        // creating a second notification would silently implement the deferred deduplication.
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
