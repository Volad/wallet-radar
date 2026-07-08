package com.walletradar.application.portfolio.application.port;

import com.walletradar.application.portfolio.application.SessionDashboardQueryService;

import java.util.Optional;

/**
 * BFF-facing read contract for session dashboard portfolio snapshot.
 */
public interface SessionDashboardReadPort {

    Optional<SessionDashboardQueryService.SessionDashboardView> findSessionDashboard(String sessionId);
}
