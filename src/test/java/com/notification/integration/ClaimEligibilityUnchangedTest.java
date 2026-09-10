package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Severity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T020 — ordering changes the order, never the set (FR-204, quickstart scenario 7).
 *
 * <p>The claim now joins {@code notification} to read severity. A join is the classic way to
 * accidentally narrow a result set, and the damage here would be quiet: some delivery stops being
 * claimable, the notification never reaches a terminal state, and every ordering test in this feature
 * still passes because the deliveries they assert on are unaffected.
 *
 * <p>{@code delivery.notification_id} is {@code NOT NULL} with a foreign key, so an inner join cannot
 * drop a row. That is a reason to expect this test to pass, not a reason to skip writing it — FR-204 is
 * about the property, and the property should be asserted rather than inferred from a schema someone
 * could later change.
 */
class ClaimEligibilityUnchangedTest {

    /** Both nested classes claim the same fixture; only the flag differs. */
    private abstract static class Fixture extends SeverityClaimOrderSupport {

        /**
         * @return the notifications represented in one full claim, and the raw delivery count
         *     <p>Both are asserted, because they fail differently. A missing notification means the join
         *     filtered a whole submission; an unchanged notification set with a smaller delivery count
         *     means it filtered one channel's delivery — for instance the escalated SMS row a CRITICAL
         *     submission produces (B-02), which a naive join condition could easily drop.
         */
        Claimed claimEverything() throws Exception {
            Set<UUID> expected =
                    Set.of(
                            submit("elig-low", Severity.LOW),
                            submit("elig-critical", Severity.CRITICAL),
                            submit("elig-medium", Severity.MEDIUM));

            // Filtered to this test's own submissions. A claim of 50 takes whatever else the shared
            // container has due, so an unfiltered count would assert the state of the whole suite rather
            // than this feature — passing or failing on which classes ran first.
            List<UUID> claimed = claim(50);
            Set<UUID> mine =
                    claimed.stream()
                            .filter(id -> expected.contains(notificationOf(id)))
                            .collect(java.util.stream.Collectors.toSet());
            return new Claimed(
                    expected,
                    mine.stream().map(this::notificationOf).collect(java.util.stream.Collectors.toSet()),
                    mine.size());
        }

        record Claimed(Set<UUID> submitted, Set<UUID> represented, int deliveryCount) {}
    }

    /**
     * Four deliveries from three submissions: EMAIL for each, plus the SMS the policy escalates to at
     * CRITICAL. Stated as a constant so a change in escalation behaviour fails here loudly rather than
     * quietly relaxing what this test checks.
     */
    private static final int EXPECTED_DELIVERIES = 4;

    @TestPropertySource(properties = "notification.features.severity-claim-order=true")
    static class Enabled extends Fixture {
        @Test
        void everyEligibleDeliveryIsStillClaimed() throws Exception {
            Claimed claimed = claimEverything();

            assertThat(claimed.represented())
                    .as("the join must reorder, not filter — every submission must still be claimable")
                    .isEqualTo(claimed.submitted());
            assertThat(claimed.deliveryCount())
                    .as("no individual delivery may be lost either (FR-204)")
                    .isEqualTo(EXPECTED_DELIVERIES);
        }
    }

    @TestPropertySource(properties = "notification.features.severity-claim-order=false")
    static class Disabled extends Fixture {
        @Test
        void theSameDeliveriesAreClaimedWithOrderingOff() throws Exception {
            // Counts over an identical fixture rather than ids across two contexts: each nested class gets
            // its own Spring context and its own rows, so ids cannot be compared directly. What must match
            // is that neither flag state loses anything — the flag changes order, never membership.
            Claimed claimed = claimEverything();

            assertThat(claimed.represented()).isEqualTo(claimed.submitted());
            assertThat(claimed.deliveryCount()).isEqualTo(EXPECTED_DELIVERIES);
        }
    }
}
