package com.notification.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.audit.payload.AuditPayload;
import com.notification.domain.port.DeliveryOutcome;
import com.notification.domain.retry.FailureClassification;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T103 — the audit allowlist is closed, and the diagnostic cannot carry prose (FR-051, FR-054).
 *
 * <p>Structural checks rather than behavioural ones: these fail if someone widens the allowlist or
 * relaxes the diagnostic rule, even if no test happens to exercise the new path.
 */
class AuditPayloadAllowlistTest {

    @Test
    void theAllowlistIsSealed() {
        // A sealed hierarchy is what makes the content payload structurally excluded: no member
        // carries it, so no code path can add it. An open interface or a free-form map would let
        // any caller put anything in an audit record.
        assertThat(AuditPayload.class.isSealed()).isTrue();
        assertThat(AuditPayload.class.getPermittedSubclasses()).hasSize(10);
    }

    @Test
    void noPayloadDeclaresAContentCarryingField() {
        // Names that would indicate the payload itself rather than a reference to it.
        List<String> forbidden = List.of("body", "payload", "content", "message", "text");

        for (Class<?> permitted : AuditPayload.class.getPermittedSubclasses()) {
            List<String> components =
                    java.util.Arrays.stream(permitted.getRecordComponents())
                            .map(java.lang.reflect.RecordComponent::getName)
                            .map(String::toLowerCase)
                            .toList();

            assertThat(components)
                    .as("%s must not declare a content-carrying field", permitted.getSimpleName())
                    .noneSatisfy(name -> assertThat(forbidden).contains(name));
        }
    }

    @Test
    void contentIsReferencedOnlyByANonReversibleRef() {
        // FR-051: audit refers to content indirectly. The only content-related field anywhere in
        // the allowlist is contentRef, which is a digest.
        var accepted = new AuditPayload.NotificationAccepted("cid", "src", 1, 1, "sha256:abc123");
        assertThat(accepted.fields()).containsKey("contentRef");
        assertThat(accepted.fields().get("contentRef")).startsWith("sha256:");
    }

    @Test
    void aRejectionRecordsFieldNamesNotValues() {
        // The offending VALUES are exactly the caller-supplied data that must not enter audit.
        var rejected = new AuditPayload.NotificationRejected("cid", "src", "recipients,content");
        assertThat(rejected.fields().get("rejectedFields")).isEqualTo("recipients,content");
        assertThat(rejected.fields()).doesNotContainKey("values");
    }

    @Test
    void aProseDiagnosticIsRedactedRatherThanCarried() {
        // The leak the privacy test caught: a provider echoing the submitted message.
        var outcome =
                DeliveryOutcome.failed(
                        FailureClassification.TRANSIENT_PROVIDER_FAILURE,
                        "provider said: SECRET MESSAGE BODY HERE");

        assertThat(outcome.diagnostic()).doesNotContain("SECRET");
        assertThat(outcome.diagnostic()).isEqualTo("REDACTED_UNSAFE_DIAGNOSTIC");
    }

    @Test
    void aCodeShapedDiagnosticIsPreserved() {
        // Adapters can still supply something useful, as long as it is a code and not prose.
        var outcome = DeliveryOutcome.failed(FailureClassification.TIMEOUT, "SMTP_421_TIMEOUT");
        assertThat(outcome.diagnostic()).isEqualTo("SMTP_421_TIMEOUT");
    }

    @Test
    void anOverlongDiagnosticIsRedacted() {
        var outcome = DeliveryOutcome.failed(FailureClassification.UNKNOWN, "A".repeat(500));
        assertThat(outcome.diagnostic()).isEqualTo("REDACTED_UNSAFE_DIAGNOSTIC");
    }
}
