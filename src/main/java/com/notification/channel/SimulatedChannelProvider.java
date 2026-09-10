package com.notification.channel;

import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A channel provider whose behaviour comes from configuration (ADR-015).
 *
 * <p>Spec G-22: no source document states that a real provider is available, so the prototype
 * simulates them. This is a declared limitation under DO-004 — no integration with a real provider
 * is proven.
 *
 * <p>Behaviour is configured rather than carried in the request. A per-request directive would have
 * put a test affordance in the production contract and made the simulator an injection surface for
 * anyone able to submit.
 *
 * <p>US2 (ADR-022) moved the shared work — timeouts, failing closed on an unmapped code, keeping the
 * diagnostic code-shaped — into {@link AbstractChannelProvider}. What is left here is the two things
 * only this adapter knows: how to call its provider, and what its provider's codes mean.
 */
public class SimulatedChannelProvider extends AbstractChannelProvider {

    /** Every classification maps to itself. Built once — it never varies per instance. */
    private static final Map<String, FailureClassification> IDENTITY_CODES =
            Arrays.stream(FailureClassification.values())
                    .collect(Collectors.toUnmodifiableMap(Enum::name, c -> c));

    private final FailureClassification simulatedFailure;
    private final int failFirstAttempts;
    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * @param simulatedFailure null means always succeed
     * @param failFirstAttempts fail only the first N calls, then succeed; 0 means always fail while a
     *     classification is configured. Lets a demo show retry-then-success without a code change.
     */
    public SimulatedChannelProvider(
            Channel channel,
            FailureClassification simulatedFailure,
            int failFirstAttempts,
            Duration connectTimeout,
            Duration readTimeout) {
        super(channel, connectTimeout, readTimeout);
        this.simulatedFailure = simulatedFailure;
        this.failFirstAttempts = failFirstAttempts;
    }

    /**
     * A simulated provider's "codes" are the classifications themselves, so the map is the identity
     * over the taxonomy. A real adapter would map its own vocabulary — SMTP replies, HTTP statuses —
     * onto the same closed set.
     */
    @Override
    protected Map<String, FailureClassification> errorCodeMap() {
        return IDENTITY_CODES;
    }

    @Override
    protected String providerCall(RecipientRef recipient, ContentRef content, IdempotencyKey key) {
        if (simulatedFailure == null) {
            return SUCCESS;
        }
        int call = callCount.incrementAndGet();
        if (failFirstAttempts > 0 && call > failFirstAttempts) {
            return SUCCESS;
        }
        return simulatedFailure.name();
    }
}
