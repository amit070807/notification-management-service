package com.notification.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Severity;
import com.notification.integration.SeverityClaimOrderSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T021 — with the flag off, the claim is the phase-2 baseline (FR-206, SC-204, B-01).
 *
 * <p>The flag is pinned off explicitly rather than inherited from the shipped default. Reading the
 * default would make this test pass for the wrong reason the day someone flips it: it would then be
 * asserting the new behaviour under a name promising the old.
 *
 * <p>This is the rollback path, and the rollback path is the one nobody looks at — which is precisely
 * why ADR-029 keeps both states in one SQL string. Two queries chosen by a boolean would let a fix land
 * on the enabled path and be forgotten here, invisibly.
 */
@TestPropertySource(properties = "notification.features.severity-claim-order=false")
class ClaimOrderUnchangedTest extends SeverityClaimOrderSupport {

    @Test
    void severityIsIgnoredEntirelyAndTheOldestIsClaimedFirst() throws Exception {
        UUID oldestButLowest = submit("baseline-low", Severity.LOW);
        submit("baseline-critical", Severity.CRITICAL);

        assertThat(claimNotifications(1))
                .as("flag off must claim oldest-first regardless of severity (B-01)")
                .containsExactly(oldestButLowest);
    }

    @Test
    void theFullAgeOrderIsPreservedAcrossMixedSeverities() throws Exception {
        // Submitted in ascending age, descending severity, so a query that leaked ANY severity weighting
        // would produce a different order. A single-pair assertion could pass on a partial leak.
        UUID first = submit("baseline-a", Severity.CRITICAL);
        UUID second = submit("baseline-b", Severity.LOW);
        UUID third = submit("baseline-c", Severity.HIGH);
        UUID fourth = submit("baseline-d", Severity.MEDIUM);

        // Compared on the EMAIL delivery of each, in age order. A CRITICAL submission also produces an
        // escalated SMS delivery sharing its state_changed_at, and the order between two rows that tie on
        // both sort keys is not something any requirement fixes — so asserting the full raw sequence
        // would be asserting the query plan.
        assertThat(claim(50))
                .as("the claimed order must be pure age, byte-for-byte phase 2")
                .containsSubsequence(
                        emailDeliveryOf(first),
                        emailDeliveryOf(second),
                        emailDeliveryOf(third),
                        emailDeliveryOf(fourth));
    }
}
