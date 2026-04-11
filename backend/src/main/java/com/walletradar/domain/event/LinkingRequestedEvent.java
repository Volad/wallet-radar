package com.walletradar.domain.event;

/**
 * Explicit trigger for the linking phase when the watchdog or orchestration
 * needs to retry the stage without replaying upstream completion events.
 */
public record LinkingRequestedEvent(String sessionId, String trigger) {
}
