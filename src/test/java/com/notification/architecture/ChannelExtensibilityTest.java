package com.notification.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T108 — adding a channel must not touch routing, retry, state or audit (Principle VII).
 *
 * <p>The constitution states this as a design property; it is only real if it is checked. The
 * check is structural rather than "add a third adapter and see": shipping a channel nobody asked
 * for to prove a point would be its own violation of YAGNI.
 */
class ChannelExtensibilityTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.notification");
    }

    @Test
    void noCoreModuleNamesAConcreteChannel() {
        // The real risk is a switch on Channel.EMAIL buried in retry or routing logic. Such a
        // branch compiles fine and silently makes a new channel behave differently from the
        // others.
        List<String> coreClasses =
                classes.stream()
                        .filter(
                                c ->
                                        c.getPackageName().startsWith("com.notification.domain.routing")
                                                || c.getPackageName().startsWith("com.notification.domain.retry")
                                                || c.getPackageName().startsWith("com.notification.domain.state")
                                                || c.getPackageName().startsWith("com.notification.audit"))
                        .map(c -> c.getName())
                        .toList();

        assertThat(coreClasses).isNotEmpty();

        for (String className : coreClasses) {
            var javaClass = classes.get(className);
            boolean referencesSpecificChannel =
                    javaClass.getFieldAccessesFromSelf().stream()
                            .anyMatch(
                                    a ->
                                            a.getTarget().getFullName().contains("Channel.EMAIL")
                                                    || a.getTarget().getFullName().contains("Channel.SMS"));

            assertThat(referencesSpecificChannel)
                    .as("%s must treat channels uniformly; naming one makes a new channel behave differently", className)
                    .isFalse();
        }
    }

    @Test
    void onlyTheChannelPackageAndConfigKnowAboutProviderImplementations() {
        noClasses()
                .that()
                .resideOutsideOfPackages("..channel..", "..config..")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("SimulatedChannelProvider")
                .because(
                        "Principle VII: adding or swapping a provider must be a change in the channel"
                                + " package and its wiring, nowhere else")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void aFixtureChannelWithItsOwnErrorVocabularyNeedsOnlyTheBaseTypeAndAMap() {
        // T030 / FR-130. The claim under test is that adding a channel is two declarations, not a
        // change to the pipeline. A fixture channel is used rather than shipping a fourth real one,
        // because shipping a channel nobody asked for to prove a point would be its own YAGNI
        // violation.
        var fixture =
                new com.notification.channel.AbstractChannelProvider(
                        com.notification.domain.model.Channel.EMAIL,
                        java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(2)) {
                    @Override
                    protected java.util.Map<String, com.notification.domain.retry.FailureClassification>
                            errorCodeMap() {
                        return java.util.Map.of(
                                "FIXTURE_GONE",
                                com.notification.domain.retry.FailureClassification.INVALID_RECIPIENT,
                                "FIXTURE_BUSY",
                                com.notification.domain.retry.FailureClassification.TRANSIENT_PROVIDER_FAILURE);
                    }

                    @Override
                    protected String providerCall(
                            com.notification.domain.model.RecipientRef recipient,
                            com.notification.domain.model.ContentRef content,
                            com.notification.domain.model.IdempotencyKey key) {
                        return "FIXTURE_BUSY";
                    }
                };

        var outcome =
                fixture.send(
                        new com.notification.domain.model.RecipientRef("r"),
                        new com.notification.domain.model.ContentRef(
                                java.util.UUID.randomUUID(), "sha256:abc", 3),
                        com.notification.domain.model.IdempotencyKey.of(
                                java.util.UUID.randomUUID(),
                                java.util.UUID.randomUUID(),
                                com.notification.domain.model.Channel.EMAIL,
                                1));

        // Classified through the shared taxonomy, with no routing, retry, state or audit change.
        assertThat(outcome.classification())
                .isEqualTo(com.notification.domain.retry.FailureClassification.TRANSIENT_PROVIDER_FAILURE);
        assertThat(outcome.diagnostic()).isNotEqualTo("REDACTED_UNSAFE_DIAGNOSTIC");
    }

    @Test
    void everyShippedAdapterExtendsTheSharedBaseSoTheMappingRuleCannotBeBypassed() {
        // An adapter implementing ChannelProviderPort directly could return an unmapped
        // classification and defeat FR-131. This asserts none does.
        var directImplementors =
                classes.stream()
                        .filter(c -> c.getPackageName().startsWith("com.notification.channel"))
                        .filter(c -> c.isAssignableTo(com.notification.domain.port.ChannelProviderPort.class))
                        .filter(c -> !c.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                        .filter(c -> !c.isAssignableTo(com.notification.channel.AbstractChannelProvider.class))
                        .map(c -> c.getName())
                        .toList();

        assertThat(directImplementors)
                .as("shipped adapters must extend AbstractChannelProvider so error mapping is enforced")
                .isEmpty();
    }

    @Test
    void deliveryLogicDependsOnThePortNotOnAnyAdapter() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..channel..")
                .because("the application layer must reach providers only through ChannelProviderPort")
                .check(classes);
    }
}
