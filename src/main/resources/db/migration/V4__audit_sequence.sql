-- Audit history has no reliable order without this.
--
-- Every event written inside the acceptance transaction carries the SAME occurred_at, because
-- they are recorded at one instant. Ordering by (occurred_at, id) therefore fell back to a
-- random UUID, so NOTIFICATION_ACCEPTED could be returned after DELIVERY_QUEUED. In production
-- the wall clock usually advances between writes, which makes this the worst kind of bug: mostly
-- right, occasionally scrambled, and invisible until someone is reading a history during an
-- incident.
--
-- Section 4.9 calls this "Audit History" and SC-010 requires a reviewer to reconstruct the
-- lifecycle from it, so sequence is part of the requirement, not a nicety.
--
-- A bigserial reflects insertion order exactly and does not depend on clock resolution.
ALTER TABLE audit_event ADD COLUMN sequence bigserial;

CREATE INDEX idx_audit_notification_sequence ON audit_event (notification_id, sequence);
CREATE INDEX idx_audit_correlation_sequence ON audit_event (correlation_id, sequence);
