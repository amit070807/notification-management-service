package com.notification.domain.port;

import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.RecipientRef;

/**
 * T011 — the boundary to a delivery channel.
 *
 * <p>Every adapter implements this, including the simulated ones, so tests exercise the production
 * code path rather than a parallel one (Principle VI).
 *
 * <p>The return type is deliberately narrow. An adapter must map whatever the provider said into
 * the closed taxonomy plus a short, sanitised diagnostic — it must never hand back a raw provider
 * response body, which may echo the submitted content straight into audit (Principle V).
 */
public interface ChannelProviderPort {

    Channel channel();

    DeliveryOutcome send(RecipientRef recipient, ContentRef content);
}
