package com.walletradar;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the target ADR-001 boundaries for classification and clarification layers.
 */
class ClassificationArchitectureArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void scan() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletradar");
    }

    @Test
    void family_classifiers_must_not_depend_on_jobs_or_clarification_pipeline() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.normalization.pipeline.classification.onchain.family..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..job..",
                        "..application.linking.pipeline.clarification.."
                );
        rule.check(classes);
    }

    @Test
    void family_classifiers_must_not_depend_on_repositories_mongo_or_raw_transaction() {
        ArchRule mongoRule = noClasses()
                .that().resideInAPackage("..application.normalization.pipeline.classification.onchain.family..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.data.mongodb.core..",
                        "..domain.transaction.raw.."
                );
        mongoRule.check(classes);

        ArchRule repositoryRule = noClasses()
                .that().resideInAPackage("..application.normalization.pipeline.classification.onchain.family..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        repositoryRule.check(classes);
    }

    @Test
    void family_classifiers_must_not_depend_on_protocol_or_special_handler_layers() {
        ArchRule protocolRule = noClasses()
                .that().resideInAPackage("..application.normalization.pipeline.classification.onchain.family..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application.normalization.pipeline.classification.onchain.protocol.registry..",
                        "..application.normalization.pipeline.classification.onchain.protocol.gmx..",
                        "..application.normalization.pipeline.classification.onchain.protocol.cow..",
                        "..application.normalization.pipeline.classification.onchain.protocol.euler..",
                        "..application.normalization.pipeline.classification.onchain.protocol.resolv..",
                        "..application.normalization.pipeline.classification.special.."
                );
        protocolRule.check(classes);
    }

    @Test
    void protocol_semantic_classifiers_must_not_depend_on_jobs_clarification_or_persistence() {
        ArchRule layeredRule = noClasses()
                .that().resideInAPackage("..application.normalization.pipeline.classification.onchain.protocol..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..job..",
                        "..application.linking.pipeline.clarification..",
                        "org.springframework.data.mongodb.core..",
                        "..domain.transaction.raw.."
                );
        layeredRule.check(classes);

        ArchRule repositoryRule = noClasses()
                .that().resideInAPackage("..application.normalization.pipeline.classification.onchain.protocol..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        repositoryRule.check(classes);
    }

    @Test
    void registry_adapter_layer_must_not_depend_on_special_handler_package() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.normalization.pipeline.classification.onchain.protocol.registry..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application.normalization.pipeline.classification.special.."
                );
        rule.check(classes);
    }

    @Test
    void clarification_services_must_not_depend_on_repositories_or_clarification_gateways() {
        ArchRule layeredRule = noClasses()
                .that().haveSimpleNameEndingWith("ClarificationService")
                .and().resideInAPackage("..application.normalization.job.clarification..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application.linking.pipeline.clarification..",
                        "org.springframework.data.mongodb.core.."
                );
        layeredRule.check(classes);

        ArchRule repositoryRule = noClasses()
                .that().haveSimpleNameEndingWith("ClarificationService")
                .and().resideInAPackage("..application.normalization.job.clarification..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        repositoryRule.check(classes);
    }

    @Test
    void clarification_job_must_not_depend_on_repositories_or_clarification_gateways() {
        ArchRule layeredRule = noClasses()
                .that().haveSimpleName("OnChainClarificationJob")
                .and().resideInAPackage("..application.normalization.job.clarification..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application.linking.pipeline.clarification..",
                        "org.springframework.data.mongodb.core.."
                );
        layeredRule.check(classes);

        ArchRule repositoryRule = noClasses()
                .that().haveSimpleName("OnChainClarificationJob")
                .and().resideInAPackage("..application.normalization.job.clarification..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
        repositoryRule.check(classes);
    }
}
