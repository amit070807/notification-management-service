package com.notification.channel;

import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.port.ChannelProviderPort;
import com.notification.domain.port.DeliveryOutcome;
import com.notification.domain.retry.FailureClassification;

/**
 * T074 (ADR-015, resolving U-5) — a channel provider whose behaviour comes from configuration.
 *
 * <p>Spec G-22: the source never states that real providers are available, so the prototype
 * simulates them. This is a declared limitation under DO-004 — no integration with a real provider
 * is proven.
 *
 * <p>Behaviour is configured rather than carried in the request. A per-request directive would
 * have put a test affordance in the production contract and made the simulator an injection
 * surface for anyone able to submit.
 *
 * <p>Two things this class does that a real adapter must also do:
 *
 * <ul>
 *   <li>Map every outcome into the closed taxonomy. There is no pass-through path (FR-037).
 *   <li>Return a short, sanitised diagnostic — never a provider response body, which may echo the
 *       submitted content straight into audit (Principle V).
 * </ul>
 */
public class SimulatedChannelProvider implements ChannelProviderPort {

    private final Channel channel;
    private final FailureClassification simulatedFailure;
    private final int failFirstAttempts;
    private final java.util.concurrent.atomic.AtomicInteger callCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * @param simulatedFailure null means always succeed
     * @param failFirstAttempts fail only the first N calls, then succeed; 0 means always fail when
     *     a classification is configured. Lets a demo show retry-then-success without code changes.
     */
    public SimulatedChannelProvider(Channel channel, FailureClassification simulatedFailure, int failFirstAttempts) {
        this.channel = channel;
        this.simulatedFailure = simulatedFailure;
        this.failFirstAttempts = failFirstAttempts;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public DeliveryOutcome send(RecipientRef recipient, ContentRef content) {
        if (simulatedFailure == null) {
            return DeliveryOutcome.succeeded();
        }
        int call = callCount.incrementAndGet();
        if (failFirstAttempts > 0 && call > failFirstAttempts) {
            return DeliveryOutcome.succeeded();
        }
        // The diagnostic names the classification and the channel only. It deliberately does not
        // interpolate the recipient or anything derived from the content.
        return DeliveryOutcome.failed(simulatedFailure, "simulated %s on %s".formatted(simulatedFailure, channel));
    }
}
