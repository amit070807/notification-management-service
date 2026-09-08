package com.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notification.domain.port.DeliveryOutcome;
import com.notification.domain.retry.FailureClassification;
import org.junit.jupiter.api.Test;

/**
 * The invariants that keep the failure taxonomy honest at the port boundary.
 *
 * <p>These two guards are what stop an adapter reporting an unclassified failure — which would
 * leave the retry decision undefined — or a success carrying a classification, which would make
 * "did this deliver?" ambiguous. Source 4.5 requires failures to be distinguished; that is only
 * enforceable if every failure arrives classified.
 */
class DeliveryOutcomeTest {

    @Test
    void aFailureMustBeClassified() {
        // An adapter that cannot map a provider response must use UNKNOWN (FR-041), never leave
        // the classification absent. Absent would make Retryability undecidable.
        assertThatThrownBy(() -> new DeliveryOutcome(false, null, "SOME_CODE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void aSuccessMustNotCarryAClassification() {
        assertThatThrownBy(
                        () ->
                                new DeliveryOutcome(
                                        true, FailureClassification.TRANSIENT_PROVIDER_FAILURE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void successFactoryProducesAnUnclassifiedSuccess() {
        DeliveryOutcome outcome = DeliveryOutcome.succeeded();
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.classification()).isNull();
    }

    @Test
    void failureFactoryPreservesTheClassification() {
        DeliveryOutcome outcome =
                DeliveryOutcome.failed(FailureClassification.INVALID_RECIPIENT, "SMTP_550");
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.classification()).isEqualTo(FailureClassification.INVALID_RECIPIENT);
        assertThat(outcome.diagnostic()).isEqualTo("SMTP_550");
    }

    @Test
    void aNullDiagnosticIsAcceptable() {
        // Not every provider supplies a code, and inventing one would be worse than none.
        assertThat(DeliveryOutcome.failed(FailureClassification.TIMEOUT, null).diagnostic()).isNull();
    }
}
