package com.walletradar.integration.bybit;

import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.integration.IntegrationBackfillPlanner;
import com.walletradar.integration.config.BybitIntegrationProperties;
import com.walletradar.integration.config.IntegrationBackfillProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces Bybit-specific segment specs for the shared integration backfill
 * planner.
 */
@Service
@RequiredArgsConstructor
public class BybitBackfillSegmentPlanner implements IntegrationBackfillPlanner {

    private final BybitIntegrationProperties bybitIntegrationProperties;
    private final IntegrationBackfillProperties integrationBackfillProperties;

    @Override
    public boolean supports(UserSession.IntegrationProvider provider) {
        return provider == UserSession.IntegrationProvider.BYBIT;
    }

    @Override
    public List<BackfillSegment> planInitialBackfill(
            String sessionId,
            UserSession.SessionIntegration integration,
            Instant plannedAt
    ) {
        Instant anchor = (plannedAt == null ? Instant.now() : plannedAt).truncatedTo(ChronoUnit.SECONDS);
        Instant from = anchor.minus(integrationBackfillProperties.getHistoryYears() * 365L, ChronoUnit.DAYS);
        List<BackfillSegment> segments = new ArrayList<>();
        int index = 0;
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.TRANSACTION_LOG, from, anchor, bybitIntegrationProperties.getTransactionLogWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.EXECUTION_LINEAR, from, anchor, bybitIntegrationProperties.getExecutionWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.EXECUTION_INVERSE, from, anchor, bybitIntegrationProperties.getExecutionWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.EXECUTION_SPOT, from, anchor, bybitIntegrationProperties.getExecutionWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.EXECUTION_OPTION, from, anchor, bybitIntegrationProperties.getExecutionWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.FUNDING_HISTORY, from, anchor, bybitIntegrationProperties.getFundingHistoryWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.DEPOSIT_ONCHAIN, from, anchor, bybitIntegrationProperties.getDepositWithdrawalWindowDays(), index, segments, plannedAt);
        index = addTimeRangeSegments(sessionId, integration, BybitIntegrationStream.WITHDRAWAL, from, anchor, bybitIntegrationProperties.getDepositWithdrawalWindowDays(), index, segments, plannedAt);
        return List.copyOf(segments);
    }

    private int addTimeRangeSegments(
            String sessionId,
            UserSession.SessionIntegration integration,
            BybitIntegrationStream stream,
            Instant from,
            Instant to,
            int windowDays,
            int startIndex,
            List<BackfillSegment> target,
            Instant plannedAt
    ) {
        int effectiveWindowDays = effectiveWindowDays(stream, windowDays);
        Instant cursor = from;
        int index = startIndex;
        while (cursor.isBefore(to)) {
            Instant segmentEnd = cursor.plus(effectiveWindowDays, ChronoUnit.DAYS);
            if (segmentEnd.isAfter(to)) {
                segmentEnd = to;
            }
            BackfillSegment segment = new BackfillSegment();
            segment.setId(integration.getIntegrationId() + ":" + stream.name() + ":" + index);
            segment.setSessionId(sessionId);
            segment.setSourceKind(BackfillSegment.SourceKind.INTEGRATION);
            segment.setSegmentKind(BackfillSegment.SegmentKind.TIME_RANGE);
            segment.setIntegrationId(integration.getIntegrationId());
            segment.setProvider(UserSession.IntegrationProvider.BYBIT.name());
            segment.setAccountRef(integration.getAccountRef());
            segment.setStream(stream.name());
            segment.setSegmentIndex(index);
            segment.setFromTime(cursor);
            segment.setToTime(segmentEnd);
            segment.setStatus(BackfillSegment.SegmentStatus.PENDING);
            segment.setProgressPct(0);
            segment.setProcessedCount(0L);
            segment.setRetryCount(0);
            segment.setUpdatedAt(plannedAt == null ? Instant.now() : plannedAt);
            target.add(segment);
            index++;
            cursor = segmentEnd;
        }
        return index;
    }

    private int effectiveWindowDays(BybitIntegrationStream stream, int requestedWindowDays) {
        if (stream == BybitIntegrationStream.EARN_FLEXIBLE_SAVING
                || stream == BybitIntegrationStream.FUNDING_HISTORY) {
            return Math.min(requestedWindowDays, 7);
        }
        return requestedWindowDays;
    }
}
