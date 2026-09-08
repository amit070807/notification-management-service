package com.notification.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * T109 — the code's enums and the contract's enums must agree exactly (Principle I).
 *
 * <p>ADR-013 chose hand-written controllers over generated interfaces, so nothing forces the two
 * to stay aligned. The conformance test catches a drifted response only if some test happens to
 * exercise that value; this catches the drift itself, which is the failure mode that matters — a
 * new enum constant added in code and forgotten in the contract.
 */
class EnumParityTest {

    private static final Path SPEC = Path.of("specs/001-notification-management-core/contracts/openapi.yaml");

    @Test
    void everyClosedEnumMatchesTheContract() throws IOException {
        Map<String, List<String>> expected =
                Map.of(
                        "Channel", names(com.notification.domain.model.Channel.values()),
                        "Severity", names(com.notification.domain.model.Severity.values()),
                        "Priority", names(com.notification.domain.model.Priority.values()),
                        "NotificationType", names(com.notification.domain.model.NotificationType.values()),
                        "NotificationState", names(com.notification.domain.state.NotificationState.values()),
                        "DeliveryState", names(com.notification.domain.state.DeliveryState.values()),
                        "FailureClassification", names(com.notification.domain.retry.FailureClassification.values()),
                        "RoutingReasonCode", names(com.notification.domain.routing.RoutingReasonCode.values()));

        Map<String, Object> schemas = schemas();

        for (var entry : expected.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) schemas.get(entry.getKey());
            assertThat(schema).as("contract is missing schema %s", entry.getKey()).isNotNull();

            @SuppressWarnings("unchecked")
            List<String> contractValues = (List<String>) schema.get("enum");

            assertThat(contractValues)
                    .as("%s: code and contract must declare the same closed set", entry.getKey())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    @Test
    void theFailureTaxonomyCoversTheFiveKindsNamedBySourceSection45() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) schemas().get("FailureClassification");
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) schema.get("enum");

        // The five from 4.5, plus UNKNOWN which fails closed (FR-041).
        assertThat(values)
                .contains(
                        "TRANSIENT_PROVIDER_FAILURE",
                        "PERMANENT_PROVIDER_REJECTION",
                        "INVALID_RECIPIENT",
                        "TIMEOUT",
                        "AUTH_ERROR",
                        "UNKNOWN");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(SPEC));
        Map<String, Object> components = (Map<String, Object>) root.get("components");
        return (Map<String, Object>) components.get("schemas");
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> out = new ArrayList<>();
        for (Enum<?> v : values) {
            out.add(v.name());
        }
        return out;
    }
}
