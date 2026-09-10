package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.Severity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T019 — unbounded starvation of low severity, asserted as <b>intended</b> (FR-207, SC-207, D-15).
 *
 * <p>Asserting that a delivery is never made looks like asserting a bug, and that is exactly why this
 * test exists. The owner answered U-1 with option (a): severity means what it says, and a {@code LOW}
 * notification that never arrives during an incident is the accepted trade. FR-207 goes further and
 * forbids adding an age-based override to cap it.
 *
 * <p>Without this test, a future engineer meeting a starved {@code LOW} backlog would reasonably read it
 * as a defect and add a starvation timer — silently reversing an owner decision, in a change that would
 * look like a bug fix and pass every other test in the suite. This is what makes that fail loudly.
 *
 * <p>The second test is the other half of the requirement and is not decoration: since starvation is
 * uncapped, the flag is the <b>only</b> mitigation (FR-207a). A rollback switch that did not actually
 * release the backlog would leave the accepted hazard with no remedy at all.
 */
class SeverityStarvationTest {

    @TestPropertySource(properties = "notification.features.severity-claim-order=true")
    static class Enabled extends SeverityClaimOrderSupport {

        @Test
        void aLowDeliveryIsNeverClaimedWhileHigherSeverityWorkKeepsArriving() throws Exception {
            scripts.of(Channel.EMAIL).alwaysSucceed();
            UUID low = submit("starve-low", Severity.LOW);

            // A sustained stream: each round adds new CRITICAL work before the worker runs, which is the
            // real-world shape — an incident generating alerts faster than they drain.
            for (int round = 0; round < 6; round++) {
                submit("starve-critical-" + round, Severity.CRITICAL);
                assertThat(claimNotifications(1))
                        .as("round %d must claim CRITICAL work, never the waiting LOW delivery", round)
                        .doesNotContain(low);
                releaseAllLeases();
                markDelivered("starve-critical-" + round);
            }

            // The point of the test, stated plainly: the LOW delivery is still QUEUED and still eligible.
            // It is not expired, not failed, not blocked. It is simply outranked, forever.
            assertThat(stateOf(emailDeliveryOf(low)))
                    .as("starvation is accepted behaviour under D-15, not a defect")
                    .isEqualTo("QUEUED");
        }

        private void markDelivered(String clientId) {
            jdbc.sql(
                            "UPDATE delivery SET state = 'DELIVERED', claimed_until = NULL WHERE notification_id = "
                                    + "(SELECT id FROM notification WHERE client_notification_id = ?)")
                    .param(clientId)
                    .update();
        }

        private String stateOf(UUID deliveryId) {
            return jdbc.sql("SELECT state FROM delivery WHERE id = ?")
                    .param(deliveryId)
                    .query(String.class)
                    .single();
        }
    }

    @TestPropertySource(properties = "notification.features.severity-claim-order=false")
    static class Disabled extends SeverityClaimOrderSupport {

        @Test
        void turningTheFlagOffReleasesTheStarvedBacklog() throws Exception {
            scripts.of(Channel.EMAIL).alwaysSucceed();
            UUID low = submit("starve-off-low", Severity.LOW);
            submit("starve-off-critical", Severity.CRITICAL);

            // With ordering off, the older LOW delivery is claimed first — age only. That is both the
            // phase-2 baseline and the documented remedy for a starved backlog (FR-207a).
            assertThat(claimNotifications(1))
                    .as("flag off is the only mitigation for D-15's accepted starvation")
                    .containsExactly(low);
        }
    }
}
