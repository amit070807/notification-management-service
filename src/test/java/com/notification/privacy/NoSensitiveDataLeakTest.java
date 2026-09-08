package com.notification.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.notification.domain.model.Channel;
import com.notification.domain.retry.FailureClassification;
import com.notification.integration.RetryTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T100 — the Principle V gate. MERGE BLOCKER.
 *
 * <p>The constitution requires a test asserting that a full end-to-end run including a failure and
 * a retry produces audit records and log output containing no forbidden value.
 *
 * <p>The approach is a planted marker rather than pattern-matching for emails or phone numbers.
 * A pattern can have gaps and produce a false negative; a marker cannot — if the content reached
 * any scanned surface, the marker is there.
 */
class NoSensitiveDataLeakTest extends RetryTestSupport {

    private static final String CONTENT_MARKER = "ZZ-CONTENT-MARKER-9f2a-DO-NOT-LEAK";
    private static final String RECIPIENT_MARKER = "ZZ-RECIPIENT-MARKER-7c1b";

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

    @Test
    void aFullLifecycleWithFailureAndRetryLeaksNothing() throws Exception {
        // A provider that echoes the submitted content in its error text — the exact case a real
        // provider produces and the one most likely to smuggle content into audit.
        scripts.of(Channel.EMAIL)
                .thenFailEchoingContent("provider said: " + CONTENT_MARKER)
                .thenFail(FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                .thenSucceed();

        UUID id = submitWithMarkers("leak-1");
        poller.drainOnce();
        runUntilSettled(5);

        SensitiveDataScanner.create()
                .forbidding(CONTENT_MARKER)
                .forbidding(RECIPIENT_MARKER)
                .scanning(allAuditRows())
                .scanning(capturedLogs())
                .scanning(metricLabels())
                .assertNothingLeaked();
    }

    @Test
    void theContentPayloadNeverEntersAnAuditRow() throws Exception {
        UUID id = submitWithMarkers("leak-2");
        poller.drainOnce();
        worker.runOnce();

        Integer hits =
                jdbc.sql("SELECT count(*) FROM audit_event WHERE payload::text LIKE ?")
                        .param("%" + CONTENT_MARKER + "%")
                        .query(Integer.class)
                        .single();
        assertThat(hits).as("FR-056: content must not appear in audit").isZero();
    }

    @Test
    void theRecipientReferenceIsMaskedInAuditButNotInStatus() throws Exception {
        UUID id = submitWithMarkers("leak-3");
        poller.drainOnce();
        worker.runOnce();

        // Masked where the reader is not the submitter...
        assertThat(allAuditRows()).doesNotContain(RECIPIENT_MARKER);

        // ...but echoed back to the system that supplied it (FR-052). Masking here would protect
        // nothing and would make the response useless to its only audience.
        assertThat(statusOf(id).toString()).contains(RECIPIENT_MARKER);
    }

    @Test
    void aProviderDiagnosticIsBoundedAndCarriesNoContent() throws Exception {
        scripts.of(Channel.EMAIL).thenFailEchoingContent("provider said: " + CONTENT_MARKER).thenSucceed();

        UUID id = submitWithMarkers("leak-4");
        poller.drainOnce();
        runUntilSettled(3);

        var diagnostics =
                jdbc.sql(
                                "SELECT coalesce(a.diagnostic, '') FROM delivery_attempt a "
                                        + "JOIN delivery d ON a.delivery_id = d.id WHERE d.notification_id = ?")
                        .param(id)
                        .query(String.class)
                        .list();

        assertThat(diagnostics).allSatisfy(d -> assertThat(d.length()).isLessThanOrEqualTo(200));
    }

    @Test
    void aRejectionDoesNotLeakTheRejectedValues() throws Exception {
        // An error body and its audit record are leak vectors like any other.
        String json =
                validSubmission("leak-5")
                        .replace("payload-leak-5", CONTENT_MARKER)
                        .replace("[\"user-1\", \"user-2\"]", "[]");

        String response =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isBadRequest())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response).doesNotContain(CONTENT_MARKER);
        assertThat(allAuditRows()).doesNotContain(CONTENT_MARKER);
    }

    private UUID submitWithMarkers(String clientId) throws Exception {
        String json =
                validSubmission(clientId)
                        .replace("[\"EMAIL\", \"SMS\"]", "[\"EMAIL\"]")
                        .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"" + RECIPIENT_MARKER + "\"]")
                        .replace("payload-" + clientId, CONTENT_MARKER);

        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
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
        // Metric labels are an unbounded-cardinality leak vector, and Principle V forbids
        // sensitive values there as firmly as in audit.
        return meters.getMeters().stream()
                .map(m -> m.getId().getName() + m.getId().getTags())
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
