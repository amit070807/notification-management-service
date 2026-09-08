package com.notification.fixtures;

import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.port.ChannelProviderPort;
import com.notification.domain.port.DeliveryOutcome;
import com.notification.domain.retry.FailureClassification;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A programmable provider for tests, implementing the SAME port as the production adapters.
 *
 * <p>Principle VI requires test doubles to go through the production code path, so the failure
 * taxonomy is exercised where it actually lives rather than in a parallel implementation.
 *
 * <p>Outcomes are queued: the script says what the Nth attempt does. That is what makes
 * "transient failure, then success on retry" expressible without touching the clock or sleeping.
 */
public final class ScriptedChannelProvider implements ChannelProviderPort {

    private final Channel channel;
    private final Deque<DeliveryOutcome> script = new ArrayDeque<>();
    private final List<RecipientRef> calls = new CopyOnWriteArrayList<>();
    private DeliveryOutcome fallback = DeliveryOutcome.succeeded();

    public ScriptedChannelProvider(Channel channel) {
        this.channel = channel;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public DeliveryOutcome send(RecipientRef recipient, ContentRef content) {
        calls.add(recipient);
        DeliveryOutcome next = script.poll();
        return next == null ? fallback : next;
    }

    public ScriptedChannelProvider thenSucceed() {
        script.add(DeliveryOutcome.succeeded());
        return this;
    }

    public ScriptedChannelProvider thenFail(FailureClassification classification) {
        script.add(DeliveryOutcome.failed(classification, "SIM_" + classification));
        return this;
    }

    /** Emits a diagnostic that echoes caller content, to prove adapters do not propagate it. */
    public ScriptedChannelProvider thenFailEchoingContent(String contentEcho) {
        script.add(DeliveryOutcome.failed(FailureClassification.TRANSIENT_PROVIDER_FAILURE, contentEcho));
        return this;
    }

    public ScriptedChannelProvider alwaysFail(FailureClassification classification) {
        fallback = DeliveryOutcome.failed(classification, "SIM_" + classification);
        return this;
    }

    public ScriptedChannelProvider alwaysSucceed() {
        fallback = DeliveryOutcome.succeeded();
        return this;
    }

    public int callCount() {
        return calls.size();
    }

    public void reset() {
        script.clear();
        calls.clear();
        fallback = DeliveryOutcome.succeeded();
    }
}
