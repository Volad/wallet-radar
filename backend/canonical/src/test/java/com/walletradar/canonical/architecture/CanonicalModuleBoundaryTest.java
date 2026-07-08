package com.walletradar.canonical.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module-level purity guard for {@code :canonical} (Track A / A6).
 */
class CanonicalModuleBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void scan() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletradar.canonical");
    }

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
