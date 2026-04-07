package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
public class SessionQueryService {

    private static final Criteria ACTIVE_ACCOUNTING_CRITERIA = new Criteria().orOperator(
            Criteria.where("excludedFromAccounting").exists(false),
            Criteria.where("excludedFromAccounting").is(Boolean.FALSE)
    );

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final AccountingUniverseService accountingUniverseService;
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

        int overallProgress = totalTargets == 0 ? 0 : (int) Math.round((double) progressSum / totalTargets);
        String aggregateStatus = resolveAggregateStatus(totalTargets, completedTargets, hasRunning, hasFailed, hasAnyStatus);

        return new SessionBackfillStatusView(
                session.getId(),
                aggregateStatus,
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
                phaseProgress(session, totalTargets, completedTargets, overallProgress),
                walletStatuses
        );
    }

    private PhaseProgressView phaseProgress(
            UserSession session,
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
            case "BYBIT_NORMALIZATION" -> progressFromCounts(
                    stage,
                    countExternalLedgerRows(session.getId()),
                    countExternalLedgerRows(session.getId(), "CONFIRMED")
            );
            case "PRICING" -> progressFromCounts(
                    stage,
                    countPricingTotal(memberRefs),
                    countPricingProcessed(memberRefs)
            );
            case "ACCOUNTING_REPLAY" -> progressFromCounts(
                    stage,
                    countReplayTotal(memberRefs),
                    countReplayProcessed(memberRefs)
            );
            case "BACKFILL" -> progressFromCounts(stage, totalTargets, completedTargets, overallProgress);
            default -> progressFromCounts(stage, totalTargets, completedTargets, overallProgress);
        };
    }

    private Map<String, SyncStatus> indexSyncStatuses(List<String> addresses) {
        if (addresses.isEmpty()) {
            return Map.of();
        }
        return syncStatusRepository.findByWalletAddressIn(addresses).stream()
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
                                                 boolean hasRunning, boolean hasFailed, boolean hasAnyStatus) {
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

    private static String pairKey(String walletAddress, String networkId) {
        return walletAddress + "|" + networkId;
    }

    private static int clampProgress(int progressPct) {
        return Math.max(0, Math.min(100, progressPct));
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

    private long countExternalLedgerRows(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Query query = Query.query(Criteria.where("sessionId").is(sessionId));
        return mongoOperations.count(query, ExternalLedgerRaw.class);
    }

    private long countExternalLedgerRows(String sessionId, String status) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("sessionId").is(sessionId),
                Criteria.where("status").is(status)
        ));
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

    private long countReplayProcessed(List<String> walletAddresses) {
        if (walletAddresses.isEmpty()) {
            return 0;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("walletAddress").in(walletAddresses),
                Criteria.where("normalizedTransactionId").ne(null)
        ));
        return mongoOperations.findDistinct(query, "normalizedTransactionId", "asset_ledger_points", String.class).size();
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
            Integer overallProgressPct,
            Integer totalTargets,
            Integer completedTargets,
            String pipelineStage,
            String pipelineStatus,
            String pipelineMessage,
            PhaseProgressView phaseProgress,
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
}
