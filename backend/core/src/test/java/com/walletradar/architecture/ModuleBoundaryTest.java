package com.walletradar.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Extensibility refactor boundary rules (Track A). Rules start {@link Disabled} until the phase
 * that makes them green; remove {@code @Disabled} when the corresponding track milestone lands.
 */
class ModuleBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void scan() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletradar");
    }

    /** Unlocks in A1 when costbasis stops importing ingestion/integration. */
    @Test
    void costbasis_must_not_depend_on_ingestion_or_integration() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..costbasis..")
                .should().dependOnClassesThat().resideInAnyPackage("..ingestion..", "..integration..");
        rule.check(classes);
    }

    /** Enabled in A3 — pricing has no ingestion/costbasis compile-time deps. */
    @Test
    void pricing_must_not_depend_on_ingestion_or_costbasis() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..pricing..")
                .should().dependOnClassesThat().resideInAnyPackage("..ingestion..", "..costbasis..");
        rule.check(classes);
    }

    /**
     * Freeze-and-shrink: no new Bybit string literals in costbasis/pricing core.
     * Exempt classes whose simple name contains Bybit during in-PR renames (A1).
     */
    @Test
    @Disabled("A1 — enable at end of canonical decoupling")
    void costbasis_must_not_reference_bybit_literals() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..costbasis..")
                .and().haveSimpleNameNotContaining("Bybit")
                .should().dependOnClassesThat().haveNameMatching(".*[Bb]ybit.*");
        rule.check(classes);
    }

    /** A4 — portfolio read queries must not depend on pipeline write jobs. */
    @Test
    void portfolio_read_model_must_not_depend_on_ingestion_job_or_pipeline_write() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..portfolio.application..")
                .and().haveSimpleNameNotContaining("OnChainBalanceRefreshService")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application.backfill.job..",
                        "..application.normalization.job..",
                        "..application.linking.job..",
                        "..application.linking.pipeline..",
                        "..application.normalization.pipeline..",
                        "..application.pipeline.."
                );
        rule.check(classes);
    }

    /**
     * A4 — GET portfolio BFF must stay zero-RPC (no direct platform.networks adapter imports).
     */
    @Test
    void api_portfolio_must_not_depend_on_platform_networks_rpc_adapters() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api.portfolio..")
                .should().dependOnClassesThat().resideInAnyPackage("..platform.networks..");
        rule.check(classes);
    }

    /** Enabled in A3 — pipeline apps must not reach into other apps' service/infrastructure packages. */
    @Test
    void application_modules_must_not_depend_on_other_apps_internals() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "..application.backfill..",
                        "..application.cex..",
                        "..application.linking..",
                        "..application.normalization..",
                        "..application.pipeline..",
                        "..costbasis.application.."
                )
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..service..",
                        "..application..infrastructure.."
                )
                .because("cross-app access must go through port/ only (A3)");
        rule.check(classes);
    }

    /**
     * A5p placeholder — tighten to forbid protocol name literals in costbasis/pricing core once
     * descriptor migration completes.
     */
    @Test
    @Disabled("A5p — enable when protocol literals are externalized to descriptors")
    void core_must_not_contain_protocol_name_literals() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..costbasis..", "..pricing..")
                .should().haveSimpleNameEndingWith("HardcodedProtocolLiteralProbe");
        rule.check(classes);
    }

    /** Placeholder — canonical must stay pure (no Spring/Mongo). Unlocks in A1. */
    @Test
    void canonical_must_not_depend_on_spring_or_mongo() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..canonical..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "org.springframework.data.."
                );
        rule.check(classes);
    }
}
