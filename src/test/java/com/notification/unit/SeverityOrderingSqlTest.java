package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.domain.model.Severity;
import com.notification.domain.model.SeverityOrdering;
import org.junit.jupiter.api.Test;

/**
 * T004, T005 — the SQL rank expression is generated from the enum (ADR-027, FR-202).
 *
 * <p>A hand-written {@code CASE} in the repository would be a second declaration of the rank, able to
 * disagree with the enum invisibly: delivery order would simply be wrong, with both places looking
 * correct in isolation. Generating it means the two cannot diverge, and these tests are what make a
 * <i>partial</i> generation fail here rather than silently rank a severity zero.
 */
class SeverityOrderingSqlTest {

    @Test
    void everyConstantAppearsWithItsRank() {
        String sql = SeverityOrdering.rankExpression("n.severity");

        for (Severity severity : Severity.values()) {
            assertThat(sql)
                    .as("%s must appear in the generated ordering", severity)
                    .contains("'" + severity.name() + "'")
                    .contains("WHEN '" + severity.name() + "' THEN " + severity.rank());
        }
    }

    @Test
    void theExpressionEndsInAFailSafeElse() {
        // Without ELSE, an unmatched value yields NULL, whose position under DESC depends on the
        // NULLS FIRST/LAST default rather than on anything this feature declares. ELSE 0 sorts an
        // unknown severity last, deterministically. It should be unreachable — the column is written
        // from the enum — which is why it is asserted rather than trusted.
        assertThat(SeverityOrdering.rankExpression("n.severity")).contains("ELSE 0");
    }

    @Test
    void theColumnReferenceIsUsedVerbatim() {
        assertThat(SeverityOrdering.rankExpression("x.sev")).contains("CASE x.sev");
    }

    @Test
    void theFlaggedTermCollapsesToAConstantWhenDisabled() {
        // ADR-029: one query, not two. The flag is a term inside the sort, so the disabled path is the
        // same SQL with a constant leading key — it cannot drift from the enabled path.
        String term = SeverityOrdering.flaggedRankTerm("n.severity");
        assertThat(term).startsWith("CASE WHEN").contains("ELSE 0 END");
        assertThat(term).contains(SeverityOrdering.rankExpression("n.severity"));
    }
}
