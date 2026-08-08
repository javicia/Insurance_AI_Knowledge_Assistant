package com.rag.springai.insuranceai.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Enforces the DDD + Hexagonal Architecture boundaries defined in
 * {@code docs/adr/ADR-001-HEXAGONAL-ARCHITECTURE.md}. These rules are the mechanical
 * counterpart of that ADR: if a rule here needs to be relaxed, the ADR must be updated first.
 *
 * <p>Several rules pass trivially today because {@code ports} and {@code adapters.outbound}
 * are still empty (FASE 1 only establishes the layering — see {@code PROJECT_DISCOVERY.md}).
 * That is intentional: the gate is in place before any bounded-context logic is written, so
 * the first real class added to each package is already constrained.
 */
class ArchitectureTest {

    private static final String BASE_PACKAGE = "com.rag.springai.insuranceai";

    private static JavaClasses importedClasses;

    @BeforeAll
    static void importClasses() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    @Test
    @DisplayName("domain must not depend on Spring, persistence, messaging or serialization frameworks")
    void domainMustBeFrameworkFree() {
        noClasses().that().resideInAPackage(BASE_PACKAGE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "org.apache.kafka..",
                        "com.fasterxml.jackson..",
                        "jakarta.servlet..")
                .because("the domain layer must be pure Java (ADR-001)")
                .check(importedClasses);
    }

    @Test
    @DisplayName("application must not depend on adapters or infrastructure")
    void applicationMustNotDependOnAdaptersOrInfrastructure() {
        noClasses().that().resideInAPackage(BASE_PACKAGE + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".adapters..",
                        BASE_PACKAGE + ".infrastructure..")
                .because("the application layer only depends on domain and ports (ADR-001)")
                .check(importedClasses);
    }

    @Test
    @DisplayName("domain and application must not be accessed from adapters or infrastructure code they don't own")
    void hexagonalLayersRespectDependencyDirection() {
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy(BASE_PACKAGE + ".domain..")
                .layer("Ports").definedBy(BASE_PACKAGE + ".ports..")
                .layer("Application").definedBy(BASE_PACKAGE + ".application..")
                .layer("Adapters").definedBy(BASE_PACKAGE + ".adapters..")
                .layer("Infrastructure").definedBy(BASE_PACKAGE + ".infrastructure..")

                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Ports", "Application", "Adapters", "Infrastructure")
                .whereLayer("Ports").mayOnlyBeAccessedByLayers("Application", "Adapters", "Infrastructure")
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Infrastructure")
                .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Infrastructure")
                .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer();

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("ports must be interfaces, not implementations")
    void portsMustBeInterfaces() {
        classes().that().resideInAPackage(BASE_PACKAGE + ".ports..")
                .should().beInterfaces()
                .because("ports describe a contract; adapters provide the implementation (ADR-001)")
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("outbound adapters must implement an outbound port")
    void outboundAdaptersMustImplementAnOutboundPort() {
        // No outbound port or adapter exists yet in FASE 1 (see class Javadoc), so this rule
        // is intentionally allowed to pass on zero classes today; it becomes load-bearing the
        // moment the first outbound port/adapter pair is added (FASE 3+).
        classes().that().resideInAPackage(BASE_PACKAGE + ".adapters.outbound..")
                .and().areNotInterfaces()
                .and().areTopLevelClasses()
                .should().implement(resideInAPackage(BASE_PACKAGE + ".ports.outbound.."))
                .because("outbound adapters must be swappable implementations of an explicit port (brief section 16/17)")
                .allowEmptyShould(true)
                .check(importedClasses);
    }
}
