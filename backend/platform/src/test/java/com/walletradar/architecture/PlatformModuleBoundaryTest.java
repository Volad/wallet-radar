package com.walletradar.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Platform layer must depend only on domain and third-party infrastructure libraries.
 */
class PlatformModuleBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void scan() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletradar.platform");
    }

    @Test
    void platform_must_not_depend_on_upper_layers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..platform..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.walletradar.application..",
                        "com.walletradar.api.."
                )
                .because("platform is infrastructure-only and must not import application or api layers");
        rule.check(classes);
    }
}
