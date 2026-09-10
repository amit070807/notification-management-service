package com.notification.domain.model;

/**
 * Notification severity. A routing factor (source 4.3) and, from feature 003, a delivery claim
 * ordering factor (FR-201).
 *
 * <p>{@code rank} is the <b>single</b> declaration of relative severity required by FR-202. Higher
 * rank is claimed first.
 *
 * <p>It is an explicit constructor parameter rather than {@link #ordinal()} on purpose, and the reason
 * is not stylistic. The constants below happen to be declared in ascending rank order, so
 * {@code ordinal()} would give the right answer today — which is exactly what makes it dangerous.
 * Reordering them for readability, or inserting a new severity between two existing ones, would
 * silently change the order in which notifications are delivered, with no test naming the rank it
 * broke. A constructor parameter cannot be omitted for a new constant, and cannot be changed by
 * accident.
 *
 * <p>It is also not the stored string. Severity persists as {@code text}, so lexicographic order is
 * {@code CRITICAL < HIGH < LOW < MEDIUM} — a near-miss that orders the top two correctly and inverts
 * {@code MEDIUM} and {@code LOW} (spec 003 B-03, G-60). {@link SeverityOrdering} is what teaches SQL
 * this rank instead.
 */
public enum Severity {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    /**
     * @return relative severity, higher meaning claimed sooner. Only the ordering is a requirement; the
     *     specific integers are an implementation detail no caller should depend on beyond comparison.
     */
    public int rank() {
        return rank;
    }
}
