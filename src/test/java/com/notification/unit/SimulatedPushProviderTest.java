package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.channel.SimulatedPushProvider;
import com.notification.config.ChannelProperties;
import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.port.DeliveryOutcome;
import com.notification.domain.retry.FailureClassification;
import com.notification.domain.retry.Retryability;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T017/T018 at unit level — the two conditions push introduces that email and SMS never produced
 * (FR-115, FR-117, ADR-018).
 *
 * <p>Kept as a unit test as well as an integration one deliberately. The mapping from a provider
 * condition to the closed taxonomy is the part most likely to be got wrong and the part that needs
 * no database to check; asserting it here means a wrong classification fails in seconds rather than
 * only in a container run.
 */
class SimulatedPushProviderTest {

    private static final RecipientRef RECIPIENT = new RecipientRef("device-abc");
    private static final ContentRef CONTENT = new ContentRef(UUID.randomUUID(), "sha256:deadbeef", 12);
    private static final IdempotencyKey KEY =
            IdempotencyKey.of(UUID.randomUUID(), UUID.randomUUID(), Channel.PUSH, 1);

    // The idempotency key is not a parameter yet: the port signature changes in phase 5 (T042),
    // when every adapter and its tests move together. Adding it here would fork the port.
    private static SimulatedPushProvider provider(ChannelProperties.Credential credential) {
        return new SimulatedPushProvider(credential, null, 0, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private static SimulatedPushProvider provider(
            ChannelProperties.Credential credential, FailureClassification simulated) {
        return new SimulatedPushProvider(credential, simulated, 0, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private static final ChannelProperties.Credential CONFIGURED =
            new ChannelProperties.Credential("env:PUSH_TOKEN", "a-token-value");
    private static final ChannelProperties.Credential MISSING =
            new ChannelProperties.Credential("env:PUSH_TOKEN", null);

    @Test
    void itServesThePushChannel() {
        assertThat(provider(CONFIGURED).channel()).isEqualTo(Channel.PUSH);
    }

    @Test
    void aMissingCredentialIsAnAuthErrorAndIsTerminal() {
        // FR-115. This is a service configuration fault, not a recipient fault: retrying cannot
        // succeed until configuration changes, so the taxonomy must place it on the non-retryable
        // side and the operational signal must fire.
        DeliveryOutcome outcome = provider(MISSING).send(RECIPIENT, CONTENT, KEY);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.classification()).isEqualTo(FailureClassification.AUTH_ERROR);
        assertThat(Retryability.isRetryable(outcome.classification())).isFalse();
        assertThat(Retryability.raisesOperationalSignal(outcome.classification())).isTrue();
    }

    @Test
    void aConfiguredCredentialDelivers() {
        DeliveryOutcome outcome = provider(CONFIGURED).send(RECIPIENT, CONTENT, KEY);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.classification()).isNull();
    }

    @Test
    void aRateLimitIsTransientAndRetryable() {
        // ADR-018: a rate limit IS transient, so the existing partition already gives the right
        // behaviour. Adding a RATE_LIMITED value would have extended a closed enum that section 4.5
        // fixes and the contract publishes, for no behavioural gain.
        DeliveryOutcome outcome =
                provider(CONFIGURED, FailureClassification.TRANSIENT_PROVIDER_FAILURE)
                        .send(RECIPIENT, CONTENT, KEY);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.classification()).isEqualTo(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        assertThat(Retryability.isRetryable(outcome.classification())).isTrue();
    }

    @Test
    void everySimulatableConditionMapsIntoTheClosedTaxonomy() {
        // FR-131: no unmapped pass-through. An adapter that could return a classification outside
        // the enum would make Retryability undecidable.
        for (FailureClassification classification : FailureClassification.values()) {
            DeliveryOutcome outcome = provider(CONFIGURED, classification).send(RECIPIENT, CONTENT, KEY);
            assertThat(outcome.classification()).isIn((Object[]) FailureClassification.values());
        }
    }

    @Test
    void theCredentialNeverAppearsInTheOutcome() {
        // FR-116. The diagnostic is the one field that travels back into audit and logs.
        DeliveryOutcome authFailure = provider(MISSING).send(RECIPIENT, CONTENT, KEY);
        DeliveryOutcome transientFailure =
                provider(CONFIGURED, FailureClassification.TIMEOUT).send(RECIPIENT, CONTENT, KEY);

        for (DeliveryOutcome outcome : java.util.List.of(authFailure, transientFailure)) {
            String diagnostic = outcome.diagnostic() == null ? "" : outcome.diagnostic();
            assertThat(diagnostic).doesNotContain("a-token-value");
        }
    }

    @Test
    void theCredentialToStringIsRedacted() {
        // A credential record interpolated into a log line is one of the easiest ways to leak a
        // secret, and it is not caught by a test that only inspects audit rows.
        assertThat(CONFIGURED.toString()).doesNotContain("a-token-value").contains("«redacted»");
        assertThat(MISSING.toString()).contains("«unset»");
    }

    @Test
    void theDiagnosticSurvivesRedactionMeaningItIsCodeShaped() {
        // DeliveryOutcome replaces prose with REDACTED_UNSAFE_DIAGNOSTIC. If this adapter's
        // diagnostic came back redacted it would mean it was emitting prose, which the phase-1
        // privacy work forbids.
        DeliveryOutcome outcome = provider(MISSING).send(RECIPIENT, CONTENT, KEY);
        assertThat(outcome.diagnostic()).isNotEqualTo("REDACTED_UNSAFE_DIAGNOSTIC");
    }

    @Test
    void timeoutsAreCarriedSoTheTimeoutClassificationIsProducible() {
        // Constitution Observability: an unbounded provider call is a defect, and without a timeout
        // the TIMEOUT classification of section 4.5 could never arise at all.
        SimulatedPushProvider p = provider(CONFIGURED);
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
