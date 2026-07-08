package com.walletradar.application.normalization.pipeline.classification.baseline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

/**
 * Manual application runner for baseline export/diff.
 */
@Slf4j
public class ClassificationBaselineToolRunner implements ApplicationRunner {

    private final ClassificationBaselineToolService toolService;
    private final ClassificationBaselineProperties properties;
    private final ConfigurableApplicationContext applicationContext;

    public ClassificationBaselineToolRunner(
            ClassificationBaselineToolService toolService,
            ClassificationBaselineProperties properties,
            ConfigurableApplicationContext applicationContext
    ) {
        this.toolService = toolService;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path outputDir = Path.of(properties.getOutputDir()).toAbsolutePath().normalize();
        log.info("classification-baseline tool starting, mode={}, outputDir={}", properties.getMode(), outputDir);
        if (properties.getMode() == ClassificationBaselineMode.DIFF
                || properties.getMode() == ClassificationBaselineMode.VERIFY) {
            String baselineDir = properties.getBaselineDir();
            if (baselineDir == null || baselineDir.isBlank()) {
                throw new IllegalArgumentException("walletradar.tools.classification-baseline.baseline-dir is required for DIFF/VERIFY mode");
            }
            Path normalizedBaselineDir = Path.of(baselineDir).toAbsolutePath().normalize();
            if (properties.getMode() == ClassificationBaselineMode.VERIFY) {
                toolService.verify(normalizedBaselineDir, outputDir);
            } else {
                toolService.diff(normalizedBaselineDir, outputDir);
            }
        } else {
            toolService.export(outputDir);
        }
        log.info("classification-baseline tool completed, closing application context");
        applicationContext.close();
    }
}
