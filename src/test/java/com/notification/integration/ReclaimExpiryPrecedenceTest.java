package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T037 — expiry outranks reclaim (FR-159b, FR-034).
 *
 * <p>A stranded delivery whose notification has since expired must reach {@code EXPIRED}. Reclaiming
 * into a fresh attempt would deliver a notification the submitter had already declared stale, and the
 * eligibility check exists precisely because a delivery can cross that boundary while waiting.
 *
 * <p>The reclaim is still <b>recorded</b>, though the attempt is not made. That ordering is deliberate:
 * a delivery that was stuck and then expired is the case an operator most needs to see, and dropping
 * the record would leave nothing to distinguish it from an ordinary expiry.
 */
@TestPropertySource(properties = "notification.features.delivery-reclaim=true")
class ReclaimExpiryPrecedenceTest extends ReclaimTestSupport {

    @Test
    void anExpiredStrandedDeliveryExpiresRatherThanReattempting() throws Exception {
        UUID notificationId = submitEmailOnlyExpiringIn(Duration.ofMinutes(30), "reclaim-expiry");
        poller.drainOnce();
        UUID deliveryId = strandMidAttempt(notificationId);
        scripts.of(Channel.EMAIL).alwaysSucceed();

        // The stranding call itself. A stranded delivery has by definition already been sent once;
        // what must not happen is ANOTHER call after expiry, so the baseline is taken here rather
        // than assumed to be zero.
        int callsBeforeExpiry = scripts.of(Channel.EMAIL).callCount();

        clock.advance(Duration.ofHours(1));
        worker.runOnce();

        assertThat(stateOf(deliveryId)).isEqualTo("EXPIRED");
        assertThat(scripts.of(Channel.EMAIL).callCount())
                .as("expiry outranks reclaim: no further attempt may be made (FR-159b)")
                .isEqualTo(callsBeforeExpiry);
    }

    @Test
    void theReclaimIsStillRecordedBeforeTheExpiry() throws Exception {
        UUID notificationId = submitEmailOnlyExpiringIn(Duration.ofMinutes(30), "reclaim-expiry-2");
        poller.drainOnce();
        strandMidAttempt(notificationId);

        clock.advance(Duration.ofHours(1));
        worker.runOnce();

        assertThat(reclaimAuditCount(notificationId)).isEqualTo(1);
    }

    private UUID submitEmailOnlyExpiringIn(Duration ttl, String clientId) throws Exception {
        UUID id = submitEmailOnly(clientId);
        jdbc.sql("UPDATE notification SET expires_at = ? WHERE id = ?")
                .param(java.sql.Timestamp.from(clock.now().plus(ttl)))
                .param(id)
                .update();
        return id;
    }
}
