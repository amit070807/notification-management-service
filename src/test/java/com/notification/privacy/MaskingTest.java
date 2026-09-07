package com.notification.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.audit.Masking;
import org.junit.jupiter.api.Test;

/** Covers the masking scheme chosen in ADR-012 (U-4). */
class MaskingTest {

    @Test
    void masksToPrefixPlusDigest() {
        String masked = Masking.mask("user-12345");
        assertThat(masked).startsWith("us…").hasSize(2 + 1 + 8);
        assertThat(masked).doesNotContain("12345");
    }

    @Test
    void isStableSoRecordsStayCorrelatable() {
        // The reason for keeping a digest at all: the same recipient must be recognisable across
        // notifications when reading audit history.
        assertThat(Masking.mask("user-1")).isEqualTo(Masking.mask("user-1"));
    }

    @Test
    void differentReferencesMaskDifferently() {
        assertThat(Masking.mask("user-1")).isNotEqualTo(Masking.mask("user-2"));
    }

    @Test
    void handlesShortAndBlankValues() {
        assertThat(Masking.mask("a")).startsWith("a…");
        assertThat(Masking.mask("")).isEqualTo("«blank»");
        assertThat(Masking.mask(null)).isEqualTo("«blank»");
    }

    @Test
    void recipientRefToStringDoesNotLeak() {
        // Defence in depth: a stray toString() in a log statement must not expose the reference.
        var ref = new com.notification.domain.model.RecipientRef("user-secret-value");
        assertThat(ref.toString()).doesNotContain("user-secret-value");
    }
}
