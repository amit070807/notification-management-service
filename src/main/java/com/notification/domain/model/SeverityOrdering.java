package com.notification.domain.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Renders {@link Severity#rank()} as a SQL expression (ADR-027, FR-202).
 *
 * <p>Severity is stored as {@code text} and PostgreSQL cannot order by a rank it has not been told
 * (B-03). The obvious implementation is a hand-written {@code CASE} in the repository — and that would
 * be a second declaration of the rank, free to disagree with the enum. The disagreement would be
 * invisible: both places look correct in isolation and only the delivery order is wrong.
 *
 * <p>Generating the expression makes divergence impossible. A new severity constant appears here
 * automatically, carrying the rank its constructor demanded.
 *
 * <p><b>On interpolating strings into SQL.</b> Normally a defect, so the reason it is safe here is
 * recorded rather than assumed: every interpolated value is an enum constant name or an {@code int}
 * from a closed, compile-time set. None of it is caller input, and none of it can be. The only
 * caller-supplied argument is {@code severityColumn}, which is a literal written at the call site.
 *
 * <p>Lives in {@code domain} with no framework import, so the architecture gate is unaffected. It emits
 * SQL text but knows nothing about executing it.
 */
public final class SeverityOrdering {

    private SeverityOrdering() {}

    /**
     * @return a {@code CASE} mapping the stored severity text to its rank
     */
    public static String rankExpression(String severityColumn) {
        String branches =
                Arrays.stream(Severity.values())
                        .map(s -> "WHEN '" + s.name() + "' THEN " + s.rank())
                        .collect(Collectors.joining(" "));

        // ELSE 0 is a fail-safe, not dead code. Without it an unmatched value yields NULL, and NULL's
        // position under DESC depends on the NULLS FIRST/LAST default rather than on anything declared
        // here. Zero sorts an unknown severity last, deterministically. It should be unreachable, since
        // the column is only ever written from this enum.
        return "CASE " + severityColumn + " " + branches + " ELSE 0 END";
    }

    /**
     * The rank expression wrapped in the feature flag (ADR-029, FR-206).
     *
     * <p>One SQL string, not two. When the bound parameter is false the term is the constant {@code 0}
     * for every row and the sort falls through to its next key — the phase-2 baseline exactly. Two
     * separate queries chosen by a boolean would drift, and the disabled path is the one nobody reads
     * and the one that <i>is</i> the rollback.
     */
    public static String flaggedRankTerm(String severityColumn) {
        return "CASE WHEN ? THEN " + rankExpression(severityColumn) + " ELSE 0 END";
    }
}
