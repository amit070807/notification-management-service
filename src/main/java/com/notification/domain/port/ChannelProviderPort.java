package com.notification.domain.port;

import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.model.RecipientRef;

/**
 * T011 — the boundary to a delivery channel.
 *
 * <p>Every adapter implements this, including the simulated ones, so tests exercise the production
 * code path rather than a parallel one (Principle VI).
 *
 * <p>The key parameter carries the feature-002 D12 agreement: this service derives a value stable
 * across re-attempts of the same attempt, and the provider recognises it. Once a call has left this
 * service, whether it was processed is knowable only to the provider — the sender can make a repeat
 * recognisable, it cannot make it harmless.
 *
 * <p>The return type is deliberately narrow. An adapter must map whatever the provider said into
 * the closed taxonomy plus a short, sanitised diagnostic — it must never hand back a raw provider
 * response body, which may echo the submitted content straight into audit (Principle V).
 */
public interface ChannelProviderPort {

    Channel channel();

    /**
     * @param key stable across re-attempts of the same logical attempt (feature 002 FR-161). A
     *     parameter rather than an ambient value so a new adapter cannot silently omit it: this is
     *     the worker's half of the D12 agreement, and the provider's half — recognising the key and
     *     not processing the same send twice — is assumed, not enforceable here (spec G-59).
     */
    DeliveryOutcome send(RecipientRef recipient, ContentRef content, IdempotencyKey key);
}
