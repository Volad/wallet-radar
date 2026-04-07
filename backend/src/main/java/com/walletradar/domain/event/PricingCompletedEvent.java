package com.walletradar.domain.event;

/**
 * Published after a pricing drain finishes for pipeline chaining.
 */
public record PricingCompletedEvent(String sessionId, int processed, String trigger) {
}
