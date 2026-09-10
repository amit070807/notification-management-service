package com.notification.audit.payload;

import java.util.Map;

/**
 * T024 — the closed allowlist of what may appear in an audit record (FR-054).
 *
 * <p>Sealed on purpose. Source 4.9 requires audit data to "avoid storing unnecessary sensitive
 * message content or credentials", and "unnecessary" is a judgment term that is untestable until
 * the permitted set is named. Naming it as a sealed hierarchy means the content payload is
 * <em>structurally</em> excluded rather than merely forbidden: no member carries it, so no code
 * path can put it there by accident.
 *
 * <p>A free-form {@code Map<String, Object>} was rejected precisely because it would let any caller
 * add any field.
 */
public sealed interface AuditPayload
        permits
                AuditPayload.NotificationAccepted,
                AuditPayload.NotificationRejected,
                AuditPayload.RoutingDecisionMade,
                AuditPayload.DeliveryQueued,
                AuditPayload.DeliveryAttempted,
                AuditPayload.DeliverySucceeded,
                AuditPayload.DeliveryFailed,
                AuditPayload.RetryScheduled,
                AuditPayload.DeliveryExpired,
                AuditPayload.RetryBudgetExhausted,
                AuditPayload.NotificationSuppressed,
                AuditPayload.RetryExecuted,
                AuditPayload.DeliveryReclaimed {

    /** Flattened for storage. Implementations must emit only non-sensitive, allowlisted fields. */
    Map<String, String> fields();

    record NotificationAccepted(String clientNotificationId, String sourceSystem, int recipientCount,
            int requestedChannelCount, String contentRef) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            // contentRef is the non-reversible reference, never the payload itself (FR-051).
            return Map.of(
                    "clientNotificationId", clientNotificationId,
                    "sourceSystem", sourceSystem,
                    "recipientCount", String.valueOf(recipientCount),
                    "requestedChannelCount", String.valueOf(requestedChannelCount),
                    "contentRef", contentRef);
        }
    }

    record NotificationRejected(String clientNotificationId, String sourceSystem, String rejectedFields)
            implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            // Field NAMES only. Never the offending values, which may be sensitive.
            return Map.of(
                    "clientNotificationId", clientNotificationId,
                    "sourceSystem", sourceSystem,
                    "rejectedFields", rejectedFields);
        }
    }

    record RoutingDecisionMade(String policyVersion, String selectedChannels, int outcomeCount)
            implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "policyVersion", policyVersion,
                    "selectedChannels", selectedChannels,
                    "outcomeCount", String.valueOf(outcomeCount));
        }
    }

    record DeliveryQueued(String deliveryId, String maskedRecipient, String channel) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of("deliveryId", deliveryId, "recipient", maskedRecipient, "channel", channel);
        }
    }

    record DeliveryAttempted(String deliveryId, String maskedRecipient, String channel, int attemptNumber)
            implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "deliveryId", deliveryId,
                    "recipient", maskedRecipient,
                    "channel", channel,
                    "attemptNumber", String.valueOf(attemptNumber));
        }
    }

    record DeliverySucceeded(String deliveryId, String maskedRecipient, String channel, int attemptNumber)
            implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "deliveryId", deliveryId,
                    "recipient", maskedRecipient,
                    "channel", channel,
                    "attemptNumber", String.valueOf(attemptNumber));
        }
    }

    record DeliveryFailed(String deliveryId, String maskedRecipient, String channel, int attemptNumber,
            String classification) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            // No adapter-supplied text of any kind. The classification is the defensible summary;
            // free text from a provider is the one field that could carry the submitted content
            // into audit, and the safest treatment of an unnecessary field is not to have it
            // (source 4.9's minimisation rule). The bounded diagnostic is kept on delivery_attempt
            // for operators, which is operational state rather than audit history.
            return Map.of(
                    "deliveryId", deliveryId,
                    "recipient", maskedRecipient,
                    "channel", channel,
                    "attemptNumber", String.valueOf(attemptNumber),
                    "classification", classification);
        }
    }

    record RetryScheduled(String deliveryId, String channel, int attemptNumber, String nextAttemptAt,
            String classification) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "deliveryId", deliveryId,
                    "channel", channel,
                    "attemptNumber", String.valueOf(attemptNumber),
                    "nextAttemptAt", nextAttemptAt,
                    "classification", classification);
        }
    }

    record DeliveryExpired(String deliveryId, String channel, String expiresAt, int attemptsMade)
            implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "deliveryId", deliveryId,
                    "channel", channel,
                    "expiresAt", expiresAt,
                    "attemptsMade", String.valueOf(attemptsMade));
        }
    }

    record RetryBudgetExhausted(String deliveryId, String channel, int attemptsMade, String lastClassification)
            implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "deliveryId", deliveryId,
                    "channel", channel,
                    "attemptsMade", String.valueOf(attemptsMade),
                    "lastClassification", lastClassification);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Feature 002.
    // ---------------------------------------------------------------------------------------

    /**
     * A submission suppressed as a duplicate (FR-143).
     *
     * <p>Carries the deduplication key components and the original notification, so a reader can
     * answer both "why was this suppressed" and "what was it a duplicate of". No content and no
     * recipient reference: a suppressed submission never became a notification, and the caller who
     * needs the detail already has it.
     */
    record NotificationSuppressed(String clientNotificationId, String sourceSystem,
            String correlationId, String originalNotificationId) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "clientNotificationId", clientNotificationId,
                    "sourceSystem", sourceSystem,
                    "correlationId", correlationId,
                    "originalNotificationId", originalNotificationId);
        }
    }

    /**
     * A scheduled retry actually running (FR-150).
     *
     * <p>{@code scheduledRef} is the load-bearing field. Both a scheduling record and an attempt
     * record already existed, but nothing tied an execution to the scheduling that caused it, so
     * across several retries a reader could not pair them (FR-150a).
     */
    record RetryExecuted(String deliveryId, String channel, int attemptNumber, String scheduledRef,
            String scheduledFor) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "deliveryId", deliveryId,
                    "channel", channel,
                    "attemptNumber", String.valueOf(attemptNumber),
                    "scheduledRef", scheduledRef,
                    "scheduledFor", scheduledFor);
        }
    }

    /**
     * A delivery stranded mid-attempt, recovered once its lease expired (FR-159).
     *
     * <p>Records the attempts already made, because a reclaim does not reset the budget (FR-159a) and
     * an operator reading the history needs to see that it did not.
     */
    record DeliveryReclaimed(String deliveryId, String channel, int attemptsMade,
            String leaseExpiredAt) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            return Map.of(
                    "deliveryId", deliveryId,
                    "channel", channel,
                    "attemptsMade", String.valueOf(attemptsMade),
                    "leaseExpiredAt", leaseExpiredAt);
        }
    }
}
