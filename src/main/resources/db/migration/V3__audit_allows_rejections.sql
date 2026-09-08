-- Rejected submissions must be audited (source 4.9, FR-006), but a rejection creates no
-- notification row (FR-007) — so audit_event.notification_id had nothing to reference and the
-- event could not be recorded at all. The three requirements were mutually unsatisfiable as
-- the schema stood.
--
-- Resolution: notification_id becomes nullable. correlation_id is already NOT NULL and is the
-- identifier FR-048 designates for retrieving a notification's history, so a rejection remains
-- fully addressable without inventing a placeholder notification.
--
-- Forward-only, and safe on existing rows: relaxing NOT NULL invalidates nothing already stored.
ALTER TABLE audit_event ALTER COLUMN notification_id DROP NOT NULL;

-- Guard the relaxation: only a rejection may omit the notification. Without this, any future
-- bug that failed to set notification_id would silently produce an unattributable audit record.
ALTER TABLE audit_event ADD CONSTRAINT audit_notification_required_unless_rejected
    CHECK (notification_id IS NOT NULL OR event_type = 'NOTIFICATION_REJECTED');
