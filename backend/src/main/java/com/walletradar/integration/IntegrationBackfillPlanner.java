package com.walletradar.integration;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;

import java.time.Instant;
import java.util.List;

/**
 * Provider-specific segment planner. Planning ownership stays shared; each
 * provider contributes only the segment specs it requires.
 */
public interface IntegrationBackfillPlanner {

    boolean supports(UserSession.IntegrationProvider provider);

    List<BackfillSegment> planInitialBackfill(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant plannedAt
    );

    List<BackfillSegment> planIncrementalBackfill(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant from,
            Instant to,
            Instant plannedAt
    );
}
