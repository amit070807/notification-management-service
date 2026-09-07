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
                AuditPayload.RetryBudgetExhausted {

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
            String classification, String diagnostic) implements AuditPayload {
        @Override
        public Map<String, String> fields() {
            // diagnostic is bounded and sanitised by the adapter — never a provider response body.
            return Map.of(
                    "deliveryId", deliveryId,
                    "recipient", maskedRecipient,
                    "channel", channel,
                    "attemptNumber", String.valueOf(attemptNumber),
                    "classification", classification,
                    "diagnostic", diagnostic == null ? "" : diagnostic);
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
}
