package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Severity;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * T001-T003 — the severity rank, declared once (FR-202, SC-202).
 *
 * <p>Every pair is asserted, not just the extremes. Severity is stored as {@code text} and the enum
 * published no rank (B-03), so the obvious wrong implementation — ordering on the stored string —
 * yields {@code CRITICAL, HIGH, LOW, MEDIUM} under {@code ASC}. The first two are right and the last
 * two are <b>inverted</b>. A test phrased as "CRITICAL comes before LOW" passes against that
 * implementation while it delivers MEDIUM after LOW forever. That is gap G-60, and the pairwise table
 * below is the whole guard against it.
 */
class SeverityRankTest {

    @Test
    void everyPairOrdersCorrectly() {
        // Six ordered pairs over four values. Written out rather than generated from a sorted list,
        // because generating them from an ordering derived from rank would assert rank against itself.
        assertOutranks(Severity.CRITICAL, Severity.HIGH);
        assertOutranks(Severity.CRITICAL, Severity.MEDIUM);
        assertOutranks(Severity.CRITICAL, Severity.LOW);
        assertOutranks(Severity.HIGH, Severity.MEDIUM);
        assertOutranks(Severity.HIGH, Severity.LOW);

        // The pair lexicographic ordering inverts. If only one assertion in this file survives, this
        // is the one that catches the near-miss.
        assertOutranks(Severity.MEDIUM, Severity.LOW);
    }

    @Test
    void theRankIsTotal() {
        // A lookup map could be missing an entry; a constructor parameter cannot. Asserting it anyway,
        // because "cannot happen" is how a zero rank would reach production unnoticed.
        assertThat(Arrays.stream(Severity.values()).map(Severity::rank)).allSatisfy(r -> assertThat(r).isPositive());
    }

    @Test
    void theRankIsDistinctPerSeverity() {
        // A duplicated rank makes two severities tie and fall through to age ordering. Every
        // extremes-only test still passes, and two severities silently stop being distinguishable.
        assertThat(Arrays.stream(Severity.values()).map(Severity::rank).collect(Collectors.toSet()))
                .hasSize(Severity.values().length);
    }

    @Test
    void theRankIsNotDeclarationOrder() {
        // FR-202 forbids ordinal(). The declaration order happens to agree today, which is exactly what
        // makes it a trap: reordering the constants for readability would silently change delivery
        // order with no test naming the rank it broke. Asserting the values pins the rank to an
        // explicit decision rather than to a position in a file.
        assertThat(Severity.CRITICAL.rank()).isEqualTo(4);
        assertThat(Severity.HIGH.rank()).isEqualTo(3);
        assertThat(Severity.MEDIUM.rank()).isEqualTo(2);
        assertThat(Severity.LOW.rank()).isEqualTo(1);

        assertThat(Severity.LOW.rank()).isNotEqualTo(Severity.LOW.ordinal());
    }

    private static void assertOutranks(Severity higher, Severity lower) {
        assertThat(higher.rank())
                .as("%s must outrank %s", higher, lower)
                .isGreaterThan(lower.rank());
    }
}
