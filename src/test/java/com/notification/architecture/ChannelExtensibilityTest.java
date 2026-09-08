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
