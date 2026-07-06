package com.walletradar.domain.event;

/**
 * Published after the dedicated linking phase drains for pipeline chaining.
 */
public record LinkingCompletedEvent(String sessionId, int processed, String trigger) {
}
