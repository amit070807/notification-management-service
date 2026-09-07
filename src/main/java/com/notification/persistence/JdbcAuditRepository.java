package com.notification.persistence;

import com.notification.domain.model.AuditEventType;
import com.notification.domain.model.AuditRecord;
import com.notification.domain.port.AuditRepositoryPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * T026 — append-only audit persistence (Principle V, FR-050).
 *
 * <p>There is no update and no delete method here, mirroring the port. V2__grants.sql withholds
 * the privileges as well, so a raw JdbcClient call elsewhere in the codebase could not do it
 * either. Two layers, because "the reviewer will notice" is not an enforcement mechanism.
 */
@Repository
public class JdbcAuditRepository implements AuditRepositoryPort {

    private final JdbcClient jdbc;

    public JdbcAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AuditRecord record) {
        jdbc.sql(
                        """
                        INSERT INTO audit_event
                            (id, notification_id, correlation_id, event_type, occurred_at, payload)
                        VALUES (:id, :notificationId, :correlationId, :eventType, :occurredAt, :payload::jsonb)
                        """)
                .param("id", record.id())
                .param("notificationId", record.notificationId())
                .param("correlationId", record.correlationId())
                .param("eventType", record.eventType().name())
                .param("occurredAt", java.sql.Timestamp.from(record.occurredAt()))
                .param("payload", toJson(record.payload()))
                .update();
    }

    @Override
    public List<AuditRecord> findByNotificationId(UUID notificationId) {
        return jdbc.sql(
                        "SELECT * FROM audit_event WHERE notification_id = :id ORDER BY occurred_at, id")
                .param("id", notificationId)
                .query(this::map)
                .list();
    }

    @Override
    public List<AuditRecord> findByCorrelationId(String correlationId) {
        return jdbc.sql(
                        "SELECT * FROM audit_event WHERE correlation_id = :cid ORDER BY occurred_at, id")
                .param("cid", correlationId)
                .query(this::map)
                .list();
    }

    private AuditRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("notification_id", UUID.class),
                rs.getString("correlation_id"),
                AuditEventType.valueOf(rs.getString("event_type")),
                rs.getTimestamp("occurred_at").toInstant(),
                fromJson(rs.getString("payload")));
    }

    /**
     * Minimal, dependency-free JSON for a flat string map. The payload is built exclusively from
     * the sealed allowlist, so the shape is always flat and the values are always strings.
     */
    private static String toJson(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s == null
                ? ""
                : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static Map<String, String> fromJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.length() < 2) {
            return out;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                        .matcher(json);
        while (m.find()) {
            out.put(unescape(m.group(1)), unescape(m.group(2)));
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Convenience for callers building a record from the allowlist. */
    public static AuditRecord record(
            UUID id,
            UUID notificationId,
            String correlationId,
            AuditEventType type,
            Instant at,
            com.notification.audit.payload.AuditPayload payload) {
        return new AuditRecord(id, notificationId, correlationId, type, at, payload.fields());
    }
}
