package com.walletradar.domain.event;

/**
 * Explicit trigger for rerunning the Dzengi normalization stage without replaying
 * unrelated upstream completion events.
 */
public record DzengiNormalizationRequestedEvent(String sessionId, String trigger) {
}
