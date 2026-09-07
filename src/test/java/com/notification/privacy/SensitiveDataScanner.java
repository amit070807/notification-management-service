package com.notification.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * T029 — the Principle V gate.
 *
 * <p>The constitution requires a test asserting that a full end-to-end run, including a failure
 * and a retry, produces audit records and log output containing no forbidden value — and makes it
 * a merge blocker.
 *
 * <p>The approach is a planted marker: the test submits content containing a string that appears
 * nowhere else, runs the lifecycle, then scans every observable surface for it. A marker is used
 * rather than pattern-matching for emails or phone numbers because it cannot produce a false
 * negative through a pattern gap — if the content leaked anywhere, the marker is there.
 */
public final class SensitiveDataScanner {

    private final List<String> forbidden = new ArrayList<>();
    private final List<String> surfaces = new ArrayList<>();

    private SensitiveDataScanner() {}

    public static SensitiveDataScanner create() {
        return new SensitiveDataScanner();
    }

    /** A value that must appear on no scanned surface — content marker, credential, raw reference. */
    public SensitiveDataScanner forbidding(String value) {
        if (value != null && !value.isBlank()) {
            forbidden.add(value);
        }
        return this;
    }

    /** A surface to scan: serialised audit rows, captured log output, metric label dumps. */
    public SensitiveDataScanner scanning(String surfaceContent) {
        if (surfaceContent != null) {
            surfaces.add(surfaceContent);
        }
        return this;
    }

    /** Fails with the offending value and surface named, so a leak is diagnosable, not just red. */
    public void assertNothingLeaked() {
        assertThat(forbidden).as("the scan must have something to look for").isNotEmpty();
        assertThat(surfaces).as("the scan must have something to look at").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (String secret : forbidden) {
            for (int i = 0; i < surfaces.size(); i++) {
                String surface = surfaces.get(i);
                if (surface.contains(secret)
                        || surface.toLowerCase(Locale.ROOT).contains(secret.toLowerCase(Locale.ROOT))) {
                    violations.add(
                            "value '%s' leaked into surface #%d near: %s"
                                    .formatted(secret, i, excerpt(surface, secret)));
                }
            }
        }
        assertThat(violations)
                .as(
                        "Principle V: no content payload, credential or unmasked recipient reference "
                                + "may appear in audit records, logs or metric labels")
                .isEmpty();
    }

    private static String excerpt(String surface, String secret) {
        int at = surface.toLowerCase(Locale.ROOT).indexOf(secret.toLowerCase(Locale.ROOT));
        int from = Math.max(0, at - 60);
        int to = Math.min(surface.length(), at + secret.length() + 60);
        return "…" + surface.substring(from, to).replaceAll("\\s+", " ") + "…";
    }
}
