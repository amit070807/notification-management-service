package com.notification.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * T023 (ADR-012, resolves U-4) — masks the recipient reference for audit, logs and metric labels.
 *
 * <p>FR-052 requires masked or indirect recording without specifying a scheme. This one keeps at
 * most two leading characters plus the first 8 hex characters of the SHA-256 digest:
 * {@code us…4f3a9c21}.
 *
 * <p>Two properties were wanted: records for the same recipient stay correlatable across
 * notifications, and an operator reading audit can recognise a recipient while debugging.
 *
 * <p><b>This is not anonymisation and must not be described as such.</b> A small amount of
 * plaintext survives, and a short digest of a low-entropy identifier is open to a dictionary
 * attack by anyone who can guess the identifier space. It satisfies FR-052's minimisation intent;
 * it is not a privacy guarantee.
 */
public final class Masking {

    private static final int PLAINTEXT_PREFIX = 2;
    private static final int DIGEST_CHARS = 8;

    private Masking() {}

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "«blank»";
        }
        String prefix = value.length() <= PLAINTEXT_PREFIX ? value : value.substring(0, PLAINTEXT_PREFIX);
        return prefix + "…" + digest(value);
    }

    private static String digest(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
