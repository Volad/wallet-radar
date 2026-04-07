package com.walletradar.ingestion.pipeline.classification.baseline;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Manual-only tooling properties for classification baseline export and diff.
 */
@ConfigurationProperties(prefix = "walletradar.tools.classification-baseline")
@NoArgsConstructor
@Getter
@Setter
public class ClassificationBaselineProperties {

    private boolean enabled = false;

    private ClassificationBaselineMode mode = ClassificationBaselineMode.EXPORT;

    /**
     * Target directory for export outputs or diff outputs.
     */
    private String outputDir = "results/refactor-baseline/adr-001/manual";

    /**
     * Existing baseline directory used by diff mode.
     */
    private String baselineDir;
}
