package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.channel.AbstractChannelProvider;
import com.notification.domain.model.Channel;
import com.notification.domain.model.ContentRef;
import com.notification.domain.model.IdempotencyKey;
import com.notification.domain.model.RecipientRef;
import com.notification.domain.port.DeliveryOutcome;
import com.notification.domain.retry.FailureClassification;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T028 — providers with different vocabularies must agree on meaning (FR-131, SC-107).
 *
 * <p>The failure this guards is subtle: two providers describe the same condition with different
 * codes, each maps it plausibly, and they end up on opposite sides of the retryability partition. One
 * channel then retries a permanent rejection while another gives up on a transient blip, and both
 * look correct in isolation.
 *
 * <p>The base type exists to make the mapping the only thing an adapter supplies, so it is the only
 * thing that can be got wrong.
 */
class ProviderErrorMappingTest {

    private static final RecipientRef RECIPIENT = new RecipientRef("r");
    private static final ContentRef CONTENT = new ContentRef(UUID.randomUUID(), "sha256:abc", 3);
    private static final IdempotencyKey KEY =
            IdempotencyKey.of(UUID.randomUUID(), UUID.randomUUID(), Channel.EMAIL, 1);

    /** Two providers, same conditions, entirely different code vocabularies. */
    private static final class ProviderA extends AbstractChannelProvider {
        private final String code;

        ProviderA(String code) {
            super(Channel.EMAIL, Duration.ofSeconds(5), Duration.ofSeconds(10));
            this.code = code;
        }

        @Override
        protected Map<String, FailureClassification> errorCodeMap() {
            return Map.of(
                    "550", FailureClassification.INVALID_RECIPIENT,
                    "421", FailureClassification.TRANSIENT_PROVIDER_FAILURE,
                    "535", FailureClassification.AUTH_ERROR);
        }

        @Override
        protected String providerCall(RecipientRef recipient, ContentRef content, IdempotencyKey key) {
            return code;
        }
    }

    private static final class ProviderB extends AbstractChannelProvider {
        private final String code;

        ProviderB(String code) {
            super(Channel.SMS, Duration.ofSeconds(5), Duration.ofSeconds(10));
            this.code = code;
        }

        @Override
        protected Map<String, FailureClassification> errorCodeMap() {
            return Map.of(
                    "BAD_NUMBER", FailureClassification.INVALID_RECIPIENT,
                    "THROTTLED", FailureClassification.TRANSIENT_PROVIDER_FAILURE,
                    "UNAUTHORIZED", FailureClassification.AUTH_ERROR);
        }

        @Override
        protected String providerCall(RecipientRef recipient, ContentRef content, IdempotencyKey key) {
            return code;
        }
    }

    @Test
    void anUnroutableRecipientClassifiesIdenticallyAcrossProviders() {
        assertThat(new ProviderA("550").send(RECIPIENT, CONTENT, KEY).classification())
                .isEqualTo(new ProviderB("BAD_NUMBER").send(RECIPIENT, CONTENT, KEY).classification())
                .isEqualTo(FailureClassification.INVALID_RECIPIENT);
    }

    @Test
    void throttlingClassifiesIdenticallyAcrossProviders() {
        assertThat(new ProviderA("421").send(RECIPIENT, CONTENT, KEY).classification())
                .isEqualTo(new ProviderB("THROTTLED").send(RECIPIENT, CONTENT, KEY).classification())
                .isEqualTo(FailureClassification.TRANSIENT_PROVIDER_FAILURE);
    }

    @Test
    void authFailureClassifiesIdenticallyAcrossProviders() {
        assertThat(new ProviderA("535").send(RECIPIENT, CONTENT, KEY).classification())
                .isEqualTo(new ProviderB("UNAUTHORIZED").send(RECIPIENT, CONTENT, KEY).classification())
                .isEqualTo(FailureClassification.AUTH_ERROR);
    }

    @Test
    void anUnrecognisedCodeBecomesUnknownRatherThanPassingThrough() {
        // FR-131 forbids unmapped pass-through, and FR-041 requires failing closed. An adapter that
        // met a code its author never saw must not report success and must not invent a value.
        DeliveryOutcome outcome = new ProviderA("999-NEVER-SEEN").send(RECIPIENT, CONTENT, KEY);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.classification()).isEqualTo(FailureClassification.UNKNOWN);
    }

    @Test
    void aSuccessCodeYieldsSuccessWithNoClassification() {
        assertThat(new ProviderA(AbstractChannelProvider.SUCCESS).send(RECIPIENT, CONTENT, KEY).success()).isTrue();
        assertThat(new ProviderA(AbstractChannelProvider.SUCCESS).send(RECIPIENT, CONTENT, KEY).classification())
                .isNull();
    }

    @Test
    void theDiagnosticIsCodeShapedSoItSurvivesRedaction() {
        // DeliveryOutcome replaces prose with REDACTED_UNSAFE_DIAGNOSTIC. A base type that built a
        // sentence would silently lose every diagnostic in the system.
        DeliveryOutcome outcome = new ProviderA("550").send(RECIPIENT, CONTENT, KEY);
        assertThat(outcome.diagnostic()).isNotEqualTo("REDACTED_UNSAFE_DIAGNOSTIC");
        assertThat(outcome.diagnostic()).matches("^[A-Za-z0-9_.:-]{1,32}$");
    }

    @Test
    void everyMappedCodeResolvesToAMemberOfTheClosedTaxonomy() {
        for (var entry : new ProviderA("x").errorCodeMapForTest().entrySet()) {
            assertThat(entry.getValue()).isIn((Object[]) FailureClassification.values());
        }
    }

    @Test
    void aProviderMayNotDeclareAnEmptyCodeMap() {
        // An empty map means every outcome becomes UNKNOWN, which is failing closed but also means
        // the adapter author never did the mapping work FR-131 requires.
        assertThatThrownBy(
                        () ->
                                new AbstractChannelProvider(
                                        Channel.EMAIL, Duration.ofSeconds(1), Duration.ofSeconds(1)) {
                                    @Override
                                    protected Map<String, FailureClassification> errorCodeMap() {
                                        return Map.of();
                                    }

                                    @Override
                                    protected String providerCall(RecipientRef r, ContentRef c, IdempotencyKey k) {
                                        return "x";
                                    }
                                }.send(RECIPIENT, CONTENT, KEY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutsAreCarriedByTheBaseTypeRatherThanRepeatedPerAdapter() {
        ProviderA a = new ProviderA("x");
        assertThat(a.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(a.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void anAdapterDeclaresItsOwnChannel() {
        // Removes the channel branch from wiring: configuration no longer decides which adapter
        // serves which channel.
        assertThat(new ProviderA("x").channel()).isEqualTo(Channel.EMAIL);
        assertThat(new ProviderB("x").channel()).isEqualTo(Channel.SMS);
    }
}
