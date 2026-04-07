package com.walletradar.domain.event;

/**
 * Published after a Bybit normalization drain finishes for pipeline chaining.
 */
public record BybitNormalizationCompletedEvent(String sessionId, int processed, String trigger) {
}
