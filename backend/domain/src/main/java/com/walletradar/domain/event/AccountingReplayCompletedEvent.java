package com.walletradar.domain.event;

/**
 * Published after accounting replay completes so downstream read-model snapshot
 * refresh can run outside the AVCO stage.
 */
public record AccountingReplayCompletedEvent(String sessionId, int processed, String trigger) {
}
