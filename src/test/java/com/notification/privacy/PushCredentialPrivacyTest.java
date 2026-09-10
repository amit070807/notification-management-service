package com.notification.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.notification.integration.SubmissionTestSupport;
import com.notification.worker.DeliveryWorker;
import com.notification.worker.OutboxPoller;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * T019 — the push credential is the new sensitive value (FR-116, SC-111).
 *
 * <p>Same marker technique as the phase-1 privacy gate: the credential is set to a string that
 * appears nowhere else, so any surface carrying it is caught regardless of how it got there. Pattern
 * matching for token-shaped strings could miss a format nobody anticipated; a marker cannot.
 *
 * <p><b>What this test does and does not cover.</b> Integration tests run against
 * {@code TestChannelConfig}'s scripted providers, so the real {@link
 * com.notification.channel.SimulatedPushProvider} is not on this path. Provider-level leakage — the
 * diagnostic, the redacting {@code toString} — is asserted in {@code SimulatedPushProviderTest}
 * instead. What this covers is the surfaces the <i>pipeline</i> owns: audit rows, log output, metric
 * labels and status responses, where a credential could arrive through configuration binding,
 * property logging or an actuator exposure rather than through the adapter at all.
 *
 * <p>{@link #theCredentialIsActuallyConfiguredSoThisTestIsNotVacuous()} exists because a privacy test
 * that had nothing to find would pass silently and look like evidence.
 */
@TestPropertySource(properties = {
    "notification.routing.policy-location=classpath:routing-policy-push-enabled.yaml",
    "notification.channel.credentials.PUSH.token=ZZ-PUSH-CREDENTIAL-MARKER-8b3f",
    "notification.channel.credentials.PUSH.token-ref=env:PUSH_TOKEN"
})
class PushCredentialPrivacyTest extends SubmissionTestSupport {

    private static final String CREDENTIAL_MARKER = "ZZ-PUSH-CREDENTIAL-MARKER-8b3f";

    @Autowired private OutboxPoller poller;
    @Autowired private DeliveryWorker worker;
    @Autowired private JdbcClient jdbc;
    @Autowired private MeterRegistry meters;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void captureLogs() {
        logCapture = new ListAppender<>();
        logCapture.start();
        ((Logger) LoggerFactory.getLogger("com.notification")).addAppender(logCapture);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger("com.notification")).detachAppender(logCapture);
    }

    @Autowired private com.notification.config.ChannelProperties channelProperties;

    @Test
    void theCredentialIsActuallyConfiguredSoThisTestIsNotVacuous() {
        // Without this, every assertion below would pass on a build where the credential was never
        // bound — the most comfortable kind of false green.
        var credential = channelProperties.credentialFor("PUSH");
        assertThat(credential.isPresent()).as("the marker credential must be bound").isTrue();
        assertThat(credential.token()).isEqualTo(CREDENTIAL_MARKER);
    }

    @Test
    void aSuccessfulPushDeliveryLeaksNoCredential() throws Exception {
        UUID id = submitPush("cred-1");
        poller.drainOnce();
        worker.runOnce();

        SensitiveDataScanner.create()
                .forbidding(CREDENTIAL_MARKER)
                .scanning(allAuditRows())
                .scanning(capturedLogs())
                .scanning(metricLabels())
                .scanning(statusBody(id))
                .assertNothingLeaked();
    }

    @Test
    void thePushAttemptDiagnosticCarriesNoCredential() throws Exception {
        UUID id = submitPush("cred-2");
        poller.drainOnce();
        worker.runOnce();

        var diagnostics =
                jdbc.sql(
                                "SELECT coalesce(a.diagnostic, '') FROM delivery_attempt a "
                                        + "JOIN delivery d ON a.delivery_id = d.id WHERE d.notification_id = ?")
                        .param(id)
                        .query(String.class)
                        .list();

        assertThat(diagnostics).allSatisfy(d -> assertThat(d).doesNotContain(CREDENTIAL_MARKER));
    }

    @Test
    void theCredentialIsAbsentFromEveryStatusResponse() throws Exception {
        // The one surface a caller reads directly. A credential here would be visible to whoever
        // submitted, which is a different audience from audit but no less wrong.
        UUID id = submitPush("cred-3");
        assertThat(statusBody(id)).doesNotContain(CREDENTIAL_MARKER);
    }

    private UUID submitPush(String clientId) throws Exception {
        String json =
                validSubmission(clientId)
                        .replace("[\"EMAIL\", \"SMS\"]", "[\"PUSH\"]")
                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"device-1\"]");
        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private String statusBody(UUID id) throws Exception {
        return mockMvc.perform(get("/api/v1/notifications/{id}/status", id))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String allAuditRows() {
        return String.join("\n", jdbc.sql("SELECT payload::text FROM audit_event").query(String.class).list());
    }

    private String capturedLogs() {
        return logCapture.list.stream()
                .map(e -> e.getFormattedMessage() + " " + e.getMDCPropertyMap())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private String metricLabels() {
        return meters.getMeters().stream()
                .map(m -> m.getId().getName() + m.getId().getTags())
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
