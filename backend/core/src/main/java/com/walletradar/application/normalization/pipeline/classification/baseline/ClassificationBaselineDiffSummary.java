package com.walletradar.application.normalization.pipeline.classification.baseline;

/**
 * Diff summary emitted by the manual baseline tooling and used by phase-gate verification.
 */
public record ClassificationBaselineDiffSummary(
        int baselineRowCount,
        int currentRowCount,
        int addedCount,
        int removedCount,
        int changedCount
) {

    public boolean hasDrift() {
        return addedCount > 0 || removedCount > 0 || changedCount > 0;
    }
}
