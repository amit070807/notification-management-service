package com.notification.fixtures;

import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.IdempotencyKey;
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

    /**
     * Records the key of every call, so a test can assert that a re-attempt of the same logical
     * attempt repeats it (feature 002 FR-161). A scripted provider is the only place that can observe
     * this: a real provider's recognition of the key is its own business (spec D12, G-59).
     */
    private final List<IdempotencyKey> keys = new CopyOnWriteArrayList<>();

    /**
     * Makes the next call throw instead of returning an outcome.
     *
     * <p>This is how a real crash is simulated once the provider call sits outside a transaction: the
     * call happens, whatever it did on the provider side stands, and this process dies before it can
     * record the result. Returning a failed outcome would test the ordinary failure path instead,
     * which is a different thing entirely.
     */
    public ScriptedChannelProvider thenThrow() {
        throwOnNextCall.set(true);
        return this;
    }

    private final java.util.concurrent.atomic.AtomicBoolean throwOnNextCall =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public DeliveryOutcome send(RecipientRef recipient, ContentRef content, IdempotencyKey key) {
        if (throwOnNextCall.getAndSet(false)) {
            calls.add(recipient);
            keys.add(key);
            throw new IllegalStateException("simulated provider/worker crash after the call was made");
        }
        calls.add(recipient);
        keys.add(key);
        DeliveryOutcome next = script.poll();
        return next == null ? fallback : next;
    }

    /** Keys seen, in call order. */
    public List<IdempotencyKey> keysSeen() {
        return List.copyOf(keys);
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
        keys.clear();
        fallback = DeliveryOutcome.succeeded();
    }
}
