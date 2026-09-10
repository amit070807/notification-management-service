package com.notification.channel;

import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.port.ChannelProviderPort;
import com.notification.domain.port.DeliveryOutcome;
import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * T031 — the shared adapter behaviour push duplicated (FR-130, ADR-022).
 *
 * <p>Before push there was one adapter and nothing to extract. Adding a second copied the timeout
 * fields, the call counting, the fail-first logic and the outcome construction, which is the
 * duplication Option 3 asks to remove. Extracting it earlier would have meant generalising from a
 * single example.
 *
 * <p><b>The division of labour is the point.</b> A subclass supplies exactly two things: the provider
 * call, and the map from that provider's own error vocabulary to the closed taxonomy. Everything that
 * could be got wrong consistently — failing closed on an unrecognised code, keeping the diagnostic
 * code-shaped so it survives redaction, bounding the call — lives here once.
 *
 * <p>Two rules enforced rather than documented:
 *
 * <ul>
 *   <li><b>No unmapped pass-through</b> (FR-131). An unrecognised code becomes {@code UNKNOWN}, which
 *       is retryable but never success (FR-041). An adapter meeting a code its author never saw fails
 *       closed instead of guessing.
 *   <li><b>No empty code map.</b> An empty map would make every outcome {@code UNKNOWN} — technically
 *       failing closed, but it means the mapping work was never done, and the system would retry
 *       permanent rejections forever within their bound while looking healthy.
 * </ul>
 *
 * <p>The adapter declares its own {@link #channel()}, so wiring no longer decides which adapter serves
 * which channel. That removes the {@code if (channel == Channel.PUSH)} branch a fourth channel would
 * otherwise extend.
 */
public abstract class AbstractChannelProvider implements ChannelProviderPort {

    /** The code a provider call returns when the send succeeded. */
    public static final String SUCCESS = "OK";

    private final Channel channel;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    protected AbstractChannelProvider(Channel channel, Duration connectTimeout, Duration readTimeout) {
        this.channel = Objects.requireNonNull(channel, "an adapter must declare its channel");
        // Constitution Observability: an unbounded provider call is a defect, and without a timeout
        // the TIMEOUT classification of section 4.5 could never be produced at all.
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        this.readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    @Override
    public final Channel channel() {
        return channel;
    }

    /**
     * @return this provider's error vocabulary mapped onto the closed taxonomy. Must be non-empty.
     */
    protected abstract Map<String, FailureClassification> errorCodeMap();

    /**
     * Performs the send.
     *
     * @param key passed through to the provider so a repeat is recognisable (FR-161)
     * @return {@link #SUCCESS}, or a provider-specific code present in {@link #errorCodeMap()}
     */
    protected abstract String providerCall(
            RecipientRef recipient, ContentRef content, IdempotencyKey key);

    @Override
    public final DeliveryOutcome send(RecipientRef recipient, ContentRef content, IdempotencyKey key) {
        Map<String, FailureClassification> codes = errorCodeMap();
        if (codes == null || codes.isEmpty()) {
            throw new IllegalStateException(
                    ("Channel provider for %s declares no error code map. FR-131 requires every provider"
                                    + " outcome to map into the closed taxonomy; an empty map would silently"
                                    + " classify everything as UNKNOWN and retry permanent rejections.")
                            .formatted(channel));
        }

        java.util.Objects.requireNonNull(
                key,
                "an idempotency key is required on every provider call (FR-161); without it a"
                        + " reclaimed delivery cannot be recognised by the provider as a repeat");

        String code = providerCall(recipient, content, key);
        if (SUCCESS.equals(code)) {
            return DeliveryOutcome.succeeded();
        }

        FailureClassification classification = codes.get(code);
        if (classification == null) {
            // Fails closed. The diagnostic names the channel rather than the unrecognised code,
            // because a code from an unfamiliar provider is exactly the kind of text that could be
            // prose — and prose would be redacted, losing the signal entirely.
            return DeliveryOutcome.failed(FailureClassification.UNKNOWN, sanitise(channel + "_UNMAPPED"));
        }
        return DeliveryOutcome.failed(classification, sanitise(channel + "_" + code));
    }

    /**
     * Keeps the diagnostic code-shaped. {@code DeliveryOutcome} replaces anything else with
     * {@code REDACTED_UNSAFE_DIAGNOSTIC}, so a base type that built a sentence would silently lose
     * every diagnostic in the system.
     */
    private static String sanitise(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return cleaned.length() <= 32 ? cleaned : cleaned.substring(0, 32);
    }

    public final Duration connectTimeout() {
        return connectTimeout;
    }

    public final Duration readTimeout() {
        return readTimeout;
    }

    /** Exposed for the mapping test, which must assert every declared code resolves to the taxonomy. */
    public final Map<String, FailureClassification> errorCodeMapForTest() {
        return errorCodeMap();
    }
}
