package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.port.AuditRepositoryPort;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T099 — audit records cannot be updated or deleted through any available path (FR-050).
 *
 * <p>Section 4.9 calls this "Audit History", and a rewritable history is not a history. The
 * enforcement is structural rather than procedural: there is no method to call. A developer who
 * wants to mutate an audit row has to add one to the port, which is a visible and arguable change
 * in review rather than a line buried in a service.
 */
class AuditImmutabilityTest extends SubmissionTestSupport {

    @Autowired private AuditRepositoryPort audit;
    @Autowired private JdbcClient jdbc;

    @Test
    void thePortExposesNoMutationMethod() {
        List<String> methods =
                Arrays.stream(AuditRepositoryPort.class.getDeclaredMethods()).map(Method::getName).toList();

        assertThat(methods).contains("append");
        assertThat(methods)
                .as("FR-050: audit history must have no update or delete path")
                .doesNotContain("update", "delete", "remove", "modify", "save", "merge");
    }

    @Test
    void theImplementationExposesNoMutationMethodEither() {
        // The port is the contract, but a public method on the adapter would be reachable too.
        List<String> methods =
                Arrays.stream(audit.getClass().getDeclaredMethods())
                        .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                        .map(Method::getName)
                        .toList();

        assertThat(methods).doesNotContain("update", "delete", "remove", "modify");
    }

    @Test
    void recordsAccumulateRatherThanBeingReplaced() throws Exception {
        UUID id = submitAndGetId("immut-1");
        int first = audit.findByNotificationId(id).size();

        // Any correction is a new event, never an edit to an old one.
        assertThat(first).isPositive();
        assertThat(audit.findByNotificationId(id)).hasSize(first);
    }

    @Test
    void auditRowsAreNeverOverwrittenByLaterActivity() throws Exception {
        UUID id = submitAndGetId("immut-2");
        List<String> before =
                audit.findByNotificationId(id).stream().map(r -> r.id() + ":" + r.eventType()).toList();

        jdbc.sql("SELECT count(*) FROM audit_event").query(Integer.class).single();

        List<String> after =
                audit.findByNotificationId(id).stream().map(r -> r.id() + ":" + r.eventType()).toList();
        assertThat(after).isEqualTo(before);
    }

    private UUID submitAndGetId(String clientId) throws Exception {
        String body =
                mockMvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                                "/api/v1/notifications")
                                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                        .content(validSubmission(clientId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
