package com.notification.performance;

import com.notification.domain.model.Channel;
import com.notification.integration.RetryTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * T068 — measures what each enhancement costs, and asserts nothing about it (FR-107, SC-112, G-40).
 *
 * <p>No source document states a performance threshold, so there is no verdict to render. Asserting one
 * would invent a requirement and then dress the invention up as a passing gate. This class produces
 * numbers for {@code docs/performance-phase2.md} to report; the interpretation is the reader's.
 *
 * <p>Excluded from the {@code test} task and run via {@code ./gradlew perfTest}. A timing measurement
 * inside a merge gate would be flaky by construction — it measures the machine as much as the code —
 * and Principle VI does not permit a gate that fails for reasons unrelated to correctness.
 *
 * <p>Two flag states are needed and a flag is fixed per Spring context, so the comparison lives in two
 * classes sharing this base. What is measured is the marginal cost of the boundary check on the
 * submission path: one indexed lookup per submission.
 */
abstract class EnhancementCostTest extends RetryTestSupport {

    @org.springframework.beans.factory.annotation.Autowired protected JdbcClient jdbc;

    protected static final int WARMUP = 20;
    protected static final int MEASURED = 200;

    /** @return per-submission wall time in microseconds, and the total, printed for the doc */
    protected void measureSubmissions(String label) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            submitEmailOnly(label + "-warm-" + i);
        }

        List<Long> samples = new ArrayList<>(MEASURED);
        for (int i = 0; i < MEASURED; i++) {
            Instant start = Instant.now();
            submitEmailOnly(label + "-run-" + i);
            samples.add(Duration.between(start, Instant.now()).toNanos());
        }
        samples.sort(null);

        System.out.printf(
                "PERF %s n=%d p50=%.2fms p95=%.2fms mean=%.2fms%n",
                label,
                MEASURED,
                samples.get(MEASURED / 2) / 1_000_000.0,
                samples.get((int) (MEASURED * 0.95)) / 1_000_000.0,
                samples.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0);
    }

    /** Measures one delivery attempt, which is where the idempotency key derivation lands. */
    protected void measureDeliveries(String label) throws Exception {
        scripts.of(Channel.EMAIL).alwaysSucceed();
        for (int i = 0; i < WARMUP; i++) {
            submitEmailOnly(label + "-dwarm-" + i);
        }
        poller.drainOnce();
        worker.runOnce();

        List<Long> samples = new ArrayList<>(MEASURED);
        for (int i = 0; i < MEASURED; i++) {
            submitEmailOnly(label + "-drun-" + i);
            poller.drainOnce();
            Instant start = Instant.now();
            worker.runOnce();
            samples.add(Duration.between(start, Instant.now()).toNanos());
        }
        samples.sort(null);

        System.out.printf(
                "PERF %s-delivery n=%d p50=%.2fms p95=%.2fms%n",
                label,
                MEASURED,
                samples.get(MEASURED / 2) / 1_000_000.0,
                samples.get((int) (MEASURED * 0.95)) / 1_000_000.0);
    }

    /**
     * Times the two queries the enhancements actually add, in ONE context.
     *
     * <p>This is the measurement worth reading. The end-to-end comparison below spans two Spring
     * contexts with separate pools, JIT states and row counts, and it showed the delivery path getting
     * <i>faster</i> with the flags on — which cannot be a code effect and so is direct evidence that its
     * noise floor exceeds the signal. Timing the added queries in a single context removes that.
     */
    protected void measureAddedQueries() {
        // The deduplication boundary lookup: one indexed read per submission.
        timeQuery(
                "dedup-boundary-lookup",
                () ->
                        jdbc.sql(
                                        "SELECT id FROM notification WHERE source_system = 'billing' "
                                                + "AND correlation_id = 'corr-perf-probe' "
                                                + "ORDER BY received_at DESC LIMIT 1")
                                .query(String.class)
                                .optional());

        // claimDue with the reclaim branch present. Compared against the same query without it, so the
        // cost measured is the added OR branch rather than the whole claim.
        timeQuery("claim-with-reclaim-branch", () -> countClaimable(true));
        timeQuery("claim-without-reclaim-branch", () -> countClaimable(false));
    }

    private Object countClaimable(boolean withReclaim) {
        String reclaimBranch =
                withReclaim
                        ? " OR (d.state = 'IN_PROGRESS' AND d.claimed_until IS NOT NULL"
                                + " AND d.claimed_until < now())"
                        : "";
        return jdbc.sql(
                        "SELECT count(*) FROM delivery d WHERE (d.state IN ('QUEUED', 'RETRY_SCHEDULED')"
                                + " AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= now())"
                                + " AND (d.claimed_until IS NULL OR d.claimed_until < now()))"
                                + reclaimBranch)
                .query(Integer.class)
                .single();
    }

    private void timeQuery(String label, java.util.function.Supplier<Object> query) {
        for (int i = 0; i < 100; i++) {
            query.get();
        }
        List<Long> samples = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) {
            long start = System.nanoTime();
            query.get();
            samples.add(System.nanoTime() - start);
        }
        samples.sort(null);
        System.out.printf(
                "PERF query %s n=500 p50=%.3fms p95=%.3fms rows_in_table=%d%n",
                label,
                samples.get(250) / 1_000_000.0,
                samples.get(475) / 1_000_000.0,
                jdbc.sql("SELECT count(*) FROM delivery").query(Integer.class).single());
    }

    @TestPropertySource(
            properties = {
                "notification.features.dedup-submission=false",
                "notification.features.delivery-reclaim=false"
            })
    static class FlagsOff extends EnhancementCostTest {
        @Test
        void measureBaseline() throws Exception {
            measureSubmissions("flags-off");
            measureDeliveries("flags-off");
        }
    }

    @TestPropertySource(
            properties = {
                "notification.features.dedup-submission=true",
                "notification.features.delivery-reclaim=true"
            })
    static class FlagsOn extends EnhancementCostTest {
        @Test
        void measureWithEnhancements() throws Exception {
            measureSubmissions("flags-on");
            measureDeliveries("flags-on");
            measureAddedQueries();
        }
    }
}
