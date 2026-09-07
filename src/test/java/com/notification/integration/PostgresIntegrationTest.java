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

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
