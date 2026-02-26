package com.walletradar;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces module dependency rules from docs/02-architecture.md (Module Dependency Rules table).
 * Run in CI to keep package boundaries.
 */
class ModuleDependencyArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void scan() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletradar");
    }

    @Test
    void domain_must_only_depend_on_common() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..ingestion..", "..config..", "..api..", "..costbasis..", "..pricing..", "..snapshot..");
        rule.check(classes);
    }

    @Test
    void ingestion_must_not_depend_on_costbasis_snapshot_api() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ingestion..")
                .should().dependOnClassesThat().resideInAnyPackage("..costbasis..", "..snapshot..", "..api..");
        rule.check(classes);
    }

    @Test
    void common_must_not_depend_on_other_app_modules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..common..")
                .should().dependOnClassesThat().resideInAnyPackage("..domain..", "..ingestion..", "..config..", "..api..", "..costbasis..", "..pricing..", "..snapshot..");
        rule.check(classes);
    }

    @Test
    void pricing_must_not_depend_on_ingestion_costbasis_snapshot_api() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..pricing..")
                .should().dependOnClassesThat().resideInAnyPackage("..ingestion..", "..costbasis..", "..snapshot..", "..api..");
        rule.check(classes);
    }

    @Test
    void costbasis_must_not_depend_on_ingestion_snapshot_api() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..costbasis..")
                .should().dependOnClassesThat().resideInAnyPackage("..ingestion..", "..snapshot..", "..api..");
        rule.check(classes);
    }

    @Test
    void costbasis_must_not_depend_on_config() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..costbasis..")
                .should().dependOnClassesThat().resideInAPackage("..config..");
        rule.check(classes);
    }

    @Test
    void snapshot_must_not_depend_on_ingestion_api() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..snapshot..")
                .should().dependOnClassesThat().resideInAnyPackage("..ingestion..", "..api..");
        rule.allowEmptyShould(true).check(classes);
    }

    @Test
    void api_should_not_import_repository_classes() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        rule.check(classes);
    }

    @Test
    void no_cyclic_dependencies_between_slices() {
        ArchRule rule = slices()
                .matching("com.walletradar.(*)..")
                .should().beFreeOfCycles();
        rule.check(classes);
    }

    @Test
    void ingestion_pipeline_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ingestion.pipeline..")
                .should().dependOnClassesThat().resideInAPackage("..ingestion.job..");
        rule.check(classes);
    }

    @Test
    void ingestion_wallet_command_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ingestion.wallet.command..")
                .should().dependOnClassesThat().resideInAPackage("..ingestion.job..");
        rule.check(classes);
    }

    @Test
    void ingestion_wallet_query_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ingestion.wallet.query..")
                .should().dependOnClassesThat().resideInAPackage("..ingestion.job..");
        rule.check(classes);
    }

    @Test
    void ingestion_sync_progress_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ingestion.sync.progress..")
                .should().dependOnClassesThat().resideInAPackage("..ingestion.job..");
        rule.check(classes);
    }
}
