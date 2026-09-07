-- T027 — Principle V, second layer of enforcement.
--
-- The AuditRepositoryPort exposes no update or delete method, so the application
-- cannot mutate audit history through its own code. This withholds the privilege
-- as well, so a raw JdbcClient call cannot do it either.
--
-- Runs only when a dedicated application role exists; a local dev superuser
-- connection skips it harmlessly.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_app') THEN
        REVOKE UPDATE, DELETE ON audit_event               FROM notification_app;
        REVOKE UPDATE, DELETE ON routing_decision          FROM notification_app;
        REVOKE UPDATE, DELETE ON routing_channel_outcome   FROM notification_app;
        GRANT  INSERT, SELECT  ON audit_event              TO   notification_app;
        GRANT  INSERT, SELECT  ON routing_decision         TO   notification_app;
        GRANT  INSERT, SELECT  ON routing_channel_outcome  TO   notification_app;
    END IF;
END
$$;
