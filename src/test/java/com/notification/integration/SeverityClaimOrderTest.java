package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Severity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T014-T016 — severity leads the claim order (US1, FR-201, FR-203, SC-201, SC-203).
 */
@TestPropertySource(properties = "notification.features.severity-claim-order=true")
class SeverityClaimOrderTest extends SeverityClaimOrderSupport {

    @Test
    void aYoungerCriticalIsClaimedBeforeAnOlderLow() throws Exception {
        UUID low = submit("sev-order-low", Severity.LOW);
        UUID critical = submit("sev-order-critical", Severity.CRITICAL);

        // Batch size one, because that is what makes the ordering observable at all: with a batch large
        // enough for both, the claim returns both and the question becomes processing order instead.
        // Asserted over the notification, because a CRITICAL submission also produces an escalated SMS
        // delivery (B-02) and which of its two the claim returns first is not what FR-201 governs.
        assertThat(claimNotifications(1))
                .as("CRITICAL must be claimed first despite being younger (FR-201)")
                .containsExactly(critical)
                .doesNotContain(low);
    }

    @Test
    void equalSeverityKeepsOldestFirst() throws Exception {
        UUID older = submit("sev-equal-a", Severity.HIGH);
        submit("sev-equal-b", Severity.HIGH);

        // FR-203: severity REFINES the existing order rather than replacing it. If it replaced it, the
        // order among equal severities would be arbitrary and this assertion would be a coin flip.
        assertThat(claimNotifications(1)).containsExactly(older);
    }

    @Test
    void allFourSeveritiesAreClaimedInRankOrder() throws Exception {
        // Submitted youngest-severity-first so age and rank disagree on every pair. Submitting them in
        // rank order would let a query that ignored severity entirely pass this test.
        UUID low = submit("sev-all-low", Severity.LOW);
        UUID medium = submit("sev-all-medium", Severity.MEDIUM);
        UUID high = submit("sev-all-high", Severity.HIGH);
        UUID critical = submit("sev-all-critical", Severity.CRITICAL);

        // Claim one at a time, settling each, so the sequence is observable across four claims.
        // Deduplicated to notifications: CRITICAL contributes two deliveries via escalation, and both
        // rank above the rest, so the raw claim sequence would show it twice.
        List<UUID> order = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID notification = claimOneAndSettle();
            if (!order.contains(notification)) {
                order.add(notification);
            }
        }

        assertThat(order)
                .as("claim order must follow rank, not the stored string")
                .containsExactly(critical, high, medium, low);

        // Stated separately because it is THE pair that lexicographic ordering inverts (G-60). An
        // implementation sorting on the text column orders CRITICAL and HIGH correctly and gets these
        // two backwards, and would pass both tests above.
        assertThat(order.indexOf(medium))
                .as("MEDIUM must precede LOW — the pair lexicographic ordering inverts")
                .isLessThan(order.indexOf(low));
    }

    /**
     * Claims one delivery, marks it terminal, and returns its notification.
     *
     * <p>Marked DELIVERED rather than having its lease released: a released row is claimable again and,
     * being the highest-ranked, would be returned on every subsequent claim forever.
     */
    private UUID claimOneAndSettle() {
        UUID claimed = claim(1).get(0);
        UUID notification = notificationOf(claimed);
        jdbc.sql("UPDATE delivery SET state = 'DELIVERED', claimed_until = NULL WHERE id = ?")
                .param(claimed)
                .update();
        return notification;
    }
}
