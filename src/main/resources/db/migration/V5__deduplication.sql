-- T050 — submission-level deduplication (feature 002 FR-140, FR-143, spec D9).
--
-- Authorised by constitution v2.1.0, which lifted the register-item-9 deferral. Before that
-- amendment this table could not exist: Principle III explicitly declined to govern
-- duplicate-submission semantics.
--
-- Inert while notification.features.dedup-submission is false — nothing queries it.

-- A suppressed submission does NOT become a notification, for the same reason a rejection does not
-- (phase-1 FR-007): it was never accepted for processing. So it gets its own table rather than a
-- flag on `notification`.
CREATE TABLE notification_suppression (
    id                       uuid PRIMARY KEY,
    source_system            text        NOT NULL,
    correlation_id           text        NOT NULL,
    suppressed_at            timestamptz NOT NULL,
    original_notification_id uuid        NOT NULL REFERENCES notification (id),
    client_notification_id   text        NOT NULL
);

CREATE INDEX idx_suppression_key ON notification_suppression (source_system, correlation_id, suppressed_at);
CREATE INDEX idx_suppression_original ON notification_suppression (original_notification_id);

-- ===========================================================================
-- The boundary lookup index.
--
-- INTENTIONAL: this is an INDEX, not a UNIQUE constraint.
--
-- Uniqueness of (source_system, correlation_id) is a CALLER CONTRACT that this service cannot
-- enforce (feature 002 G-55). Neither requirements document requires the event identifier to be
-- unique, and phase 1 deliberately left client_notification_id unconstrained (D4).
--
-- A unique constraint here would also be the wrong behaviour, not merely a stronger one: it would
-- REJECT a duplicate submission at the database, where the requirement is to SUPPRESS it and report
-- the suppression to the caller (FR-140, FR-144). Rejection and suppression are different outcomes
-- with different response codes.
-- ===========================================================================
CREATE INDEX idx_notification_dedup_key
    ON notification (source_system, correlation_id, received_at DESC);
