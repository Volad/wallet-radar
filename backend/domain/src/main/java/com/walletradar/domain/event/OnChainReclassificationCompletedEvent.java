package com.walletradar.domain.event;

/**
 * Published after on-chain rows marked by clarification have been reclassified.
 */
public record OnChainReclassificationCompletedEvent(String sessionId, int processed, String trigger) {
}
