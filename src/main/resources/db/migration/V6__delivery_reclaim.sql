-- T045 — reclaim of deliveries stranded mid-attempt (feature 002 FR-159, baseline fact B-13).
--
-- The defect: claimDue claims only QUEUED and RETRY_SCHEDULED. A worker or provider that stops
-- after the move to IN_PROGRESS leaves the row unclaimable, nothing sweeps stale leases, and no path
-- resets the state. The delivery never terminates, and rollup rule 2b then reports the notification
-- IN_PROGRESS indefinitely — status lying about work that will never complete.
--
-- No column change and no data migration: claimed_until and state already exist. This adds the index
-- that makes reclaiming them cheap.
--
-- WARNING for whoever tunes this later: reclaiming IN_PROGRESS means a delivery whose provider call
-- is merely SLOW — longer than the lease — can be reclaimed while still in flight (G-58). That is
-- why the idempotency key of FR-161 is not optional, and why notification.worker.delivery-lease MUST
-- stay above notification.channel.read-timeout.
CREATE INDEX idx_delivery_reclaimable
    ON delivery (claimed_until)
    WHERE state = 'IN_PROGRESS';
