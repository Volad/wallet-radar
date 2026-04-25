package com.walletradar.domain.event;

/**
 * Requests a retry/resume of the on-chain reclassification stage.
 */
public record OnChainReclassificationRequestedEvent(String sessionId, String trigger) {
}
