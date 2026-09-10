package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Channel;
import com.notification.domain.model.Severity;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T018 — reclaimed deliveries are ordered by severity, not privileged (FR-207b, SC-206, decision D-16).
 *
 * <p>The owner's answer to U-2 was that recovery work is ordinary due work. The alternative — reclaims
 * jump the queue — was coherent, and rejecting it has a real cost this test pins down: <b>recovery is
 * not expedited.</b> A crashed low-severity delivery waits behind fresh high-severity traffic.
 *
 * <p>It also keeps FR-202's "exactly one place" honest. Exempting the reclaim branch would have meant a
 * second ordering path, which is a second place the rank is applied.
 */
@TestPropertySource(
        properties = {
            "notification.features.severity-claim-order=true",
            "notification.features.delivery-reclaim=true"
        })
class SeverityReclaimOrderTest extends SeverityClaimOrderSupport {

    @Test
    void aFreshCriticalIsClaimedAheadOfAStrandedLow() throws Exception {
        UUID lowNotification = submit("reclaim-low", Severity.LOW);

        // Strand it for real: the provider is called and then the attempt dies. Note the consequence —
        // a stranded delivery has ALREADY had one provider call, so any assertion of zero calls here
        // would be asserting the fixture rather than the behaviour.
        scripts.of(Channel.EMAIL).thenThrow();
        try {
            worker.runOnce();
        } catch (RuntimeException expected) {
            // The simulated crash. Swallowed because the crash IS the setup, not the assertion.
        }
        clock.advance(Duration.ofMinutes(2)); // past the lease, so the row is reclaimable

        UUID criticalNotification = submit("reclaim-critical", Severity.CRITICAL);
        scripts.of(Channel.EMAIL).alwaysSucceed();

        assertThat(claimNotifications(1))
                .as("a fresh CRITICAL outranks a stranded LOW — recovery is not expedited (D-16)")
                .containsExactly(criticalNotification)
                .doesNotContain(lowNotification);
    }

    @Test
    void aStrandedCriticalStillOutranksAFreshLow() throws Exception {
        UUID criticalNotification = submit("reclaim-crit-stranded", Severity.CRITICAL);

        scripts.of(Channel.EMAIL).thenThrow();
        try {
            worker.runOnce();
        } catch (RuntimeException expected) {
            // as above
        }
        clock.advance(Duration.ofMinutes(2));

        submit("reclaim-low-fresh", Severity.LOW);
        scripts.of(Channel.EMAIL).alwaysSucceed();

        // The converse of the case above, and the reason D-16 is not simply "reclaims go last". They are
        // ordered on the same terms as anything else, so a stranded CRITICAL is still recovered first.
        assertThat(claimNotifications(1))
                .as("reclaims participate in ordering rather than being deprioritised")
                .containsExactly(criticalNotification);
    }
}
