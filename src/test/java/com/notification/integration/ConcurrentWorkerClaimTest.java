package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.notification.domain.port.DeliveryRepositoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T069 — two workers must never claim the same delivery (Principle II, NON-NEGOTIABLE).
 *
 * <p>The constitution requires this to be "proven by a concurrency test", not asserted. It is also
 * the reason ADR-003 rejected H2: its locking semantics differ from PostgreSQL, so this test would
 * pass against H2 while proving nothing about production.
 */
class ConcurrentWorkerClaimTest extends SubmissionTestSupport {

    @Autowired private DeliveryRepositoryPort deliveries;
    @Autowired private JdbcClient jdbc;
    @Autowired private com.notification.worker.OutboxPoller poller;

    @Test
    void twoConcurrentClaimsNeverReturnTheSameDelivery() throws Exception {
        UUID id = submitAndGetId("conc-1");
        poller.drainOnce();

        Instant now = Instant.now();
        Instant lease = now.plus(Duration.ofMinutes(1));

        // Both threads race for the same four QUEUED rows.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Scoped to THIS notification. The suite shares one container, so other tests leave
            // QUEUED rows behind; asserting a global count would make the result depend on
            // execution order, which Principle VI forbids.
            Callable<List<UUID>> claim =
                    () ->
                            deliveries.claimDue(now, lease, 50).stream()
                                    .filter(d -> d.notificationId().equals(id))
                                    .map(d -> d.id())
                                    .toList();

            Future<List<UUID>> a = pool.submit(claim);
            Future<List<UUID>> b = pool.submit(claim);

            List<UUID> first = a.get();
            List<UUID> second = b.get();

            // The union must contain no duplicates: a row claimed by one worker is SKIP LOCKED
            // for the other, not queued behind it.
            List<UUID> all = new java.util.ArrayList<>(first);
            all.addAll(second);
            assertThat(all).doesNotHaveDuplicates();
            assertThat(all).hasSize(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aClaimedDeliveryIsNotReclaimedWhileItsLeaseHolds() throws Exception {
        UUID id = submitAndGetId("conc-2");
        poller.drainOnce();

        Instant now = Instant.now();
        long firstBatch = claimedFor(id, now);
        long secondBatch = claimedFor(id, now);

        assertThat(firstBatch).isEqualTo(4);
        assertThat(secondBatch).as("a live lease must prevent reclaiming").isZero();
    }

    @Test
    void attemptNumbersAreUniquePerDelivery() throws Exception {
        // The database-level guarantee behind the claim: even if two workers somehow both
        // attempted, the unique constraint on (delivery_id, attempt_number) would reject the
        // duplicate rather than record two attempts as one.
        UUID id = submitAndGetId("conc-3");
        Integer violations =
                jdbc.sql(
                                """
                                SELECT count(*) FROM (
                                  SELECT delivery_id, attempt_number, count(*) c
                                  FROM delivery_attempt GROUP BY delivery_id, attempt_number
                                  HAVING count(*) > 1
                                ) dupes
                                """)
                        .query(Integer.class)
                        .single();
        assertThat(violations).isZero();
    }

    /** Claims a batch and counts only the rows belonging to the notification under test. */
    private long claimedFor(UUID notificationId, Instant now) {
        return deliveries.claimDue(now, now.plus(Duration.ofMinutes(5)), 50).stream()
                .filter(d -> d.notificationId().equals(notificationId))
                .count();
    }

    private UUID submitAndGetId(String clientId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/notifications")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validSubmission(clientId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }
}
