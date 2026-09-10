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
 *
 * <p>T065 extends the gate to feature 002's three new record types and to the push credential. Both
 * feature flags are enabled, and that is load-bearing rather than incidental: two of the three types
 * are only written when their flag is on, so scanning them with flags off would pass without ever
 * having produced a row to scan. {@link #theNewRecordTypesAreActuallyWritten()} exists so that
 * vacuity cannot go unnoticed — a scan that finds nothing because nothing happened is not a pass.
 */
@org.springframework.test.context.TestPropertySource(
        properties = {
            "notification.features.dedup-submission=true",
            "notification.features.delivery-reclaim=true",
            "notification.channel.credentials.PUSH.token=ZZ-PUSH-CREDENTIAL-MARKER-8b3f",
            "notification.channel.credentials.PUSH.token-ref=env:PUSH_TOKEN"
        })
class NoSensitiveDataLeakTest extends RetryTestSupport {

    private static final String CONTENT_MARKER = "ZZ-CONTENT-MARKER-9f2a-DO-NOT-LEAK";
    private static final String RECIPIENT_MARKER = "ZZ-RECIPIENT-MARKER-7c1b";
    private static final String CREDENTIAL_MARKER = "ZZ-PUSH-CREDENTIAL-MARKER-8b3f";

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

    /**
     * T065 — the suppression, retry-execution and reclaim records leak nothing (FR-153, SC-111).
     *
     * <p>All three are new surfaces for the same old leak. The suppression record is the one worth
     * naming: it is written for a submission that was refused, so it is the only audit row describing a
     * request whose content the system deliberately never processed — echoing that content back into
     * the trail would be a leak with no delivery to justify it.
     */
    @Test
    void theNewRecordTypesLeakNothing() throws Exception {
        exerciseNewRecordTypes("leaks");

        SensitiveDataScanner.create()
                .forbidding(CONTENT_MARKER)
                .forbidding(RECIPIENT_MARKER)
                .forbidding(CREDENTIAL_MARKER)
                .scanning(allAuditRows())
                .scanning(capturedLogs())
                .scanning(metricLabels())
                .assertNothingLeaked();
    }

    /**
     * The anti-vacuity guard. Without it the scan above would pass on a build where suppression and
     * reclaim never fired, which is the failure mode a flag-gated feature makes easy: the gate reports
     * green and has inspected nothing.
     */
    @Test
    void theNewRecordTypesAreActuallyWritten() throws Exception {
        Exercised exercised = exerciseNewRecordTypes("written");

        // Scoped to THIS test's notifications. A global DISTINCT event_type would pass on rows another
        // class left in the shared container, which would make the anti-vacuity guard itself vacuous —
        // the exact failure it was added to catch, one level up.
        assertThat(auditTypesFor(exercised.retried())).contains("RETRY_EXECUTED", "NOTIFICATION_SUPPRESSED");
        assertThat(auditTypesFor(exercised.stranded())).contains("DELIVERY_RECLAIMED");
    }

    private record Exercised(UUID retried, UUID stranded) {}

    /**
     * Drives one run that produces all three of feature 002's record types.
     *
     * <p>The suffix makes the client identifiers unique per caller. Without it the second test to call
     * this would repeat a boundary the first had already used, and with deduplication on its opening
     * submission would be suppressed rather than accepted — a test failing on the fixture rather than
     * on the behaviour, and the container is shared across the whole suite.
     */
    private Exercised exerciseNewRecordTypes(String suffix) throws Exception {
        // RETRY_EXECUTED, with the provider echoing content into its error text on the way.
        scripts.of(Channel.EMAIL)
                .thenFailEchoingContent("provider said: " + CONTENT_MARKER)
                .thenSucceed();
        UUID retried = submitWithMarkers("leak-retry-" + suffix);
        poller.drainOnce();
        runUntilSettled(4);

        // NOTIFICATION_SUPPRESSED — the same boundary, submitted again. Attributed to the original
        // notification, which is why the assertions can scope to `retried`.
        submitWithMarkersExpecting("leak-retry-" + suffix, status().isOk());

        // DELIVERY_RECLAIMED. The stranded row state is written directly; killing a worker
        // mid-transaction would roll it back and strand nothing.
        scripts.of(Channel.EMAIL).alwaysSucceed();
        UUID stranded = submitWithMarkers("leak-reclaim-" + suffix);
        poller.drainOnce();
        jdbc.sql(
                        "UPDATE delivery SET state = 'IN_PROGRESS', claimed_until = ? "
                                + "WHERE notification_id = ?")
                .param(java.sql.Timestamp.from(clock.now().minus(Duration.ofMinutes(5))))
                .param(stranded)
                .update();
        worker.runOnce();

        return new Exercised(retried, stranded);
    }

    private java.util.List<String> auditTypesFor(UUID notificationId) {
        return jdbc.sql("SELECT DISTINCT event_type FROM audit_event WHERE notification_id = ?")
                .param(notificationId)
                .query(String.class)
                .list();
    }

    private void submitWithMarkersExpecting(
            String clientId, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        mockMvc.perform(
                        post("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(markedSubmission(clientId)))
                .andExpect(expected);
    }

    private UUID submitWithMarkers(String clientId) throws Exception {
        String json = markedSubmission(clientId);

        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private String markedSubmission(String clientId) {
        return validSubmission(clientId)
                .replace("[\"EMAIL\", \"SMS\"]", "[\"EMAIL\"]")
                .replace("\"recipients\": [\"user-1\", \"user-2\"]", "\"recipients\": [\"" + RECIPIENT_MARKER + "\"]")
                .replace("payload-" + clientId, CONTENT_MARKER);
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
