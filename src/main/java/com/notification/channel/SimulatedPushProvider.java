package com.notification.channel;

import com.notification.config.ChannelProperties;
import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The push channel adapter (FR-118, FR-131, ADR-015).
 *
 * <p>Simulated, like the others: no source document states that a real provider is available (phase-1
 * G-22), and this proves no interoperability with one. What it does prove is that the closed failure
 * taxonomy covers push's conditions and that the delivery pipeline needs no knowledge of them.
 *
 * <p>Push is the first channel with genuinely different provider semantics, which is why US1 precedes
 * US2 — and the duplication it created is what US2 then extracted into {@link
 * AbstractChannelProvider}. Two conditions email and SMS never produced:
 *
 * <ul>
 *   <li><b>Authentication</b> (FR-115). A missing credential is {@code AUTH_ERROR} — a service
 *       configuration fault, not a recipient fault, so non-retryable with the operational signal
 *       firing. Checked before anything else: treating it as a delivery failure would spread one
 *       configuration fault across every recipient's failure count.
 *   <li><b>Rate limiting</b> (FR-117). Mapped to {@code TRANSIENT_PROVIDER_FAILURE} per ADR-018
 *       rather than given its own classification, which would extend a closed enum that section 4.5
 *       fixes and the contract publishes. Per-channel retry (FR-132) is what lets push back off more
 *       patiently, and that is the substantive part of handling it.
 * </ul>
 *
 * <p><b>Not implemented and not implementable here</b>: resolving the recipient reference to a device
 * token. Push makes phase-1 G-32 unavoidable — a token has no source but the platform that issues one,
 * and no source document supplies it (spec G-49). This adapter accepts the opaque reference; a real
 * one would not.
 *
 * <p>The credential is never interpolated into a diagnostic. The base type's sanitiser would catch
 * prose, but the safer habit is to keep a secret away from that field entirely (FR-116).
 */
public class SimulatedPushProvider extends AbstractChannelProvider {

    /** The code this adapter emits when it has no usable credential. */
    static final String AUTH_MISSING = "AUTH_MISSING";

    private static final Map<String, FailureClassification> CODES = buildCodes();

    private final ChannelProperties.Credential credential;
    private final FailureClassification simulatedFailure;
    private final int failFirstAttempts;
    private final AtomicInteger callCount = new AtomicInteger();

    public SimulatedPushProvider(
            ChannelProperties.Credential credential,
            FailureClassification simulatedFailure,
            int failFirstAttempts,
            Duration connectTimeout,
            Duration readTimeout) {
        super(Channel.PUSH, connectTimeout, readTimeout);
        this.credential = credential == null ? new ChannelProperties.Credential(null, null) : credential;
        this.simulatedFailure = simulatedFailure;
        this.failFirstAttempts = failFirstAttempts;
    }

    @Override
    protected Map<String, FailureClassification> errorCodeMap() {
        return CODES;
    }

    @Override
    protected String providerCall(RecipientRef recipient, ContentRef content, IdempotencyKey key) {
        // Authentication first. An unauthenticated adapter has no business reporting a per-recipient
        // outcome at all.
        if (!credential.isPresent()) {
            return AUTH_MISSING;
        }
        if (simulatedFailure == null) {
            return SUCCESS;
        }
        int call = callCount.incrementAndGet();
        if (failFirstAttempts > 0 && call > failFirstAttempts) {
            return SUCCESS;
        }
        return simulatedFailure.name();
    }

    /** Push's own vocabulary, plus the simulated classifications, all onto the closed taxonomy. */
    private static Map<String, FailureClassification> buildCodes() {
        Map<String, FailureClassification> codes =
                Arrays.stream(FailureClassification.values())
                        .collect(Collectors.toMap(Enum::name, c -> c, (a, b) -> a, java.util.LinkedHashMap::new));
        codes.put(AUTH_MISSING, FailureClassification.AUTH_ERROR);
        return Map.copyOf(codes);
    }
}
