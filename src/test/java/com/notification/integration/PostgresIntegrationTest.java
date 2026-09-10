package com.notification.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T030 — base class for integration tests.
 *
 * <p>Real PostgreSQL, because ADR-003 rejected H2: its locking semantics differ, so the
 * concurrent-worker claim test would prove less than it appears to — unacceptable for a
 * non-negotiable gate (Principle II).
 *
 * <p>The container is static, so one instance is shared across the suite rather than restarted per
 * class. Flyway migrates it on context startup, which also makes every run a test of the
 * migrations themselves.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@org.springframework.context.annotation.Import({TestClockConfig.class, TestChannelConfig.class})
public abstract class PostgresIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("notifications")
                    .withUsername("notifications")
                    .withPassword("test-only-not-a-secret")
                    .withReuse(false);

    static {
        POSTGRES.start();
    }

    /**
     * The scripted providers are a context-wide singleton, so a script set by one test would
     * otherwise leak into every test that runs after it. Resetting here rather than in a subclass
     * means no test can forget: Principle VI forbids reliance on execution order, and a shared
     * mutable fixture is the most common way that creeps back in.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TestChannelConfig.Scripts sharedScripts;

    @org.junit.jupiter.api.BeforeEach
    void resetSharedChannelScripts() {
        if (sharedScripts != null) {
            sharedScripts.resetAll();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.simple.JdbcClient sharedJdbc;

    /**
     * Parks deliveries left due by earlier tests, so one worker run reaches the delivery under test.
     *
     * <p>The container is shared for the whole suite and the worker claims a bounded batch ordered
     * oldest-first. Once the suite accumulates more due deliveries than one batch holds, a test's own
     * brand-new delivery is the newest row and falls outside the batch — so {@code worker.runOnce()}
     * silently processes other tests' leftovers instead, and the test fails on an unattempted delivery.
     * That is a real coupling between unrelated tests, and it appears only once the suite crosses the
     * batch size, which makes it the kind of failure that looks like a defect in whichever test was
     * added last.
     *
     * <p>Parking rather than deleting or forcing a terminal state: pushing {@code next_attempt_at} out
     * makes a row not-due without inventing an outcome, so nothing another test asserted about a
     * delivery is destroyed or falsified. Stale IN_PROGRESS rows get a live lease for the same reason —
     * a lease in the future is what "another worker has it" already means, so reclaim leaves it alone
     * without any special case.
     */
    @org.junit.jupiter.api.BeforeEach
    void parkDeliveriesLeftDueByEarlierTests() {
        if (sharedJdbc == null) {
            return;
        }
        java.sql.Timestamp farFuture =
                java.sql.Timestamp.from(java.time.Instant.parse("2999-01-01T00:00:00Z"));
        sharedJdbc
                .sql("UPDATE delivery SET next_attempt_at = ? WHERE state IN ('QUEUED', 'RETRY_SCHEDULED')")
                .param(farFuture)
                .update();
        sharedJdbc
                .sql("UPDATE delivery SET claimed_until = ? WHERE state = 'IN_PROGRESS'")
                .param(farFuture)
                .update();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
