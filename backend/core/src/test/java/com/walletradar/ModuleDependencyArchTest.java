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
 * Enforces package boundaries for application runtime modules (Track A / A3).
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
    void domain_must_not_depend_on_runtime_layers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..config..", "..api..", "..backend..");
        rule.check(classes);
    }

    @Test
    void common_must_not_depend_on_other_app_modules() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.walletradar.platform.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..domain..",
                        "com.walletradar.config..",
                        "..api..",
                        "..backend.."
                );
        rule.check(classes);
    }

    @Test
    void api_should_not_import_repository_classes() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        rule.check(classes);
    }

    @Test
    void domain_slices_must_not_have_cycles() {
        ArchRule rule = slices()
                .matching("com.walletradar.domain.(*)..")
                .should().beFreeOfCycles();
        rule.check(classes);
    }

    @Test
    void backfill_wallet_command_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.backfill.wallet.command..")
                .should().dependOnClassesThat().resideInAPackage("..application..job..");
        rule.check(classes);
    }

    @Test
    void backfill_query_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.backfill.query..")
                .should().dependOnClassesThat().resideInAPackage("..application..job..");
        rule.check(classes);
    }

    @Test
    void backfill_sync_progress_must_not_depend_on_job_triggers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.backfill.sync.progress..")
                .should().dependOnClassesThat().resideInAPackage("..application..job..");
        rule.check(classes);
    }
}
