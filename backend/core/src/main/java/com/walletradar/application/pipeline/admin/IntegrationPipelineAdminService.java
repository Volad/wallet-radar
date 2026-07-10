package com.walletradar.application.pipeline.admin;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.dzengi.DzengiExtractedEvent;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.application.backfill.job.BackfillJobPlanner;
import com.walletradar.integration.IntegrationBackfillPlanningService;
import com.walletradar.application.cex.acquisition.venue.bybit.BybitLiveBalanceService;
import com.walletradar.application.cex.acquisition.venue.dzengi.DzengiLiveBalanceService;
import com.walletradar.application.session.application.SourceSyncPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Destructive integration pipeline reset (Bybit) plus optional on-chain window repair for affected sessions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationPipelineAdminService {

    private final UserSessionRepository userSessionRepository;
    private final MongoOperations mongoOperations;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final IntegrationBackfillPlanningService integrationBackfillPlanningService;
    private final SourceSyncPlanner sourceSyncPlanner;
    private final BackfillJobPlanner backfillJobPlanner;
    private final SyncStatusRepository syncStatusRepository;
    private final BybitLiveBalanceService bybitLiveBalanceService;
    private final DzengiLiveBalanceService dzengiLiveBalanceService;

    /** Supported venues for the destructive per-integration cold rebuild. */
    private enum RebuildVenue {
        BYBIT("BYBIT-", "BYBIT", NormalizedTransactionSource.BYBIT, BybitExtractedEvent.class),
        DZENGI("DZENGI-", "DZENGI", NormalizedTransactionSource.DZENGI, DzengiExtractedEvent.class);

        private final String idPrefix;
        private final String walletPrefix;
        private final NormalizedTransactionSource source;
        private final Class<?> extractedType;

        RebuildVenue(String idPrefix, String walletPrefix, NormalizedTransactionSource source, Class<?> extractedType) {
            this.idPrefix = idPrefix;
            this.walletPrefix = walletPrefix;
            this.source = source;
            this.extractedType = extractedType;
        }

        static RebuildVenue fromIntegrationId(String upperId) {
            for (RebuildVenue venue : values()) {
                if (upperId.startsWith(venue.idPrefix)) {
                    return venue;
                }
            }
            throw new IllegalArgumentException("Only BYBIT and DZENGI integrations are supported");
        }
    }

    public FullRebuildResult fullRebuild(String integrationId, boolean repairSessionOnChainWindows) {
        if (integrationId == null || integrationId.isBlank()) {
            throw new IllegalArgumentException("integrationId is required");
        }
        String trimmedId = integrationId.trim();
        RebuildVenue venue = RebuildVenue.fromIntegrationId(trimmedId.toUpperCase(Locale.ROOT));
        String uid = trimmedId.substring(venue.idPrefix.length()).trim();
        if (uid.isEmpty()) {
            throw new IllegalArgumentException("Invalid integration id");
        }
        Pattern walletPattern = Pattern.compile(
                "^" + venue.walletPrefix + ":" + Pattern.quote(uid) + "(?::[A-Za-z]+)?$",
                Pattern.CASE_INSENSITIVE
        );

        List<UserSession> sessions = userSessionRepository.findAllByIntegrationsIntegrationId(trimmedId);
        if (sessions.isEmpty()) {
            throw new IllegalStateException("No session owns integration " + trimmedId);
        }

        long normalizedDeleted = mongoOperations.remove(
                Query.query(Criteria.where("source")
                        .is(venue.source)
                        .and("walletAddress")
                        .regex(walletPattern)),
                NormalizedTransaction.class
        ).getDeletedCount();

        long ledgerDeleted = mongoOperations.remove(
                Query.query(Criteria.where("walletAddress").regex(walletPattern)),
                AssetLedgerPoint.class
        ).getDeletedCount();

        long rawDeleted = mongoOperations.remove(
                Query.query(Criteria.where("integrationId").is(trimmedId)),
                IntegrationRawEvent.class
        ).getDeletedCount();

        long extractedDeleted = mongoOperations.remove(
                Query.query(Criteria.where("integrationId").is(trimmedId)),
                venue.extractedType
        ).getDeletedCount();

        backfillSegmentRepository.deleteByIntegrationId(trimmedId);

        Instant now = Instant.now();
        int sessionsUpdated = 0;
        for (UserSession session : sessions) {
            if (session.getIntegrations() == null) {
                continue;
            }
            UserSession.SessionIntegration integration = session.getIntegrations().stream()
                    .filter(candidate -> trimmedId.equals(candidate.getIntegrationId()))
                    .findFirst()
                    .orElse(null);
            if (integration == null) {
                continue;
            }
            SyncStatus integrationStatus = sourceSyncPlanner.resetIntegrationBackfillWindow(integration, now);
            if (integrationStatus == null
                    || integrationStatus.getWindowFromTime() == null
                    || integrationStatus.getWindowToTime() == null
                    || !integrationStatus.getWindowFromTime().isBefore(integrationStatus.getWindowToTime())) {
                throw new IllegalStateException("Failed to reset integration sync window for " + trimmedId);
            }
            UserSession.IntegrationSyncState syncState = integrationBackfillPlanningService.replanWindowBackfill(
                    session.getId(),
                    integration,
                    integrationStatus.getWindowFromTime(),
                    integrationStatus.getWindowToTime(),
                    integrationStatus.getId()
            );
            integration.setSyncState(syncState);
            integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
            integration.setUpdatedAt(now);
            integration.setLastError(null);
            // Reset pipeline state so the UI shows BACKFILL progress instead of the stale
            // PORTFOLIO_SNAPSHOT_REFRESH/COMPLETE status from the previous run.
            UserSession.PipelineState backfillState = new UserSession.PipelineState();
            backfillState.setStage(UserSession.PipelineStage.BACKFILL);
            backfillState.setStatus(UserSession.PipelineStatus.RUNNING);
            backfillState.setMessage("Integration re-backfill in progress");
            backfillState.setUpdatedAt(now);
            session.setPipelineState(backfillState);

            if (repairSessionOnChainWindows) {
                List<String> walletRefs = onChainWalletRefs(session);
                if (!walletRefs.isEmpty()) {
                    List<SyncStatus> onChainRows = syncStatusRepository.findOnChainByWalletAddressIn(
                            SyncStatus.SourceKind.ONCHAIN,
                            walletRefs
                    );
                    for (SyncStatus onChain : onChainRows) {
                        SyncStatus repaired = sourceSyncPlanner.repairOnChainBlockWindowIfMissing(onChain, now);
                        if (repaired.getId() != null) {
                            backfillJobPlanner.planOnChainSyncStatus(repaired.getId());
                        }
                    }
                }
            }

            session.setUpdatedAt(now);
            userSessionRepository.save(session);
            sessionsUpdated++;
        }

        // Cycle/5 N15: pre-warm the live balance snapshot so the dashboard's umbrella clamp is
        // authoritative immediately after rebuild instead of waiting for the first dashboard query.
        try {
            if (venue == RebuildVenue.BYBIT) {
                bybitLiveBalanceService.refresh(trimmedId);
            } else if (venue == RebuildVenue.DZENGI) {
                dzengiLiveBalanceService.refresh(trimmedId);
            }
        } catch (RuntimeException ex) {
            log.warn("Live snapshot pre-warm failed for {}: {}", trimmedId, ex.getMessage());
        }

        log.info(
                "Admin full rebuild for {}: sessionsUpdated={}, rawDeleted={}, extractedDeleted={}, normalizedDeleted={}, ledgerDeleted={}",
                trimmedId,
                sessionsUpdated,
                rawDeleted,
                extractedDeleted,
                normalizedDeleted,
                ledgerDeleted
        );

        return new FullRebuildResult(
                trimmedId,
                sessionsUpdated,
                rawDeleted,
                extractedDeleted,
                normalizedDeleted,
                ledgerDeleted
        );
    }

    private static List<String> onChainWalletRefs(UserSession session) {
        List<String> refs = new ArrayList<>();
        if (session.getWallets() == null) {
            return refs;
        }
        for (UserSession.SessionWallet wallet : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            refs.add(wallet.getAddress().trim().toLowerCase(Locale.ROOT));
        }
        return refs;
    }

    public record FullRebuildResult(
        String integrationId,
        int sessionsUpdated,
        long integrationRawEventsDeleted,
        long extractedEventsDeleted,
        long normalizedTransactionsDeleted,
        long assetLedgerPointsDeleted
    ) {
    }
}
