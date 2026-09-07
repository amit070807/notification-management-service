package com.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the Flyway migrations apply cleanly and that the two deliberate schema decisions
 * survive — both are the kind a future maintainer might "correct".
 */
class SchemaMigrationTest extends PostgresIntegrationTest {

    @Autowired private JdbcClient jdbc;

    @Test
    void allTablesExist() {
        List<String> tables =
                jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                        .query(String.class)
                        .list();
        assertThat(tables)
                .contains(
                        "notification",
                        "notification_content",
                        "recipient",
                        "routing_decision",
                        "routing_channel_outcome",
                        "delivery",
                        "delivery_attempt",
                        "audit_event",
                        "outbox");
    }

    @Test
    void clientNotificationIdHasNoUniqueConstraint() {
        // Spec D4 / FR-008b: duplicates are accepted as independent notifications. A UNIQUE here
        // would silently implement the deduplication the constitution defers (register item 9).
        Integer uniqueConstraints =
                jdbc.sql(
                                """
                                SELECT count(*)
                                FROM information_schema.table_constraints tc
                                JOIN information_schema.key_column_usage kcu
                                  ON tc.constraint_name = kcu.constraint_name
                                WHERE tc.table_name = 'notification'
                                  AND tc.constraint_type = 'UNIQUE'
                                  AND kcu.column_name = 'client_notification_id'
                                """)
                        .query(Integer.class)
                        .single();
        assertThat(uniqueConstraints)
                .as("client_notification_id must NOT be unique — duplicates are accepted (D4)")
                .isZero();
    }

    @Test
    void recipientTableHoldsNoAddressColumn() {
        // Spec D5 / G-32: the source defines no recipient field, so none is invented. An address
        // column appearing here would be a scope change smuggled in as a schema tweak.
        List<String> columns =
                jdbc.sql(
                                "SELECT column_name FROM information_schema.columns "
                                        + "WHERE table_name = 'recipient'")
                        .query(String.class)
                        .list();
        assertThat(columns).containsExactlyInAnyOrder("id", "notification_id", "recipient_ref");
    }

    @Test
    void deliveryIsUniquePerRecipientAndChannel() {
        Integer c =
                jdbc.sql(
                                "SELECT count(*) FROM information_schema.table_constraints "
                                        + "WHERE table_name = 'delivery' AND constraint_type = 'UNIQUE'")
                        .query(Integer.class)
                        .single();
        assertThat(c).isGreaterThanOrEqualTo(1);
    }
}
