package com.notification.config;

import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Channel configuration, including the simulated-failure controls from ADR-015.
 *
 * <p>{@code connectTimeout} and {@code readTimeout} are not optional garnish: the constitution
 * requires every outbound provider call to be bounded, because without a timeout the
 * {@code TIMEOUT} classification of source 4.5 could never be produced at all.
 */
@ConfigurationProperties(prefix = "notification.channel")
public record ChannelProperties(
        Duration connectTimeout,
        Duration readTimeout,
        Map<String, Simulation> simulate,
        Map<String, Credential> credentials) {

    public ChannelProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        simulate = simulate == null ? Map.of() : simulate;
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }

    /**
     * T021 — per-channel provider credentials (FR-115, FR-116).
     *
     * <p>Source 4.3's Option 1 requires push provider authentication but names no scheme, provider or
     * credential source (spec G-37). The assumption recorded there is that credentials arrive from
     * the environment or a secret manager, as any other secret does.
     *
     * <p>{@link #toString()} is overridden rather than left to the record default. A credential
     * record reaching a log line through an interpolated object is one of the easiest ways to leak a
     * secret, and it is not caught by a test that only inspects audit rows (Principle V, FR-116).
     */
    public record Credential(String tokenRef, String token) {

        /** @return true when a usable credential is configured. */
        public boolean isPresent() {
            return token != null && !token.isBlank();
        }

        @Override
        public String toString() {
            // Never the value. tokenRef is a pointer, not a secret, so it stays visible for
            // diagnosing which credential was selected.
            return "Credential[tokenRef=%s, token=%s]"
                    .formatted(tokenRef, token == null ? "«unset»" : "«redacted»");
        }
    }

    /** @return the credential for a channel, or an empty one if none is configured. */
    public Credential credentialFor(String channel) {
        return credentials.getOrDefault(channel, new Credential(null, null));
    }

    /**
     * @param failWith null means the channel always succeeds
     * @param failFirstAttempts fail only the first N attempts, then succeed
     */
    public record Simulation(FailureClassification failWith, Integer failFirstAttempts) {
        public int firstAttempts() {
            return failFirstAttempts == null ? 0 : failFirstAttempts;
        }
    }
}
