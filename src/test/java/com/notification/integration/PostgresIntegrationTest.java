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

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
