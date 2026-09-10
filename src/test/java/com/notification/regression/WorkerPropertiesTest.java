package com.notification.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.config.WorkerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * T009 — the batch size becomes configuration without changing its value (ADR-030).
 *
 * <p>Severity ordering is only observable when the backlog exceeds one batch (FR-201), so a test needs
 * a batch size of one. The alternative was building a fifty-one delivery backlog per test, which would
 * have made the assertion about the batch boundary rather than about severity.
 *
 * <p>Making a constant configurable is only safe if the default is unchanged, so that is what this
 * asserts. A configuration knob that quietly moved production behaviour would be a behaviour change
 * wearing a refactor's clothes.
 */
class WorkerPropertiesTest {

    private static final Path CONFIG = Path.of("src/main/resources/application.yaml");
    private static final int PHASE_2_BATCH = 50;

    @Test
    void theDefaultBatchSizeIsUnchangedFromTheConstantItReplaces() {
        assertThat(new WorkerProperties(null).batchSize()).isEqualTo(PHASE_2_BATCH);
    }

    @Test
    void theCommittedConfigurationDeclaresTheSameDefault() throws IOException {
        // Both places must agree. If the record defaulted to 50 and the file said 10, the shipped
        // behaviour would be 10 while the test above still passed.
        assertThat(committedWorkerBlock().get("batch-size"))
                .as("committed batch-size must match the phase-2 constant")
                .isEqualTo(PHASE_2_BATCH);
    }

    @Test
    void aBatchSizeBelowOneIsRejectedAtStartup() {
        // A zero or negative batch claims nothing, so the worker would run forever doing no work and
        // report success. Failing at startup makes a typo a crash rather than a silent stall.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WorkerProperties(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> committedWorkerBlock() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(CONFIG));
        Map<String, Object> notification = (Map<String, Object>) root.get("notification");
        assertThat(notification).isNotNull();
        Map<String, Object> worker = (Map<String, Object>) notification.get("worker");
        assertThat(worker).as("notification.worker block must exist").isNotNull();
        return worker;
    }
}
