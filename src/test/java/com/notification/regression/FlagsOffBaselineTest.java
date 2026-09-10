package com.notification.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.notification.config.FeatureFlags;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * T004 — the guard on FR-105.
 *
 * <p>Every enhancement in this phase is switchable and must be inert when off, because that is what
 * makes the flags a rollback mechanism rather than settings. The risk is banal and easy: a flag left
 * enabled in committed configuration, so the "flags off" regression run silently measures a
 * flagged-on build and proves nothing.
 *
 * <p>This reads the <b>committed configuration file</b> rather than a resolved bean, deliberately.
 * A bean reflects whichever profile the test happens to run under; the file is what ships. It also
 * means the test needs no Spring context and no database, so it cannot be skipped by an environment
 * problem — which matters for a guard whose whole job is to not be bypassed.
 */
class FlagsOffBaselineTest {

    private static final Path CONFIG = Path.of("src/main/resources/application.yaml");

    @Test
    void everyFlagIsOffInCommittedConfiguration() throws IOException {
        Map<String, Object> features = committedFeatureFlags();

        assertThat(features).as("notification.features must be present in committed config").isNotNull();
        assertThat(features)
                .as("every committed flag must ship off (FR-105)")
                .allSatisfy((name, value) -> assertThat(value).as("flag %s", name).isEqualTo(false));
    }

    @Test
    void theCommittedConfigurationCoversEveryDeclaredFlag() {
        // Catches the reverse drift: a flag added to the record but never bound, which would leave
        // its default to whatever the record does rather than to something a reviewer can see.
        List<String> declared =
                Arrays.stream(FeatureFlags.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .map(FlagsOffBaselineTest::toKebabCase)
                        .toList();

        assertThat(declared).isNotEmpty();
        assertThat(committedFeatureFlagsQuietly().keySet())
                .as("every flag declared in FeatureFlags must appear in application.yaml")
                .containsAll(declared);
    }

    @Test
    void anUnsetFlagResolvesToOffRatherThanNull() throws Exception {
        // If a property is absent the record must still answer "off". A null Boolean would make
        // "off" depend on whether binding happened, which is not a default at all.
        //
        // Built reflectively rather than by naming each component. An earlier version passed one null
        // per known flag, so adding a flag broke this test on arity — which reads as a failure to fix
        // rather than as a reminder, and invites fixing it by adding a null without thinking about
        // whether the new flag actually defaults off.
        RecordComponent[] components = FeatureFlags.class.getRecordComponents();
        Object[] allNull = new Object[components.length];
        FeatureFlags unset =
                (FeatureFlags)
                        FeatureFlags.class
                                .getDeclaredConstructors()[0]
                                .newInstance(allNull);

        List<Object> resolved = new java.util.ArrayList<>();
        for (RecordComponent component : components) {
            resolved.add(component.getAccessor().invoke(unset));
        }

        assertThat(resolved)
                .as("an entirely unset FeatureFlags must be all-off, not all-null")
                .isNotEmpty()
                .containsOnly(false);
        assertThat(unset).isEqualTo(FeatureFlags.allOff());
    }

    @Test
    void everyFlagComponentIsBoxedSoAnAbsentPropertyStillBinds() {
        for (RecordComponent component : FeatureFlags.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("flag %s must be Boolean, not primitive boolean", component.getName())
                    .isEqualTo(Boolean.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> committedFeatureFlags() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(CONFIG));
        Map<String, Object> notification = (Map<String, Object>) root.get("notification");
        assertThat(notification).as("notification block must exist in application.yaml").isNotNull();
        return (Map<String, Object>) notification.get("features");
    }

    private static Map<String, Object> committedFeatureFlagsQuietly() {
        try {
            return committedFeatureFlags();
        } catch (IOException e) {
            throw new AssertionError("could not read " + CONFIG, e);
        }
    }

    private static String toKebabCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }
}
