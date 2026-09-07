package com.notification.domain.routing;

import com.notification.domain.model.Channel;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.model.Severity;
import java.util.List;

/**
 * The complete input to channel selection.
 *
 * <p>Three factors, not four. Source 4.3 also names recipient preferences, but the document never
 * supplies them: 4.1's field list does not carry preferences and no section defines a preference
 * store (spec G-05). Implementing that factor would mean inventing both a data source and a
 * preference model, so it is deferred and recorded as G-26 — an explicit requirement this
 * iteration knowingly does not meet.
 *
 * <p>This type is the enforcement point. There is no field here through which a recipient
 * attribute could reach routing, so a preference proxy cannot be smuggled in under another name
 * (FR-019a). The recipients are carried only so an outcome can be recorded per recipient; nothing
 * about them is consulted.
 */
public record RoutingRequest(
        Severity severity, List<Channel> requestedChannels, List<RecipientRef> recipients) {}
