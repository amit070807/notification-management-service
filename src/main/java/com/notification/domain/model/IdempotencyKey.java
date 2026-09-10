package com.notification.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * T011/T013 — the key a provider uses to recognise a repeated send (FR-161, FR-163, ADR-019).
 *
 * <p><b>Derived, never stored.</b> Every component comes from a row written <i>before</i> the provider
 * call — the delivery at acceptance, the attempt at the start of the attempt — so the key is
 * reproducible after a crash that recorded nothing further. A stored key would need writing
 * immediately before the call, which is the precise window the key exists to survive: a crash
 * between that write and the call would leave a key for a send that never happened.
 *
 * <p><b>Responsibility split</b> (spec D12). This service derives the key and sends it. Recognising
 * it and not processing the same send twice is the <i>provider's</i> obligation. Once a call has left
 * this service, whether it was processed is knowable only to the provider — the sender can make a
 * repeat recognisable, it cannot make it harmless. Against a provider that ignores the key, a
 * reclaimed delivery can duplicate and this service cannot detect it (spec G-59).
 *
 * <p>The attempt number is part of the key on purpose: a genuine retry after a genuine failure is a
 * <i>new</i> send and must not be suppressed. Only a re-attempt of the <i>same</i> attempt number
 * repeats a key.
 *
 * <p>Hashed rather than concatenated because the value travels to a third party. Identifiers are not
 * secret, but sending them gives an external system a view of internal structure for no benefit, and
 * Principle V's habit is to send a reference rather than the thing.
 */
public record IdempotencyKey(String value) {

    private static final String PREFIX = "nms-";
    private static final int DIGEST_CHARS = 32;

    public IdempotencyKey {
        Objects.requireNonNull(value, "idempotency key value must not be null");
    }

    /**
     * @param attemptNumber 1-based; attempt numbers start at 1, and a zero would mask a counting bug
     *     in the caller rather than collide with anything
     */
    public static IdempotencyKey of(
            UUID notificationId, UUID recipientId, Channel channel, int attemptNumber) {

        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "attemptNumber must be at least 1; got " + attemptNumber);
        }

        // Field separator so (a, bc) and (ab, c) cannot collide.
        String material =
                String.join(
                        "|",
                        notificationId.toString(),
                        recipientId.toString(),
                        channel.name(),
                        Integer.toString(attemptNumber));

        return new IdempotencyKey(PREFIX + digest(material));
    }

    private static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
