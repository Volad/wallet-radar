package com.walletradar.domain.event;

/**
 * Published when all raw backfill targets for one live session are complete.
 */
public record SessionBackfillCompletedEvent(String sessionId, int walletCount, int targetCount) {
}
