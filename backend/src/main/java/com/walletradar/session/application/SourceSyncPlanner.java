package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.ingestion.adapter.BlockHeightResolver;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.integration.config.IntegrationBackfillProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Owns source-level sync window planning. It sets windows on sync_status and
 * leaves segment creation to BackfillJobPlanner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceSyncPlanner {

    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final List<BlockHeightResolver> blockHeightResolvers;
    private final BackfillProperties backfillProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final IntegrationBackfillProperties integrationBackfillProperties;

    public PlanResult planUniverseChange(UserSession session, Instant observedAt) {
        if (session == null) {
            return PlanResult.empty();
        }
        Instant anchor = normalizeAnchor(observedAt);
        int scheduledTargets = 0;
        int skippedTargets = 0;
        List<String> scheduledOnChainSyncStatusIds = new ArrayList<>();
        List<String> scheduledIntegrationSyncStatusIds = new ArrayList<>();
        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                String syncStatusId = planTarget(
                        "universe-change",
                        normalizedAddress(wallet.getAddress()),
                        networkId,
                        () -> ensureInitialOnChainWindow(normalizedAddress(wallet.getAddress()), networkId, anchor)
                );
                if (syncStatusId != null) {
                    scheduledTargets++;
                    scheduledOnChainSyncStatusIds.add(syncStatusId);
                } else {
                    skippedTargets++;
                }
            }
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            String syncStatusId = planTarget(
                    "universe-change",
                    integration.getIntegrationId(),
                    null,
                    () -> ensureInitialIntegrationWindow(integration, anchor)
            );
            if (syncStatusId != null) {
                integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
                integration.setUpdatedAt(anchor);
                integration.setLastError(null);
                integration.setSyncState(zeroSyncState());
                scheduledTargets++;
                scheduledIntegrationSyncStatusIds.add(syncStatusId);
            } else {
                skippedTargets++;
            }
        }
        return new PlanResult(
                scheduledTargets,
                skippedTargets,
                scheduledOnChainSyncStatusIds,
                scheduledIntegrationSyncStatusIds
        );
    }

    public PlanResult planRefresh(UserSession session, Instant observedAt) {
        if (session == null) {
            return PlanResult.empty();
        }
        Instant anchor = normalizeAnchor(observedAt);
        int scheduledTargets = 0;
        int skippedTargets = 0;
        List<String> scheduledOnChainSyncStatusIds = new ArrayList<>();
        List<String> scheduledIntegrationSyncStatusIds = new ArrayList<>();

        // Pre-fetch the current head block for each UNIQUE network once.
        // Without this, resolveCurrentBlock() is called per wallet×network pair —
        // 52 serial RPC calls for 4 wallets × 13 networks instead of 13.
        Set<NetworkId> requiredNetworks = collectRequiredNetworks(session);
        Map<NetworkId, Long> headBlockCache = prefetchHeadBlocks(requiredNetworks, "refresh");

        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                String syncStatusId = planTarget(
                        "refresh",
                        normalizedAddress(wallet.getAddress()),
                        networkId,
                        () -> scheduleOnChainRefresh(normalizedAddress(wallet.getAddress()), networkId, anchor, headBlockCache)
                );
                if (syncStatusId != null) {
                    scheduledTargets++;
                    scheduledOnChainSyncStatusIds.add(syncStatusId);
                } else {
                    skippedTargets++;
                }
            }
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            String syncStatusId = planTarget(
                    "refresh",
                    integration.getIntegrationId(),
                    null,
                    () -> scheduleIntegrationRefresh(integration, anchor)
            );
            if (syncStatusId != null) {
                integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
                integration.setUpdatedAt(anchor);
                integration.setLastError(null);
                integration.setSyncState(zeroSyncState());
                scheduledTargets++;
                scheduledIntegrationSyncStatusIds.add(syncStatusId);
            } else {
                skippedTargets++;
            }
        }
        return new PlanResult(
                scheduledTargets,
                skippedTargets,
                scheduledOnChainSyncStatusIds,
                scheduledIntegrationSyncStatusIds
        );
    }

    public int planStandaloneInitialOnChain(String address, List<NetworkId> networks, Instant observedAt) {
        Instant anchor = normalizeAnchor(observedAt);
        int scheduledTargets = 0;
        for (NetworkId networkId : normalizeNetworks(networks)) {
            if (planTarget(
                    "standalone-initial",
                    normalizedAddress(address),
                    networkId,
                    () -> ensureInitialOnChainWindow(normalizedAddress(address), networkId, anchor)
            ) != null) {
                scheduledTargets++;
            }
        }
        return scheduledTargets;
    }

    public int planStandaloneRefreshOnChain(String address, List<NetworkId> networks, Instant observedAt) {
        Instant anchor = normalizeAnchor(observedAt);
        List<NetworkId> normalized = normalizeNetworks(networks);
        Set<NetworkId> networkSet = normalized.isEmpty() ? EnumSet.noneOf(NetworkId.class) : EnumSet.copyOf(normalized);
        Map<NetworkId, Long> headBlockCache = prefetchHeadBlocks(networkSet, "standalone-refresh");
        int scheduledTargets = 0;
        for (NetworkId networkId : normalized) {
            if (planTarget(
                    "standalone-refresh",
                    normalizedAddress(address),
                    networkId,
                    () -> scheduleOnChainRefresh(normalizedAddress(address), networkId, anchor, headBlockCache)
            ) != null) {
                scheduledTargets++;
            }
        }
        return scheduledTargets;
    }

    /**
     * Isolates a single source's window planning so a transient per-network failure (e.g. unresolvable head block:
     * explorer down, RPC 401/timeout) is treated as a skipped target and never aborts planning for the remaining
     * wallets/networks/integrations. The failing source is simply not armed and will be retried on the next refresh.
     */
    private String planTarget(String mode, String sourceRef, NetworkId networkId, Supplier<String> planner) {
        try {
            return planner.get();
        } catch (RuntimeException e) {
            log.warn(
                    "Sync planning skipped a source ({} mode): source={}, network={}, reason={} — other sources continue",
                    mode,
                    sourceRef,
                    networkId == null ? "-" : networkId.name(),
                    e.getMessage()
            );
            return null;
        }
    }

    private String ensureInitialOnChainWindow(String walletAddress, NetworkId networkId, Instant anchor) {
        SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                        SyncStatus.SourceKind.ONCHAIN,
                        walletAddress,
                        networkId.name()
                )
                .orElse(null);
        if (status != null && (status.isBackfillComplete() || hasOnChainBlockWindow(status))) {
            return null;
        }
        long currentHead = resolveCurrentBlock(networkId);
        long windowBlocks = resolveWindowBlocksForNetwork(networkId.name());
        long fromBlock = Math.max(0L, currentHead - windowBlocks + 1);
        SyncStatus target = armOnChainWindow(status, walletAddress, networkId.name(), fromBlock, currentHead, anchor, "Backfill queued");
        return target == null ? null : target.getId();
    }

    private String scheduleOnChainRefresh(String walletAddress, NetworkId networkId, Instant anchor) {
        return scheduleOnChainRefresh(walletAddress, networkId, anchor, Map.of());
    }

    private String scheduleOnChainRefresh(
            String walletAddress,
            NetworkId networkId,
            Instant anchor,
            Map<NetworkId, Long> headBlockCache
    ) {
        SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                        SyncStatus.SourceKind.ONCHAIN,
                        walletAddress,
                        networkId.name()
                )
                .orElse(null);
        if (status == null || !status.isBackfillComplete()) {
            return null;
        }
        // Use pre-fetched head block when available; fall back to an on-demand RPC call.
        Long cachedHead = headBlockCache.get(networkId);
        long currentHead = cachedHead != null ? cachedHead : resolveCurrentBlock(networkId);
        Long checkpoint = resolveOnChainCheckpoint(status);
        if (checkpoint == null) {
            return null;
        }
        long fromBlock = checkpoint + 1;
        if (fromBlock > currentHead) {
            return null;
        }
        SyncStatus target = armOnChainWindow(status, walletAddress, networkId.name(), fromBlock, currentHead, anchor, "Refresh queued");
        return target == null ? null : target.getId();
    }

    /**
     * Collects the set of unique on-chain networks referenced by the session's wallets.
     * Used to pre-fetch block heads once per network rather than once per wallet×network pair.
     */
    private Set<NetworkId> collectRequiredNetworks(UserSession session) {
        Set<NetworkId> networks = EnumSet.noneOf(NetworkId.class);
        if (session == null || session.getWallets() == null) {
            return networks;
        }
        for (UserSession.SessionWallet wallet : session.getWallets()) {
            if (wallet == null || wallet.getNetworks() == null) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks()) {
                if (networkId != null) {
                    networks.add(networkId);
                }
            }
        }
        return networks;
    }

    /**
     * Resolves the current head block for each unique network once and caches the results.
     * Networks that fail to resolve (RPC error/timeout) are silently excluded from the cache;
     * callers fall back to an on-demand RPC call for those networks, preserving existing behaviour.
     */
    private Map<NetworkId, Long> prefetchHeadBlocks(Set<NetworkId> networks, String mode) {
        if (networks == null || networks.isEmpty()) {
            return Map.of();
        }
        Map<NetworkId, Long> cache = new EnumMap<>(NetworkId.class);
        for (NetworkId networkId : networks) {
            try {
                long head = resolveCurrentBlock(networkId);
                cache.put(networkId, head);
                log.debug("Head block prefetched ({} mode): network={} head={}", mode, networkId.name(), head);
            } catch (RuntimeException e) {
                log.warn(
                        "Head block prefetch failed ({} mode): network={}, reason={} — will retry per-wallet",
                        mode,
                        networkId.name(),
                        e.getMessage()
                );
            }
        }
        return cache;
    }

    private SyncStatus armOnChainWindow(
            SyncStatus status,
            String walletAddress,
            String networkId,
            long fromBlock,
            long toBlock,
            Instant anchor,
            String bannerMessage
    ) {
        if (fromBlock > toBlock) {
            return null;
        }
        SyncStatus target = status == null ? new SyncStatus() : status;
        target.setSourceKind(SyncStatus.SourceKind.ONCHAIN);
        target.setWalletAddress(walletAddress);
        target.setNetworkId(networkId);
        target.setStatus(SyncStatus.SyncStatusValue.PENDING);
        target.setProgressPct(0);
        target.setWindowFromBlock(fromBlock);
        target.setWindowToBlock(toBlock);
        target.setWindowFromTime(null);
        target.setWindowToTime(anchor);
        target.setSyncBannerMessage(bannerMessage);
        target.setBackfillComplete(false);
        target.setRawFetchComplete(false);
        target.setRetryCount(0);
        target.setNextRetryAfter(null);
        target.setUpdatedAt(anchor);
        return syncStatusRepository.save(target);
    }

    private String ensureInitialIntegrationWindow(UserSession.SessionIntegration integration, Instant anchor) {
        SyncStatus status = latestIntegrationStatus(integration).orElse(null);
        if (status != null && (status.isBackfillComplete() || hasActiveWindow(status))) {
            return null;
        }
        Instant fromTime = anchor.minus(integrationBackfillProperties.getHistoryYears() * 365L, ChronoUnit.DAYS);
        SyncStatus target = armIntegrationWindow(status, integration, fromTime, anchor, "Backfill queued");
        return target == null ? null : target.getId();
    }

    private String scheduleIntegrationRefresh(UserSession.SessionIntegration integration, Instant anchor) {
        SyncStatus status = latestIntegrationStatus(integration).orElse(null);
        if (status == null) {
            if (integration == null || integration.getStatus() != UserSession.IntegrationStatus.READY) {
                return null;
            }
            Instant checkpoint = resolveIntegrationCheckpoint(integration, null);
            if (checkpoint == null || !checkpoint.isBefore(anchor)) {
                return null;
            }
            SyncStatus target = armIntegrationWindow(null, integration, checkpoint, anchor, "Refresh queued");
            return target == null ? null : target.getId();
        }
        if (!status.isBackfillComplete()) {
            return null;
        }
        Instant checkpoint = resolveIntegrationCheckpoint(integration, status);
        if (checkpoint == null || !checkpoint.isBefore(anchor)) {
            return null;
        }
        SyncStatus target = armIntegrationWindow(status, integration, checkpoint, anchor, "Refresh queued");
        return target == null ? null : target.getId();
    }

    private SyncStatus armIntegrationWindow(
            SyncStatus status,
            UserSession.SessionIntegration integration,
            Instant fromTime,
            Instant toTime,
            String bannerMessage
    ) {
        if (integration == null
                || integration.getIntegrationId() == null
                || integration.getIntegrationId().isBlank()
                || fromTime == null
                || toTime == null
                || !fromTime.isBefore(toTime)) {
            return null;
        }
        SyncStatus target = status == null ? new SyncStatus() : status;
        target.setSourceKind(SyncStatus.SourceKind.INTEGRATION);
        target.setIntegrationId(integration.getIntegrationId());
        target.setProvider(integration.getProvider() == null ? null : integration.getProvider().name());
        target.setAccountRef(integration.getAccountRef());
        target.setWalletAddress(integration.getAccountRef());
        target.setNetworkId(integration.getProvider() == null ? null : integration.getProvider().name());
        target.setStatus(SyncStatus.SyncStatusValue.PENDING);
        target.setProgressPct(0);
        target.setWindowFromBlock(null);
        target.setWindowToBlock(null);
        target.setWindowFromTime(fromTime.truncatedTo(ChronoUnit.SECONDS));
        target.setWindowToTime(toTime.truncatedTo(ChronoUnit.SECONDS));
        target.setSyncBannerMessage(bannerMessage);
        target.setBackfillComplete(false);
        target.setRawFetchComplete(false);
        target.setRetryCount(0);
        target.setNextRetryAfter(null);
        target.setUpdatedAt(toTime.truncatedTo(ChronoUnit.SECONDS));
        return syncStatusRepository.save(target);
    }

    private Optional<SyncStatus> latestIntegrationStatus(UserSession.SessionIntegration integration) {
        if (integration == null || integration.getIntegrationId() == null || integration.getIntegrationId().isBlank()) {
            return Optional.empty();
        }
        return syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId());
    }

    private List<UserSession.SessionIntegration> enabledIntegrations(UserSession session) {
        if (session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(Objects::nonNull)
                .filter(integration -> integration.getStatus() != UserSession.IntegrationStatus.DISABLED)
                .filter(integration -> integration.getIntegrationId() != null && !integration.getIntegrationId().isBlank())
                .toList();
    }

    private List<NetworkId> normalizeNetworks(List<NetworkId> networks) {
        if (networks == null || networks.isEmpty()) {
            return List.of(NetworkId.values());
        }
        List<NetworkId> values = new ArrayList<>();
        for (NetworkId network : networks) {
            if (network != null && !values.contains(network)) {
                values.add(network);
            }
        }
        return values;
    }

    private long resolveCurrentBlock(NetworkId networkId) {
        BlockHeightResolver resolver = blockHeightResolvers.stream()
                .filter(candidate -> candidate.supports(networkId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No block-height resolver registered for " + networkId.name()));
        return resolver.getCurrentBlock(networkId);
    }

    private long resolveWindowBlocksForNetwork(String networkId) {
        IngestionNetworkProperties.NetworkIngestionEntry entry = ingestionNetworkProperties.getNetwork() == null
                ? null
                : ingestionNetworkProperties.getNetwork().get(networkId);
        if (entry != null && entry.getWindowBlocks() != null && entry.getWindowBlocks() > 0) {
            return entry.getWindowBlocks();
        }
        return Math.max(1L, backfillProperties.getWindowBlocks());
    }

    private boolean hasActiveWindow(SyncStatus status) {
        if (status == null) {
            return false;
        }
        boolean blockWindow = status.getWindowFromBlock() != null
                && status.getWindowToBlock() != null
                && status.getWindowFromBlock() <= status.getWindowToBlock();
        boolean timeWindow = status.getWindowFromTime() != null
                && status.getWindowToTime() != null
                && status.getWindowFromTime().isBefore(status.getWindowToTime());
        return blockWindow || timeWindow;
    }

    /**
     * On-chain sync uses block windows only; a stale time-only window must not block re-arming after DB resets.
     */
    private boolean hasOnChainBlockWindow(SyncStatus status) {
        if (status == null) {
            return false;
        }
        return status.getWindowFromBlock() != null
                && status.getWindowToBlock() != null
                && status.getWindowFromBlock() <= status.getWindowToBlock();
    }

    /**
     * Repairs {@link SyncStatus} block range from current head and configured depth (~2y per network).
     * Used after cold Mongo resets or legacy rows missing {@code windowFromBlock}/{@code windowToBlock}.
     */
    public SyncStatus repairOnChainBlockWindowIfMissing(SyncStatus status, Instant observedAt) {
        if (status == null || status.getSourceKind() != SyncStatus.SourceKind.ONCHAIN) {
            return status;
        }
        if (hasOnChainBlockWindow(status)) {
            return status;
        }
        if (status.getWalletAddress() == null || status.getWalletAddress().isBlank()
                || status.getNetworkId() == null || status.getNetworkId().isBlank()) {
            log.warn("Cannot repair on-chain block window: missing wallet or network on syncStatus id={}", status.getId());
            return status;
        }
        try {
            NetworkId networkId = NetworkId.valueOf(status.getNetworkId().trim().toUpperCase(Locale.ROOT));
            Instant anchor = normalizeAnchor(observedAt);
            long currentHead = resolveCurrentBlock(networkId);
            long windowBlocks = resolveWindowBlocksForNetwork(status.getNetworkId());
            long fromBlock = Math.max(0L, currentHead - windowBlocks + 1);
            return armOnChainWindow(
                    status,
                    normalizedAddress(status.getWalletAddress()),
                    status.getNetworkId(),
                    fromBlock,
                    currentHead,
                    anchor,
                    "Backfill replay window"
            );
        } catch (Exception e) {
            log.error("Failed to repair on-chain block window for syncStatus id={}", status.getId(), e);
            return status;
        }
    }

    /**
     * Resets integration sync_status to a fresh {@code historyYears} time window ending at {@code anchor}.
     */
    public SyncStatus resetIntegrationBackfillWindow(UserSession.SessionIntegration integration, Instant observedAt) {
        if (integration == null || integration.getProvider() == null) {
            return null;
        }
        Instant anchor = normalizeAnchor(observedAt);
        Instant fromTime = anchor.minus(integrationBackfillProperties.getHistoryYears() * 365L, ChronoUnit.DAYS);
        return armIntegrationWindow(
                latestIntegrationStatus(integration).orElse(null),
                integration,
                fromTime,
                anchor,
                "Admin full rebuild"
        );
    }

    private Long resolveOnChainCheckpoint(SyncStatus status) {
        if (status == null) {
            return null;
        }
        Long persistedCheckpoint = status.getLastBlockSynced();
        Long segmentCheckpoint = completedOnChainSegmentCheckpoint(status);
        if (persistedCheckpoint == null) {
            return segmentCheckpoint;
        }
        if (segmentCheckpoint == null) {
            return persistedCheckpoint;
        }
        return Math.max(persistedCheckpoint, segmentCheckpoint);
    }

    private Long completedOnChainSegmentCheckpoint(SyncStatus status) {
        if (status == null || status.getId() == null || status.getId().isBlank()) {
            return null;
        }
        return backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(status.getId()).stream()
                .filter(segment -> segment.getStatus() == BackfillSegment.SegmentStatus.COMPLETE)
                .map(BackfillSegment::getToBlock)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
    }

    private Instant resolveIntegrationCheckpoint(UserSession.SessionIntegration integration, SyncStatus status) {
        Instant persistedCheckpoint = status != null && status.getLastSyncedAt() != null
                ? status.getLastSyncedAt().truncatedTo(ChronoUnit.SECONDS)
                : null;
        Instant segmentCheckpoint = completedIntegrationSegmentCheckpoint(integration);
        if (persistedCheckpoint != null && segmentCheckpoint != null) {
            return persistedCheckpoint.isAfter(segmentCheckpoint) ? persistedCheckpoint : segmentCheckpoint;
        }
        if (persistedCheckpoint != null) {
            return persistedCheckpoint;
        }
        if (segmentCheckpoint != null) {
            return segmentCheckpoint;
        }
        if (integration == null || integration.getLastSyncAt() == null) {
            return null;
        }
        return integration.getLastSyncAt().truncatedTo(ChronoUnit.SECONDS);
    }

    private Instant completedIntegrationSegmentCheckpoint(UserSession.SessionIntegration integration) {
        if (integration == null || integration.getIntegrationId() == null || integration.getIntegrationId().isBlank()) {
            return null;
        }
        return backfillSegmentRepository.findByIntegrationIdOrderByUpdatedAtAsc(integration.getIntegrationId()).stream()
                .filter(segment -> segment.getStatus() == BackfillSegment.SegmentStatus.COMPLETE)
                .map(BackfillSegment::getToTime)
                .filter(Objects::nonNull)
                .map(timestamp -> timestamp.truncatedTo(ChronoUnit.SECONDS))
                .max(Instant::compareTo)
                .orElse(null);
    }

    private Instant normalizeAnchor(Instant observedAt) {
        return (observedAt == null ? Instant.now() : observedAt).truncatedTo(ChronoUnit.SECONDS);
    }

    private String normalizedAddress(String address) {
        return address == null ? null : address.trim().toLowerCase(Locale.ROOT);
    }

    private UserSession.IntegrationSyncState zeroSyncState() {
        UserSession.IntegrationSyncState state = new UserSession.IntegrationSyncState();
        state.setTotalSegments(0);
        state.setCompletedSegments(0);
        state.setFailedSegments(0);
        state.setProgressPct(0);
        return state;
    }

    public record PlanResult(
            int scheduledTargets,
            int skippedTargets,
            List<String> scheduledOnChainSyncStatusIds,
            List<String> scheduledIntegrationSyncStatusIds
    ) {
        public PlanResult(int scheduledTargets, int skippedTargets) {
            this(scheduledTargets, skippedTargets, List.of(), List.of());
        }

        public PlanResult {
            scheduledOnChainSyncStatusIds = scheduledOnChainSyncStatusIds == null
                    ? List.of()
                    : List.copyOf(scheduledOnChainSyncStatusIds);
            scheduledIntegrationSyncStatusIds = scheduledIntegrationSyncStatusIds == null
                    ? List.of()
                    : List.copyOf(scheduledIntegrationSyncStatusIds);
        }

        public static PlanResult empty() {
            return new PlanResult(0, 0, List.of(), List.of());
        }
    }
}
