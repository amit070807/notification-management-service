package com.notification.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notification.domain.model.Severity;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared fixture for the feature-003 claim-ordering tests.
 *
 * <p>Every submission requests EMAIL only, with one recipient, so the deliveries a notification produces
 * stay few and its claim position is unambiguous. With two recipients and two channels the resulting
 * deliveries tie on severity and on {@code state_changed_at}, and the order among them would be whatever
 * the query plan produced — a test that passes or fails on nothing.
 *
 * <p><b>One notification is not always one delivery.</b> The routing policy escalates SMS at
 * {@code CRITICAL} (B-02), so a CRITICAL submission produces an EMAIL delivery <i>and</i> an SMS one even
 * though only EMAIL was requested. That is correct baseline behaviour, not a fixture problem, so
 * assertions are phrased over the <b>notification</b> a claimed delivery belongs to rather than over a
 * single delivery id. Naming one delivery would have made these tests depend on which channel the claim
 * happened to return first, which severity ordering says nothing about.
 *
 * <p>Severity is set per submission and the clock is advanced between them, so age and severity can be
 * made to <b>disagree</b>. That disagreement is the entire feature: a test where the high-severity
 * delivery is also the oldest proves nothing, because the phase-2 age ordering already puts it first.
 */
public abstract class SeverityClaimOrderSupport extends RetryTestSupport {

    @Autowired protected JdbcClient jdbc;

    /** Submits one delivery at the given severity and advances the clock so ages are distinguishable. */
    protected UUID submit(String clientId, Severity severity) throws Exception {
        String json =
                validSubmission(clientId)
                        .replace("[\"EMAIL\", \"SMS\"]", "[\"EMAIL\"]")
                        // One recipient, and a DISTINCT one per submission. Distinctness is what makes
                        // the provider's call order attributable to a specific delivery: with every
                        // submission using "user-1" the recorded calls are indistinguishable and an
                        // assertion about processing order could not be written at all.
                        .replace(
                                "\"recipients\": [\"user-1\", \"user-2\"]",
                                "\"recipients\": [\"" + clientId + "\"]")
                        .replace("\"severity\": \"HIGH\"", "\"severity\": \"" + severity.name() + "\"");

        String body =
                mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        UUID id = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        poller.drainOnce();

        // Ordering by state_changed_at needs distinguishable timestamps. Submissions inside one test
        // otherwise share a clock reading and tie, making "oldest first" untestable.
        clock.advance(Duration.ofSeconds(30));
        return id;
    }

    /** @return every delivery a notification produced — more than one when escalation applied */
    protected java.util.Set<UUID> deliveriesOf(UUID notificationId) {
        return java.util.Set.copyOf(
                jdbc.sql("SELECT id FROM delivery WHERE notification_id = ?")
                        .param(notificationId)
                        .query(UUID.class)
                        .list());
    }

    /** @return the EMAIL delivery, the one every submission produces regardless of escalation */
    protected UUID emailDeliveryOf(UUID notificationId) {
        return jdbc.sql("SELECT id FROM delivery WHERE notification_id = ? AND channel = 'EMAIL'")
                .param(notificationId)
                .query(UUID.class)
                .single();
    }

    /** Severity of the notification behind a delivery, read back to describe failures usefully. */
    protected String severityOfDelivery(UUID deliveryId) {
        return jdbc.sql(
                        "SELECT n.severity FROM delivery d JOIN notification n ON n.id = d.notification_id "
                                + "WHERE d.id = ?")
                .param(deliveryId)
                .query(String.class)
                .single();
    }

    /** Claims one batch through the production repository and returns the delivery ids, in order. */
    protected List<UUID> claim(int batchSize) {
        return deliveries
                .claimDue(clock.now(), clock.now().plus(Duration.ofMinutes(1)), batchSize)
                .stream()
                .map(d -> d.id())
                .toList();
    }

    /**
     * Claims one batch and returns the <b>notification</b> each claimed delivery belongs to, in claim
     * order.
     *
     * <p>This is the right granularity for a severity assertion: severity is a property of the
     * notification, and which of its deliveries the claim returns first is not something FR-201 governs.
     */
    protected List<UUID> claimNotifications(int batchSize) {
        return claim(batchSize).stream().map(this::notificationOf).toList();
    }

    protected UUID notificationOf(UUID deliveryId) {
        return jdbc.sql("SELECT notification_id FROM delivery WHERE id = ?")
                .param(deliveryId)
                .query(UUID.class)
                .single();
    }

    @Autowired protected com.notification.domain.port.DeliveryRepositoryPort deliveries;

    /** Releases every lease this test took, so a later claim in the same test sees the rows again. */
    protected void releaseAllLeases() {
        jdbc.sql("UPDATE delivery SET claimed_until = NULL WHERE state IN ('QUEUED', 'RETRY_SCHEDULED')")
                .update();
    }
}
