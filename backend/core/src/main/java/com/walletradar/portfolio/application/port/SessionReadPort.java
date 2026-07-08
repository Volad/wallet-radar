package com.walletradar.portfolio.application.port;

import com.walletradar.portfolio.application.SessionQueryService;

import java.util.Optional;

/**
 * BFF-facing read contract for session metadata and backfill progress.
 */
public interface SessionReadPort {

    Optional<SessionQueryService.SessionView> findSession(String sessionId);

    Optional<SessionQueryService.SessionBackfillStatusView> findBackfillStatus(String sessionId);
}
