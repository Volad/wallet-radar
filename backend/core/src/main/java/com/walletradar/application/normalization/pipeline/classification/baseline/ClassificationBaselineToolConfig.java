package com.walletradar.application.normalization.pipeline.classification.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for manual baseline tooling used during ADR-001 migration.
 */
@Configuration
@EnableConfigurationProperties(ClassificationBaselineProperties.class)
public class ClassificationBaselineToolConfig {

    @Bean
    public ClassificationBaselineToolService classificationBaselineToolService(
            com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository normalizedTransactionRepository,
            com.walletradar.domain.transaction.raw.RawTransactionRepository rawTransactionRepository,
            com.walletradar.domain.transaction.externalledger.ExternalLedgerRawRepository externalLedgerRawRepository,
            ObjectMapper objectMapper,
            org.springframework.data.mongodb.core.MongoOperations mongoOperations
    ) {
        return new ClassificationBaselineToolService(
                normalizedTransactionRepository,
                rawTransactionRepository,
                externalLedgerRawRepository,
                objectMapper,
                mongoOperations
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "walletradar.tools.classification-baseline", name = "enabled", havingValue = "true")
    public ApplicationRunner classificationBaselineToolRunner(
            ClassificationBaselineToolService toolService,
            ClassificationBaselineProperties properties,
            org.springframework.context.ConfigurableApplicationContext applicationContext
    ) {
        return new ClassificationBaselineToolRunner(toolService, properties, applicationContext);
    }
}
