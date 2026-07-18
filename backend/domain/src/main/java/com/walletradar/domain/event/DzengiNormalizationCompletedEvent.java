package com.walletradar.domain.event;

/**
 * Published after a Dzengi normalization drain finishes for pipeline chaining.
 */
public record DzengiNormalizationCompletedEvent(String sessionId, int processed, String trigger) {
}
