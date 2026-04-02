package com.walletradar.domain.event;

/**
 * Application event published after an on-chain normalization drain finishes with processed rows.
 */
public record OnChainNormalizationCompletedEvent(String sessionId, int processed, String trigger) {
}
