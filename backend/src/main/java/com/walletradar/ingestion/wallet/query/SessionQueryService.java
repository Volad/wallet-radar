package com.walletradar.ingestion.wallet.query;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import lombok.RequiredArgsConstructor;
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

    private final UserSessionRepository userSessionRepository;
    private final SyncStatusRepository syncStatusRepository;

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
                walletStatuses
        );
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
            List<WalletBackfillStatusView> wallets
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
