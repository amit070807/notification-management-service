package com.notification.performance;

import com.notification.domain.model.Severity;
import com.notification.integration.SeverityClaimOrderSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T027 — measures what the severity join costs, and asserts nothing (FR-107, G-61, G-40).
 *
 * <p>G-61 records that the join-versus-denormalise choice could not be made on measured grounds, because
 * no source document states a performance target. That is still true after measuring: this produces a
 * number for {@code docs/performance-phase2.md} to report, not a verdict on it.
 *
 * <p>The comparison that matters is the claim query with and without the join, in the <b>same</b> Spring
 * context. Feature 002's measurement learned this the hard way — comparing two flag states across two
 * contexts also compared two connection pools, two JIT histories and two table sizes, and produced the
 * impossible result that enabling a feature made it faster. Here the flag state is fixed and the two
 * queries are issued side by side, so the difference is the join and nothing else.
 */
@TestPropertySource(properties = "notification.features.severity-claim-order=true")
class ClaimOrderCostTest extends SeverityClaimOrderSupport {

    private static final int BACKLOG = 200;
    private static final int SAMPLES = 300;

    @Test
    void measureClaimCost() throws Exception {
        for (int i = 0; i < BACKLOG; i++) {
            submit("perf-" + i, Severity.values()[i % Severity.values().length]);
        }

        // Ordered claim, as shipped: joins notification and sorts on the generated rank.
        time("claim-with-severity-join", () -> claim(50));

        // The phase-2 shape, issued against the same rows in the same context, so the delta is the join
        // and the rank evaluation rather than an artefact of comparing two builds.
        time(
                "claim-baseline-age-only",
                () ->
                        jdbc.sql(
                                        """
                                        SELECT d.id FROM delivery d
                                        WHERE d.state IN ('QUEUED', 'RETRY_SCHEDULED')
                                          AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= now())
                                          AND (d.claimed_until IS NULL OR d.claimed_until < now())
                                        ORDER BY d.state_changed_at
                                        LIMIT 50
                                        """)
                                .query(String.class)
                                .list());

        // The rank expression alone, so the join and the CASE can be told apart.
        time(
                "rank-expression-only",
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM delivery d "
                                                + "JOIN notification n ON n.id = d.notification_id "
                                                + "WHERE "
                                                + com.notification.domain.model.SeverityOrdering
                                                        .rankExpression("n.severity")
                                                + " > 0")
                                .query(Integer.class)
                                .single());
    }

    private void time(String label, Runnable query) {
        for (int i = 0; i < 50; i++) {
            query.run();
            releaseAllLeases();
        }

        List<Long> samples = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            query.run();
            samples.add(System.nanoTime() - start);
            releaseAllLeases();
        }
        samples.sort(null);

        System.out.printf(
                "PERF %s n=%d p50=%.3fms p95=%.3fms rows_due=%d%n",
                label,
                SAMPLES,
                samples.get(SAMPLES / 2) / 1_000_000.0,
                samples.get((int) (SAMPLES * 0.95)) / 1_000_000.0,
                jdbc.sql("SELECT count(*) FROM delivery WHERE state IN ('QUEUED','RETRY_SCHEDULED')")
                        .query(Integer.class)
                        .single());
    }

    @Override
    protected java.util.List<java.util.UUID> claim(int batchSize) {
        return deliveries
                .claimDue(clock.now(), clock.now().plus(Duration.ofMinutes(1)), batchSize)
                .stream()
                .map(d -> d.id())
                .toList();
    }
}
