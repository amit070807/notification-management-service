package com.notification.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T009 — the Principle VII gate.
 *
 * <p>The constitution requires the domain/framework boundary to be "enforced by an automated
 * architecture/dependency test that runs in CI, not by convention". ADR-002 chose a single Gradle
 * project with package boundaries, which makes this test — not the compiler — the enforcement
 * mechanism. It is therefore blocking and non-bypassable.
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.notification");
    }

    @Test
    void domainMustNotDependOnSpring() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("org.springframework..")
                        .because("Principle VII: the domain must not import web or DI frameworks");
        rule.check(classes);
    }

    @Test
    void domainMustNotDependOnPersistenceTechnology() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("java.sql..", "javax.sql..", "jakarta.persistence..")
                        .because("Principle VII: the domain must not import persistence frameworks");
        rule.check(classes);
    }

    @Test
    void domainMustNotDependOnAdapters() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "..api..",
                                "..persistence..",
                                "..worker..",
                                "..channel..",
                                "..config..",
                                "..application..",
                                "..audit..")
                        .because(
                                "Principle VII: adapters depend on the domain, never the reverse — "
                                        + "this inversion is what makes the ports meaningful");
        rule.check(classes);
    }

    @Test
    void domainMustNotCallTheSystemClock() {
        // Principle III + VI: time enters the domain only through ClockPort, which is what makes
        // bounded retry and expiry testable without real waiting.
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .callMethod(java.time.Instant.class, "now")
                        .orShould()
                        .callMethod(java.time.LocalDateTime.class, "now")
                        .orShould()
                        .callMethod(java.time.LocalDate.class, "now")
                        .orShould()
                        .callMethod(System.class, "currentTimeMillis")
                        .because("Principle III: the domain reads time only through ClockPort");
        rule.check(classes);
    }

    @Test
    void domainMustNotGenerateItsOwnIdentifiers() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .callMethod(java.util.UUID.class, "randomUUID")
                        .because("Principle III: identifiers enter the domain through IdPort");
        rule.check(classes);
    }

    @Test
    void domainMustNotUseUnseededRandomness() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName("java.util.Random")
                        .because("Principle III: jitter comes from RandomPort so retries stay reproducible");
        rule.check(classes);
    }

    @Test
    void apiMustNotReachChannelProvidersDirectly() {
        // Principle II: no provider I/O may happen on the submission request thread.
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..api..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("..channel..", "..worker..")
                        .because(
                                "Principle II: acceptance must not touch a provider — "
                                        + "delivery happens asynchronously after the transaction commits")
                        // The api package is empty until Phase 3 creates the controllers.
                        // Scoped to this rule only: setting archRule.failOnEmptyShould=false
                        // globally would let every rule here pass vacuously if a package were
                        // renamed, which is precisely the erosion these rules exist to prevent.
                        .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void theDomainPackageIsNotEmpty() {
        // Guards the rules above from passing vacuously. If the domain package is ever renamed or
        // emptied, every "no classes in ..domain.. should ..." rule would silently succeed while
        // enforcing nothing. This test makes that failure loud.
        long domainClasses =
                classes.stream()
                        .filter(c -> c.getPackageName().startsWith("com.notification.domain"))
                        .count();
        org.assertj.core.api.Assertions.assertThat(domainClasses)
                .as("the domain package must contain classes, or the architecture rules are vacuous")
                .isGreaterThan(5);
    }
}
