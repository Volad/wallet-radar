package com.walletradar.application.portfolio.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.linking.query.LinkingPendingStatusQuery;
import com.walletradar.application.portfolio.application.port.SessionReadPort;
import com.walletradar.application.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Read-only query service for user sessions and session-level backfill progress.
 */
@Service
@RequiredArgsConstructor
public class SessionQueryService implements SessionReadPort {

    private static final Criteria ACTIVE_ACCOUNTING_CRITERIA = new Criteria().orOperator(
            Criteria.where("excludedFromAccounting").exists(false),
            Criteria.where("excludedFromAccounting").is(Boolean.FALSE)
    );

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final LinkingPendingStatusQuery linkingPendingStatusQuery;
    private final MongoOperations mongoOperations;

    public Optional<SessionView> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::toSessionResponse);
    }

    public Optional<SessionBackfillStatusView> findBackfillStatus(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findById(sessionId.trim())
                .map(this::toBackfillStatusResponse);
    }

    private SessionView toSessionResponse(UserSession session) {
        List<SessionWalletView> wallets = session.getWallets().stream()
                .map(w -> new SessionWalletView(
                        w.getAddress(),
                        w.getLabel(),
                        w.getColor(),
                        w.getNetworks()))
                .toList();
        return new SessionView(session.getId(), wallets);
    }

    private SessionBackfillStatusView toBackfillStatusResponse(UserSession session) {
        List<String> sessionAddresses = session.getWallets().stream()
                .map(UserSession.SessionWallet::getAddress)
                .distinct()
                .toList();
        Map<String, SyncStatus> syncStatusByPair = indexSyncStatuses(sessionAddresses);

        long totalTargets = 0;
        long completedTargets = 0;
        long progressSum = 0;
        boolean hasRunning = false;
        boolean hasFailed = false;
        boolean hasAnyStatus = false;

        List<WalletBackfillStatusView> walletStatuses = new ArrayList<>();
        for (UserSession.SessionWallet wallet : session.getWallets()) {
            List<NetworkBackfillStatusView> networkStatuses = new ArrayList<>();
            for (NetworkId networkId : wallet.getNetworks()) {
                totalTargets++;
                String key = pairKey(wallet.getAddress(), networkId.name());
                SyncStatus syncStatus = syncStatusByPair.get(key);
                NetworkBackfillStatusView networkStatus = toNetworkStatus(networkId, syncStatus);
                networkStatuses.add(networkStatus);

                int progress = networkStatus.progressPct() == null ? 0 : clampProgress(networkStatus.progressPct());
                progressSum += progress;
                boolean complete = Boolean.TRUE.equals(networkStatus.backfillComplete());
                if (complete) {
                    completedTargets++;
                }
                if ("PENDING".equals(networkStatus.status()) || "RUNNING".equals(networkStatus.status())) {
                    hasRunning = true;
                }
                if ("FAILED".equals(networkStatus.status()) || "ABANDONED".equals(networkStatus.status())) {
                    hasFailed = true;
                }
                hasAnyStatus = true;
            }

            walletStatuses.add(new WalletBackfillStatusView(
                    wallet.getAddress(),
                    wallet.getLabel(),
                    wallet.getColor(),
                    networkStatuses
            ));
        }

        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            totalTargets++;
            IntegrationBackfillTargetView integrationStatus = integrationBackfillTarget(integration);
            progressSum += integrationStatus.progressPct();
            if (integrationStatus.complete()) {
                completedTargets++;
            }
            if (integrationStatus.running()) {
                hasRunning = true;
            }
            if (integrationStatus.failed()) {
                hasFailed = true;
            }
            hasAnyStatus = true;
        }

        boolean emptyBackfillComplete = totalTargets == 0
                && session.getPipelineState() != null
                && session.getPipelineState().getStage() == UserSession.PipelineStage.BACKFILL
                && session.getPipelineState().getStatus() == UserSession.PipelineStatus.COMPLETE;
        int overallProgress = emptyBackfillComplete
                ? 100
                : totalTargets == 0 ? 0 : (int) Math.round((double) progressSum / totalTargets);
        String acquisitionStatus = resolveAggregateStatus(
                totalTargets,
                completedTargets,
                hasRunning,
                hasFailed,
                hasAnyStatus,
                emptyBackfillComplete
        );
        String overallStatus = resolveOverallStatus(session, acquisitionStatus);

        Instant lastSyncedAt = syncStatusByPair.values().stream()
                .map(SyncStatus::getLastSyncedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        return new SessionBackfillStatusView(
                session.getId(),
                overallStatus,
                acquisitionStatus,
                overallProgress,
                (int) totalTargets,
                (int) completedTargets,
                session.getPipelineState() == null || session.getPipelineState().getStage() == null
                        ? null
                        : session.getPipelineState().getStage().name(),
                session.getPipelineState() == null || session.getPipelineState().getStatus() == null
                        ? null
                        : session.getPipelineState().getStatus().name(),
                session.getPipelineState() == null
                        ? null
                        : session.getPipelineState().getMessage(),
                phaseProgress(session, syncStatusByPair, totalTargets, completedTargets, overallProgress),
                lastSyncedAt,
                walletStatuses
        );
    }

    private PhaseProgressView phaseProgress(
            UserSession session,
            Map<String, SyncStatus> syncStatusByPair,
            long totalTargets,
            long completedTargets,
            int overallProgress
    ) {
        String stage = session.getPipelineState() == null || session.getPipelineState().getStage() == null
                ? UserSession.PipelineStage.BACKFILL.name()
                : session.getPipelineState().getStage().name();
        AccountingUniverseService.AccountingUniverseScope scope = accountingUniverseService.resolveScope(session);
        List<String> onChainWalletRefs = scope.onChainWalletRefs();
        List<String> memberRefs = scope.memberRefs();

        return switch (stage) {
            case "ON_CHAIN_NORMALIZATION" -> progressFromCounts(
                    stage,
                    countRawTransactions(onChainWalletRefs),
                    countRawTransactions(onChainWalletRefs, "COMPLETE")
            );
            case "ON_CHAIN_CLARIFICATION" -> progressFromCounts(
                    stage,
                    countOnChainClarificationTotal(onChainWalletRefs),
                    countOnChainClarificationProcessed(onChainWalletRefs)
            );
            case "ON_CHAIN_RECLASSIFICATION" -> progressFromCounts(
                    stage,
                    countOnChainClarificationTotal(onChainWalletRefs),
                    countOnChainReclassificationProcessed(onChainWalletRefs)
            );
            case "BYBIT_NORMALIZATION" -> progressFromCounts(
                    stage,
                    countBybitStagingRows(session.getId()),
                    countBybitStagingRows(session.getId(), "CONFIRMED")
            );
            case "LINKING" -> progressFromCounts(
                    stage,
                    1,
                    linkingPendingStatusQuery.hasPendingLinking(session.getId()) || hasPendingPriceRows(memberRefs) ? 0 : 1
            );
            case "PRICING" -> progressFromCounts(
                    stage,
                    countPricingTotal(memberRefs),
                    countPricingProcessed(memberRefs)
            );
            case "ACCOUNTING_REPLAY" -> progressFromCounts(
                    stage,
                    countReplayTotal(memberRefs),
                    countReplayProcessed(scope)
            );
            case "PORTFOLIO_SNAPSHOT_REFRESH" -> progressFromCounts(
                    stage,
                    1,
                    session.getPipelineState() != null
                            && session.getPipelineState().getStatus() == UserSession.PipelineStatus.COMPLETE ? 1 : 0
            );
            case "BACKFILL" -> {
                BackfillSegmentTally segments = countPlannedBackfillSegments(session, syncStatusByPair);
                if (segments.total() > 0) {
                    yield progressFromCounts(stage, segments.total(), segments.completed());
                }
                yield progressFromCounts(stage, totalTargets, completedTargets, overallProgress);
            }
            default -> progressFromCounts(stage, totalTargets, completedTargets, overallProgress);
        };
    }

    /**
     * Sums {@link BackfillSegment} rows for on-chain sync_status ids and integration ids on this session.
     */
    private BackfillSegmentTally countPlannedBackfillSegments(
            UserSession session,
            Map<String, SyncStatus> syncStatusByPair
    ) {
        long total = 0;
        long completed = 0;
        if (session.getWallets() != null) {
            for (UserSession.SessionWallet wallet : session.getWallets()) {
                if (wallet == null || wallet.getNetworks() == null) {
                    continue;
                }
                for (NetworkId networkId : wallet.getNetworks()) {
                    String key = pairKey(wallet.getAddress(), networkId.name());
                    SyncStatus sync = syncStatusByPair.get(key);
                    if (sync == null || sync.getId() == null || sync.getId().isBlank()) {
                        continue;
                    }
                    String sid = sync.getId();
                    total += backfillSegmentRepository.countBySyncStatusId(sid);
                    completed += backfillSegmentRepository.countBySyncStatusIdAndStatus(
                            sid,
                            BackfillSegment.SegmentStatus.COMPLETE
                    );
                }
            }
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            if (integration.getIntegrationId() == null || integration.getIntegrationId().isBlank()) {
                continue;
            }
            String iid = integration.getIntegrationId();
            total += backfillSegmentRepository.countByIntegrationId(iid);
            completed += backfillSegmentRepository.countByIntegrationIdAndStatus(
                    iid,
                    BackfillSegment.SegmentStatus.COMPLETE
            );
        }
        return new BackfillSegmentTally(total, completed);
    }

    private record BackfillSegmentTally(long total, long completed) {
    }

    private Map<String, SyncStatus> indexSyncStatuses(List<String> addresses) {
        if (addresses.isEmpty()) {
            return Map.of();
        }
        return syncStatusRepository.findByWalletAddressIn(addresses).stream()
                .filter(s -> s.getSourceKind() == null || s.getSourceKind() == SyncStatus.SourceKind.ONCHAIN)
                .filter(s -> s.getWalletAddress() != null && s.getNetworkId() != null)
                .collect(Collectors.toMap(
                        s -> pairKey(s.getWalletAddress(), s.getNetworkId()),
                        s -> s,
                        SessionQueryService::pickLatest,
                        LinkedHashMap::new
                ));
    }

    private NetworkBackfillStatusView toNetworkStatus(NetworkId networkId, SyncStatus syncStatus) {
        if (syncStatus == null) {
            return new NetworkBackfillStatusView(
                    networkId.name(),
                    SyncStatus.SyncStatusValue.PENDING.name(),
                    0,
                    null,
                    false,
                    "Backfill queued"
            );
        }
        return new NetworkBackfillStatusView(
                networkId.name(),
                syncStatus.getStatus() != null ? syncStatus.getStatus().name() : SyncStatus.SyncStatusValue.PENDING.name(),
                clampProgress(syncStatus.getProgressPct() == null ? 0 : syncStatus.getProgressPct()),
                syncStatus.getLastBlockSynced(),
                syncStatus.isBackfillComplete(),
                syncStatus.getSyncBannerMessage()
        );
    }

    private static String resolveAggregateStatus(long totalTargets, long completedTargets,
                                                 boolean hasRunning, boolean hasFailed,
                                                 boolean hasAnyStatus, boolean emptyBackfillComplete) {
        if (emptyBackfillComplete) {
            return SyncStatus.SyncStatusValue.COMPLETE.name();
        }
        if (totalTargets > 0 && completedTargets == totalTargets) {
            return SyncStatus.SyncStatusValue.COMPLETE.name();
        }
        if (hasRunning) {
            return SyncStatus.SyncStatusValue.RUNNING.name();
        }
        if (hasFailed) {
            return SyncStatus.SyncStatusValue.FAILED.name();
        }
        if (hasAnyStatus) {
            return SyncStatus.SyncStatusValue.PARTIAL.name();
        }
        return SyncStatus.SyncStatusValue.PENDING.name();
    }

    private static String resolveOverallStatus(UserSession session, String acquisitionStatus) {
        if (session == null || session.getPipelineState() == null || session.getPipelineState().getStatus() == null) {
            return acquisitionStatus;
        }
        UserSession.PipelineState pipelineState = session.getPipelineState();
        return switch (pipelineState.getStatus()) {
            case RUNNING -> SyncStatus.SyncStatusValue.RUNNING.name();
            case FAILED -> SyncStatus.SyncStatusValue.FAILED.name();
            case BLOCKED -> "BLOCKED";
            case COMPLETE -> pipelineState.getStage() == UserSession.PipelineStage.PORTFOLIO_SNAPSHOT_REFRESH
                    ? SyncStatus.SyncStatusValue.COMPLETE.name()
                    : acquisitionStatus;
        };
    }

    private static String pairKey(String walletAddress, String networkId) {
        return walletAddress + "|" + networkId;
    }

    private static int clampProgress(int progressPct) {
        return Math.max(0, Math.min(100, progressPct));
    }

    private List<UserSession.SessionIntegration> enabledIntegrations(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(integration -> integration != null
                        && integration.getStatus() != UserSession.IntegrationStatus.DISABLED
                        && integration.getIntegrationId() != null
                        && !integration.getIntegrationId().isBlank())
                .toList();
    }

    private IntegrationBackfillTargetView integrationBackfillTarget(UserSession.SessionIntegration integration) {
        SyncStatus integrationSync = syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()).orElse(null);
        if (integrationSync != null) {
            boolean complete = integrationSync.isBackfillComplete();
            boolean failed = integrationSync.getStatus() == SyncStatus.SyncStatusValue.FAILED
                    || integrationSync.getStatus() == SyncStatus.SyncStatusValue.ABANDONED;
            boolean running = integrationSync.getStatus() == SyncStatus.SyncStatusValue.RUNNING
                    || integrationSync.getStatus() == SyncStatus.SyncStatusValue.PENDING;
            return new IntegrationBackfillTargetView(
                    clampProgress(integrationSync.getProgressPct() == null ? 0 : integrationSync.getProgressPct()),
                    complete,
                    running && !complete && !failed,
                    failed
            );
        }
        long totalSegments = backfillSegmentRepository.countByIntegrationId(integration.getIntegrationId());
        long completedSegments = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.COMPLETE
        );
        long failedSegments = backfillSegmentRepository.countByIntegrationIdAndStatus(
                integration.getIntegrationId(),
                BackfillSegment.SegmentStatus.FAILED
        );
        int progressPct;
        boolean complete;
        boolean running;
        boolean failed;
        if (totalSegments > 0) {
            progressPct = clampProgress((int) Math.round((double) completedSegments * 100.0 / totalSegments));
            complete = completedSegments >= totalSegments;
            failed = failedSegments > 0 && !complete;
            running = !complete && !failed;
        } else {
            complete = integration.getStatus() == UserSession.IntegrationStatus.READY;
            failed = integration.getStatus() == UserSession.IntegrationStatus.ERROR;
            running = !complete && !failed;
            progressPct = complete ? 100 : 0;
        }
        return new IntegrationBackfillTargetView(progressPct, complete, running, failed);
    }

    private PhaseProgressView progressFromCounts(String phase, long totalCount, long processedCount) {
        long normalizedProcessed = Math.max(0, Math.min(processedCount, totalCount));
        long leftCount = Math.max(0, totalCount - normalizedProcessed);
        int progressPct = totalCount == 0 ? 100 : clampProgress((int) Math.round((double) normalizedProcessed * 100.0 / totalCount));
        return new PhaseProgressView(phase, progressPct, normalizedProcessed, leftCount, totalCount);
    }

    private PhaseProgressView progressFromCounts(String phase, long totalCount, long processedCount, int fallbackPct) {
        long normalizedProcessed = Math.max(0, Math.min(processedCount, totalCount));
        long leftCount = Math.max(0, totalCount - normalizedProcessed);
        int progressPct = totalCount == 0 ? clampProgress(fallbackPct) : clampProgress((int) Math.round((double) normalizedProcessed * 100.0 / totalCount));
        return new PhaseProgressView(phase, progressPct, normalizedProcessed, leftCount, totalCount);
    }

    private long countRawTransactions(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(Criteria.where("walletAddress").in(walletAddresses));
        return mongoOperations.count(query, "raw_transactions");
    }

    private long countRawTransactions(List<String> walletAddresses, String normalizationStatus) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("normalizationStatus").is(normalizationStatus)
        ));
        return mongoOperations.count(query, "raw_transactions");
    }

    private long countOnChainClarificationTotal(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("source").is("ON_CHAIN")
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countOnChainClarificationProcessed(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("source").is("ON_CHAIN"),
                Criteria.where("status").ne("PENDING_CLARIFICATION")
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countOnChainReclassificationProcessed(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("source").is("ON_CHAIN"),
                Criteria.where("status").nin("PENDING_CLARIFICATION", "PENDING_RECLASSIFICATION")
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countBybitStagingRows(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Query query = Query.query(Criteria.where("sessionId").is(sessionId));
        if (mongoOperations.exists(query, BybitExtractedEvent.class)) {
            return mongoOperations.count(query, BybitExtractedEvent.class);
        }
        return mongoOperations.count(query, ExternalLedgerRaw.class);
    }

    private long countBybitStagingRows(String sessionId, String status) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("sessionId").is(sessionId),
                Criteria.where("status").is(status)
        ));
        Query sessionQuery = Query.query(Criteria.where("sessionId").is(sessionId));
        if (mongoOperations.exists(sessionQuery, BybitExtractedEvent.class)) {
            return mongoOperations.count(query, BybitExtractedEvent.class);
        }
        return mongoOperations.count(query, ExternalLedgerRaw.class);
    }

    private long countPricingTotal(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").in("PENDING_PRICE", "PENDING_STAT", "CONFIRMED"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private boolean hasPendingPriceRows(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return false;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").is("PENDING_PRICE"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.exists(query, NormalizedTransaction.class);
    }

    private long countPricingProcessed(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").in("PENDING_STAT", "CONFIRMED"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countReplayTotal(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").is("CONFIRMED"),
                ACTIVE_ACCOUNTING_CRITERIA
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private long countReplayProcessed(AccountingUniverseService.AccountingUniverseScope scope) {
        if (scope == null || scope.memberRefs().isEmpty()) {
            return 0;
        }
        Query query = scope.accountingUniverseId() == null || scope.accountingUniverseId().isBlank()
                ? Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(scope.memberRefs()),
                Criteria.where("normalizedTransactionId").ne(null)
        ))
                : Query.query(new Criteria().andOperator(
                Criteria.where("accountingUniverseId").is(scope.accountingUniverseId()),
                Criteria.where("normalizedTransactionId").ne(null)
        ));
        long ledgerMaterializedTransactions = mongoOperations
                .findDistinct(query, "normalizedTransactionId", "asset_ledger_points", String.class)
                .size();
        return ledgerMaterializedTransactions + countReplayZeroFlowConfirmed(scope.memberRefs());
    }

    private long countReplayZeroFlowConfirmed(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("status").is("CONFIRMED"),
                ACTIVE_ACCOUNTING_CRITERIA,
                new Criteria().orOperator(
                        Criteria.where("flows").exists(false),
                        Criteria.where("flows").size(0)
                )
        ));
        return mongoOperations.count(query, NormalizedTransaction.class);
    }

    private static SyncStatus pickLatest(SyncStatus a, SyncStatus b) {
        Instant aUpdated = a.getUpdatedAt();
        Instant bUpdated = b.getUpdatedAt();
        if (aUpdated == null && bUpdated == null) {
            return a;
        }
        if (aUpdated == null) {
            return b;
        }
        if (bUpdated == null) {
            return a;
        }
        return Comparator.<Instant>naturalOrder().compare(aUpdated, bUpdated) >= 0 ? a : b;
    }

    public record SessionView(
            String sessionId,
            List<SessionWalletView> wallets
    ) {
    }

    public record SessionWalletView(
            String address,
            String label,
            String color,
            List<NetworkId> networks
    ) {
    }

    public record SessionBackfillStatusView(
            String sessionId,
            String status,
            String acquisitionStatus,
            Integer overallProgressPct,
            Integer totalTargets,
            Integer completedTargets,
            String pipelineStage,
            String pipelineStatus,
            String pipelineMessage,
            PhaseProgressView phaseProgress,
            Instant lastSyncedAt,
            List<WalletBackfillStatusView> wallets
    ) {
    }

    public record PhaseProgressView(
            String phase,
            Integer progressPct,
            Long processedCount,
            Long leftCount,
            Long totalCount
    ) {
    }

    public record WalletBackfillStatusView(
            String address,
            String label,
            String color,
            List<NetworkBackfillStatusView> networks
    ) {
    }

    public record NetworkBackfillStatusView(
            String networkId,
            String status,
            Integer progressPct,
            Long lastBlockSynced,
            Boolean backfillComplete,
            String syncBannerMessage
    ) {
    }

    private record IntegrationBackfillTargetView(
            int progressPct,
            boolean complete,
            boolean running,
            boolean failed
    ) {
    }
}
