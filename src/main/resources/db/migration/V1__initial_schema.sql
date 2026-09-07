-- T022 — initial schema. Forward-only (constitution Safe Change Management).
-- Every table traces to data-model.md; see that document for per-column requirement traces.

-- ---------------------------------------------------------------------------
-- notification
-- ---------------------------------------------------------------------------
CREATE TABLE notification (
    id                     uuid PRIMARY KEY,
    client_notification_id text        NOT NULL,
    source_system          text        NOT NULL,
    correlation_id         text        NOT NULL,
    notification_type      text        NOT NULL,
    severity               text        NOT NULL,
    priority               text        NOT NULL,
    content_ref            uuid        NOT NULL,
    created_at_client      timestamptz NOT NULL,
    received_at            timestamptz NOT NULL,
    not_before_at          timestamptz NULL,
    expires_at             timestamptz NULL,
    state                  text        NOT NULL,
    state_changed_at       timestamptz NOT NULL,

    -- FR-003d: a not-before at or after expiry describes a window that can never open.
    CONSTRAINT notification_window_valid
        CHECK (not_before_at IS NULL OR expires_at IS NULL OR not_before_at < expires_at)
);

-- ===========================================================================
-- INTENTIONAL: client_notification_id has NO UNIQUE CONSTRAINT.
--
-- This is not an oversight. Source 4.1 names a caller-supplied "Notification
-- identifier" but never requires it to be unique, and the project constitution
-- (v2.0.0, register item 9) defers idempotency to a later deliverable.
-- Spec decision D4 therefore accepts duplicates as INDEPENDENT notifications,
-- each addressed by its own server-issued `id`, which is the status retrieval key.
--
-- Adding UNIQUE here would silently implement a deduplication behaviour that was
-- deliberately deferred, and would break FR-008b. If idempotency is later taken
-- out of deferral, that is a specification change, not a schema tidy-up.
-- ===========================================================================
CREATE INDEX idx_notification_client_id ON notification (client_notification_id);
CREATE INDEX idx_notification_correlation ON notification (correlation_id);

-- ---------------------------------------------------------------------------
-- notification_content — separate table so the sensitive payload has its own
-- grant and can be purged independently if G-25 is resolved toward minimisation.
-- ---------------------------------------------------------------------------
CREATE TABLE notification_content (
    id                 uuid PRIMARY KEY,
    payload            bytea NOT NULL,
    payload_size_bytes int   NOT NULL,
    payload_ref        text  NOT NULL
);

ALTER TABLE notification
    ADD CONSTRAINT fk_notification_content FOREIGN KEY (content_ref) REFERENCES notification_content (id);

-- ---------------------------------------------------------------------------
-- recipient — an opaque reference and nothing else (spec D5).
-- There is deliberately NO address column: source 4.1 defines no recipient field,
-- so none is invented. Adding one is a scope change (see spec G-32).
-- ---------------------------------------------------------------------------
CREATE TABLE recipient (
    id              uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES notification (id),
    recipient_ref   text NOT NULL
);
CREATE INDEX idx_recipient_notification ON recipient (notification_id);

-- ---------------------------------------------------------------------------
-- routing_decision / routing_channel_outcome — immutable (FR-021).
-- ---------------------------------------------------------------------------
CREATE TABLE routing_decision (
    id              uuid PRIMARY KEY,
    notification_id uuid        NOT NULL UNIQUE REFERENCES notification (id),
    policy_version  text        NOT NULL,
    decided_at      timestamptz NOT NULL
);

CREATE TABLE routing_channel_outcome (
    id                  uuid PRIMARY KEY,
    routing_decision_id uuid    NOT NULL REFERENCES routing_decision (id),
    recipient_id        uuid    NOT NULL REFERENCES recipient (id),
    channel             text    NOT NULL,
    selected            boolean NOT NULL,
    reason_code         text    NOT NULL
);
CREATE INDEX idx_rco_decision ON routing_channel_outcome (routing_decision_id);

-- ---------------------------------------------------------------------------
-- delivery — one per (notification, recipient, channel), per source 4.2.
-- ---------------------------------------------------------------------------
CREATE TABLE delivery (
    id                           uuid PRIMARY KEY,
    notification_id              uuid        NOT NULL REFERENCES notification (id),
    recipient_id                 uuid        NOT NULL REFERENCES recipient (id),
    channel                      text        NOT NULL,
    state                        text        NOT NULL,
    attempt_count                int         NOT NULL DEFAULT 0,
    next_attempt_at              timestamptz NULL,
    last_failure_classification  text        NULL,
    claimed_until                timestamptz NULL,
    state_changed_at             timestamptz NOT NULL,

    CONSTRAINT uq_delivery_recipient_channel UNIQUE (notification_id, recipient_id, channel)
);
CREATE INDEX idx_delivery_claimable ON delivery (state, next_attempt_at) WHERE state IN ('QUEUED', 'RETRY_SCHEDULED');

-- ---------------------------------------------------------------------------
-- delivery_attempt — every attempt separately observable (FR-043).
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_attempt (
    id                     uuid PRIMARY KEY,
    delivery_id            uuid        NOT NULL REFERENCES delivery (id),
    attempt_number         int         NOT NULL,
    started_at             timestamptz NOT NULL,
    finished_at            timestamptz NULL,
    outcome                text        NOT NULL,
    failure_classification text        NULL,
    -- Bounded and sanitised. A provider's raw response body may echo the submitted
    -- content straight into audit, so adapters must never propagate it (Principle V).
    diagnostic             varchar(200) NULL,

    CONSTRAINT uq_attempt_number UNIQUE (delivery_id, attempt_number)
);

-- ---------------------------------------------------------------------------
-- audit_event — append-only (FR-050). V2__grants.sql withholds UPDATE and DELETE.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
    id              uuid PRIMARY KEY,
    notification_id uuid        NOT NULL REFERENCES notification (id),
    correlation_id  text        NOT NULL,
    event_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    payload         jsonb       NOT NULL
);
CREATE INDEX idx_audit_notification ON audit_event (notification_id, occurred_at);
CREATE INDEX idx_audit_correlation ON audit_event (correlation_id, occurred_at);

-- ---------------------------------------------------------------------------
-- outbox — written in the SAME transaction as the notification, so the handoff
-- cannot lose or phantom-create work if the process dies (Principle II).
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    id              bigserial PRIMARY KEY,
    notification_id uuid        NOT NULL REFERENCES notification (id),
    created_at      timestamptz NOT NULL,
    processed_at    timestamptz NULL,
    claimed_until   timestamptz NULL
);
CREATE INDEX idx_outbox_unprocessed ON outbox (id) WHERE processed_at IS NULL;
