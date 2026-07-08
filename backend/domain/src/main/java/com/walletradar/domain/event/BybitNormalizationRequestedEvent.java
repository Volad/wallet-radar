package com.walletradar.domain.event;

/**
 * Explicit trigger for rerunning the Bybit normalization stage without replaying
 * unrelated upstream completion events.
 */
public record BybitNormalizationRequestedEvent(String sessionId, String trigger) {
}
