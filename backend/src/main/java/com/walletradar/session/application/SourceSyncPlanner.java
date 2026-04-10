package com.walletradar.session.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns source-level sync window planning. It sets windows on sync_status and
 * leaves segment creation to BackfillJobPlanner.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceSyncPlanner {

    private final SyncStatusRepository syncStatusRepository;
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
        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                if (ensureInitialOnChainWindow(normalizedAddress(wallet.getAddress()), networkId, anchor)) {
                    scheduledTargets++;
                } else {
                    skippedTargets++;
                }
            }
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            if (ensureInitialIntegrationWindow(integration, anchor)) {
                integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
                integration.setUpdatedAt(anchor);
                integration.setLastError(null);
                integration.setSyncState(zeroSyncState());
                scheduledTargets++;
            } else {
                skippedTargets++;
            }
        }
        return new PlanResult(scheduledTargets, skippedTargets);
    }

    public PlanResult planRefresh(UserSession session, Instant observedAt) {
        if (session == null) {
            return PlanResult.empty();
        }
        Instant anchor = normalizeAnchor(observedAt);
        int scheduledTargets = 0;
        int skippedTargets = 0;
        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                if (scheduleOnChainRefresh(normalizedAddress(wallet.getAddress()), networkId, anchor)) {
                    scheduledTargets++;
                } else {
                    skippedTargets++;
                }
            }
        }
        for (UserSession.SessionIntegration integration : enabledIntegrations(session)) {
            if (scheduleIntegrationRefresh(integration, anchor)) {
                integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
                integration.setUpdatedAt(anchor);
                integration.setLastError(null);
                integration.setSyncState(zeroSyncState());
                scheduledTargets++;
            } else {
                skippedTargets++;
            }
        }
        return new PlanResult(scheduledTargets, skippedTargets);
    }

    public int planStandaloneInitialOnChain(String address, List<NetworkId> networks, Instant observedAt) {
        Instant anchor = normalizeAnchor(observedAt);
        int scheduledTargets = 0;
        for (NetworkId networkId : normalizeNetworks(networks)) {
            if (ensureInitialOnChainWindow(normalizedAddress(address), networkId, anchor)) {
                scheduledTargets++;
            }
        }
        return scheduledTargets;
    }

    public int planStandaloneRefreshOnChain(String address, List<NetworkId> networks, Instant observedAt) {
        Instant anchor = normalizeAnchor(observedAt);
        int scheduledTargets = 0;
        for (NetworkId networkId : normalizeNetworks(networks)) {
            if (scheduleOnChainRefresh(normalizedAddress(address), networkId, anchor)) {
                scheduledTargets++;
            }
        }
        return scheduledTargets;
    }

    private boolean ensureInitialOnChainWindow(String walletAddress, NetworkId networkId, Instant anchor) {
        SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                        SyncStatus.SourceKind.ONCHAIN,
                        walletAddress,
                        networkId.name()
                )
                .orElse(null);
        if (status != null && (status.isBackfillComplete() || hasActiveWindow(status))) {
            return false;
        }
        long currentHead = resolveCurrentBlock(networkId);
        long windowBlocks = resolveWindowBlocksForNetwork(networkId.name());
        long fromBlock = Math.max(0L, currentHead - windowBlocks + 1);
        armOnChainWindow(status, walletAddress, networkId.name(), fromBlock, currentHead, anchor, "Backfill queued");
        return true;
    }

    private boolean scheduleOnChainRefresh(String walletAddress, NetworkId networkId, Instant anchor) {
        SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                        SyncStatus.SourceKind.ONCHAIN,
                        walletAddress,
                        networkId.name()
                )
                .orElse(null);
        if (status == null || !status.isBackfillComplete()) {
            return false;
        }
        long currentHead = resolveCurrentBlock(networkId);
        Long checkpoint = status.getLastBlockSynced();
        if (checkpoint == null) {
            return false;
        }
        long fromBlock = checkpoint + 1;
        if (fromBlock > currentHead) {
            return false;
        }
        armOnChainWindow(status, walletAddress, networkId.name(), fromBlock, currentHead, anchor, "Refresh queued");
        return true;
    }

    private void armOnChainWindow(
            SyncStatus status,
            String walletAddress,
            String networkId,
            long fromBlock,
            long toBlock,
            Instant anchor,
            String bannerMessage
    ) {
        if (fromBlock > toBlock) {
            return;
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
        syncStatusRepository.save(target);
    }

    private boolean ensureInitialIntegrationWindow(UserSession.SessionIntegration integration, Instant anchor) {
        SyncStatus status = latestIntegrationStatus(integration).orElse(null);
        if (status != null && (status.isBackfillComplete() || hasActiveWindow(status))) {
            return false;
        }
        Instant fromTime = anchor.minus(integrationBackfillProperties.getHistoryYears() * 365L, ChronoUnit.DAYS);
        armIntegrationWindow(status, integration, fromTime, anchor, "Backfill queued");
        return true;
    }

    private boolean scheduleIntegrationRefresh(UserSession.SessionIntegration integration, Instant anchor) {
        SyncStatus status = latestIntegrationStatus(integration).orElse(null);
        if (status == null || !status.isBackfillComplete()) {
            return false;
        }
        Instant checkpoint = status.getLastSyncedAt() != null
                ? status.getLastSyncedAt().truncatedTo(ChronoUnit.SECONDS)
                : integration.getLastSyncAt() == null
                ? null
                : integration.getLastSyncAt().truncatedTo(ChronoUnit.SECONDS);
        if (checkpoint == null || !checkpoint.isBefore(anchor)) {
            return false;
        }
        armIntegrationWindow(status, integration, checkpoint, anchor, "Refresh queued");
        return true;
    }

    private void armIntegrationWindow(
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
            return;
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
        syncStatusRepository.save(target);
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
            int skippedTargets
    ) {
        public static PlanResult empty() {
            return new PlanResult(0, 0);
        }
    }
}
