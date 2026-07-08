package com.walletradar.domain.event;

/**
 * Published after an on-chain clarification drain finishes for pipeline chaining.
 */
public record OnChainClarificationCompletedEvent(String sessionId, int processed, String trigger) {
}
