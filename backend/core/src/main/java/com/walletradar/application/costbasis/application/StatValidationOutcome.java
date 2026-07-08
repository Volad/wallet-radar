package com.walletradar.application.costbasis.application;

/**
 * Aggregated outcome for one stat-validation batch.
 */
public record StatValidationOutcome(
        int processed,
        int promotedToConfirmed,
        int demotedToNeedsReview
) {
}
