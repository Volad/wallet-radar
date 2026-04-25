package com.walletradar.ingestion.job.backfill;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.sync.BackfillSegment;
import com.walletradar.domain.sync.BackfillSegmentRepository;
import com.walletradar.domain.sync.SyncStatus;
import com.walletradar.domain.sync.SyncStatusRepository;
import com.walletradar.ingestion.config.BackfillSegmentConfiguration;
import com.walletradar.ingestion.config.BackfillSegmentsConfiguration;
import com.walletradar.ingestion.config.BackfillProperties;
import com.walletradar.ingestion.config.IngestionNetworkProperties;
import com.walletradar.ingestion.wallet.command.WalletBackfillPlanner;
import com.walletradar.integration.IntegrationBackfillPlanningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creates or replaces persisted backfill segments from sync_status windows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackfillJobPlanner implements WalletBackfillPlanner {

    private static final int MAX_SEGMENTS = 1000;

    private final SyncStatusRepository syncStatusRepository;
    private final BackfillSegmentRepository backfillSegmentRepository;
    private final BackfillProperties backfillProperties;
    private final IngestionNetworkProperties ingestionNetworkProperties;
    private final IntegrationBackfillPlanningService integrationBackfillPlanningService;

    public int planPendingSessionSources(UserSession session) {
        if (session == null) {
            return 0;
        }
        int plannedSegments = 0;
        for (UserSession.SessionWallet wallet : session.getWallets() == null ? List.<UserSession.SessionWallet>of() : session.getWallets()) {
            if (wallet == null || wallet.getAddress() == null || wallet.getAddress().isBlank()) {
                continue;
            }
            for (NetworkId networkId : wallet.getNetworks() == null ? List.<NetworkId>of() : wallet.getNetworks()) {
                SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                                SyncStatus.SourceKind.ONCHAIN,
                                wallet.getAddress(),
                                networkId.name()
                        )
                        .orElse(null);
                plannedSegments += planOnChainSource(status);
            }
        }
        for (UserSession.SessionIntegration integration : session.getIntegrations() == null ? List.<UserSession.SessionIntegration>of() : session.getIntegrations()) {
            if (integration == null
                    || integration.getStatus() == UserSession.IntegrationStatus.DISABLED
                    || integration.getIntegrationId() == null
                    || integration.getIntegrationId().isBlank()) {
                continue;
            }
            SyncStatus status = syncStatusRepository.findLatestByIntegrationId(integration.getIntegrationId()).orElse(null);
            plannedSegments += planIntegrationSource(session, integration, status);
        }
        return plannedSegments;
    }

    public int planScheduledSessionSources(
            UserSession session,
            Collection<String> onChainSyncStatusIds,
            Collection<String> integrationSyncStatusIds
    ) {
        if (session == null) {
            return 0;
        }
        int plannedSegments = 0;
        for (String syncStatusId : distinctIds(onChainSyncStatusIds)) {
            SyncStatus status = syncStatusRepository.findById(syncStatusId).orElse(null);
            plannedSegments += planOnChainSource(status);
        }
        if (integrationSyncStatusIds == null || integrationSyncStatusIds.isEmpty()) {
            return plannedSegments;
        }
        Map<String, UserSession.SessionIntegration> integrationsById = enabledIntegrations(session).stream()
                .collect(Collectors.toMap(
                        UserSession.SessionIntegration::getIntegrationId,
                        Function.identity(),
                        (left, right) -> right
                ));
        for (String syncStatusId : distinctIds(integrationSyncStatusIds)) {
            SyncStatus status = syncStatusRepository.findById(syncStatusId).orElse(null);
            if (status == null || status.getIntegrationId() == null) {
                continue;
            }
            plannedSegments += planIntegrationSource(session, integrationsById.get(status.getIntegrationId()), status);
        }
        return plannedSegments;
    }

    @Override
    public int planPendingOnChainSources(String walletAddress, List<NetworkId> networks) {
        if (walletAddress == null || walletAddress.isBlank()) {
            return 0;
        }
        int plannedSegments = 0;
        List<NetworkId> targetNetworks = networks == null || networks.isEmpty()
                ? List.of(NetworkId.values())
                : networks;
        for (NetworkId networkId : targetNetworks) {
            SyncStatus status = syncStatusRepository.findOnChainByWalletAddressAndNetworkId(
                            SyncStatus.SourceKind.ONCHAIN,
                            walletAddress,
                            networkId.name()
                    )
                    .orElse(null);
            plannedSegments += planOnChainSource(status);
        }
        return plannedSegments;
    }

    public int planOnChainSyncStatus(String syncStatusId) {
        if (syncStatusId == null || syncStatusId.isBlank()) {
            return 0;
        }
        return planOnChainSource(syncStatusRepository.findById(syncStatusId.trim()).orElse(null));
    }

    private int planOnChainSource(SyncStatus status) {
        if (status == null
                || status.getStatus() != SyncStatus.SyncStatusValue.PENDING
                || status.getId() == null
                || status.getWindowFromBlock() == null
                || status.getWindowToBlock() == null
                || status.getWindowFromBlock() > status.getWindowToBlock()) {
            return 0;
        }
        List<BackfillSegment> existing = backfillSegmentRepository.findBySyncStatusIdOrderBySegmentIndexAsc(status.getId());
        if (matchesOnChainWindow(existing, status)) {
            return 0;
        }
        if (!existing.isEmpty()) {
            backfillSegmentRepository.deleteBySyncStatusId(status.getId());
        }
        List<BackfillSegment> segments = buildOnChainSegments(status, Instant.now());
        if (!segments.isEmpty()) {
            backfillSegmentRepository.saveAll(segments);
        }
        log.info(
                "Planned on-chain segments: wallet={}, network={}, syncStatusId={}, segments={}, fromBlock={}, toBlock={}",
                status.getWalletAddress(),
                status.getNetworkId(),
                status.getId(),
                segments.size(),
                status.getWindowFromBlock(),
                status.getWindowToBlock()
        );
        return segments.size();
    }

    private List<UserSession.SessionIntegration> enabledIntegrations(UserSession session) {
        if (session == null || session.getIntegrations() == null || session.getIntegrations().isEmpty()) {
            return List.of();
        }
        return session.getIntegrations().stream()
                .filter(Objects::nonNull)
                .filter(integration -> integration.getStatus() != UserSession.IntegrationStatus.DISABLED)
                .filter(integration -> integration.getIntegrationId() != null && !integration.getIntegrationId().isBlank())
                .toList();
    }

    private int planIntegrationSource(UserSession session, UserSession.SessionIntegration integration, SyncStatus status) {
        if (session == null
                || integration == null
                || status == null
                || status.getStatus() != SyncStatus.SyncStatusValue.PENDING
                || status.getWindowFromTime() == null
                || status.getWindowToTime() == null
                || !status.getWindowFromTime().isBefore(status.getWindowToTime())) {
            return 0;
        }
        List<BackfillSegment> existing = backfillSegmentRepository.findByIntegrationIdOrderByUpdatedAtAsc(integration.getIntegrationId());
        if (matchesIntegrationWindow(existing, status)) {
            return 0;
        }
        UserSession.IntegrationSyncState syncState = integrationBackfillPlanningService.replanWindowBackfill(
                session.getId(),
                integration,
                status.getWindowFromTime(),
                status.getWindowToTime(),
                status.getId()
        );
        integration.setSyncState(syncState);
        integration.setStatus(UserSession.IntegrationStatus.BACKFILLING);
        integration.setUpdatedAt(Instant.now());
        log.info(
                "Planned integration segments: integrationId={}, provider={}, syncStatusId={}, segments={}, from={}, to={}",
                integration.getIntegrationId(),
                integration.getProvider(),
                status.getId(),
                syncState.getTotalSegments(),
                status.getWindowFromTime(),
                status.getWindowToTime()
        );
        return syncState.getTotalSegments() == null ? 0 : syncState.getTotalSegments();
    }

    private List<BackfillSegment> buildOnChainSegments(SyncStatus status, Instant plannedAt) {
        long fromBlock = status.getWindowFromBlock();
        long toBlock = status.getWindowToBlock();
        long totalBlocks = toBlock - fromBlock + 1;
        if (totalBlocks <= 0) {
            return List.of();
        }
        int segmentCount = resolveSegmentCount(status.getNetworkId(), totalBlocks);
        long baseSize = totalBlocks / segmentCount;
        long remainder = totalBlocks % segmentCount;
        long cursor = fromBlock;
        List<BackfillSegment> segments = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            long size = baseSize + (index < remainder ? 1 : 0);
            if (size <= 0) {
                continue;
            }
            BackfillSegment segment = new BackfillSegment();
            segment.setId(status.getId() + ":" + index);
            segment.setSourceKind(BackfillSegment.SourceKind.ONCHAIN);
            segment.setSegmentKind(BackfillSegment.SegmentKind.BLOCK_RANGE);
            segment.setSyncStatusId(status.getId());
            segment.setWalletAddress(status.getWalletAddress());
            segment.setNetworkId(status.getNetworkId());
            segment.setSegmentIndex(index);
            segment.setFromBlock(cursor);
            segment.setToBlock(cursor + size - 1);
            segment.setStatus(BackfillSegment.SegmentStatus.PENDING);
            segment.setProgressPct(0);
            segment.setRetryCount(0);
            segment.setUpdatedAt(plannedAt);
            segments.add(segment);
            cursor += size;
        }
        return List.copyOf(segments);
    }

    private int resolveSegmentCount(String networkId, long totalBlocks) {
        SegmentPlanningProfile profile = resolveSegmentPlanningProfile(networkId);
        long targetBlocksPerSegment = Math.max(1L, profile.targetBlocksPerSegment());
        long rawCount = (totalBlocks + targetBlocksPerSegment - 1) / targetBlocksPerSegment;
        return (int) Math.max(1L, Math.min(MAX_SEGMENTS, rawCount));
    }

    private SegmentPlanningProfile resolveSegmentPlanningProfile(String networkId) {
        BackfillSegmentsConfiguration segmentsConfig = backfillProperties.getSegments();
        BackfillSegmentConfiguration defaults = segmentsConfig != null
                ? segmentsConfig.getDefaults()
                : null;
        BackfillSegmentConfiguration byRpc = segmentsConfig != null
                ? segmentsConfig.getByRpc()
                : null;
        int defaultParallelSegments = positiveOrDefault(
                defaults != null ? defaults.getParallelSegments() : null,
                BackfillSegmentConfiguration.DEFAULT_PARALLEL_SEGMENTS
        );
        long windowBlocks = resolveWindowBlocksForNetwork(networkId);
        IngestionNetworkProperties.NetworkIngestionEntry networkEntry = ingestionNetworkProperties.getNetwork() == null
                ? null
                : ingestionNetworkProperties.getNetwork().get(networkId);
        boolean rpcProfile = networkEntry != null
                && networkEntry.getSyncMethod() == IngestionNetworkProperties.NetworkIngestionEntry.SyncMethod.RPC;
        int effectiveParallelSegments = rpcProfile
                ? positiveOrDefault(
                        byRpc != null ? byRpc.getParallelSegments() : null,
                        defaultParallelSegments
                )
                : defaultParallelSegments;
        long targetBlocksPerSegment = Math.max(1L, windowBlocks / Math.max(1, effectiveParallelSegments));
        return new SegmentPlanningProfile(targetBlocksPerSegment);
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

    private boolean matchesOnChainWindow(List<BackfillSegment> segments, SyncStatus status) {
        if (segments == null || segments.isEmpty()) {
            return false;
        }
        Long minFrom = segments.stream()
                .map(BackfillSegment::getFromBlock)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Long maxTo = segments.stream()
                .map(BackfillSegment::getToBlock)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return Objects.equals(minFrom, status.getWindowFromBlock())
                && Objects.equals(maxTo, status.getWindowToBlock());
    }

    private boolean matchesIntegrationWindow(List<BackfillSegment> segments, SyncStatus status) {
        if (segments == null || segments.isEmpty()) {
            return false;
        }
        Instant minFrom = segments.stream()
                .map(BackfillSegment::getFromTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Instant maxTo = segments.stream()
                .map(BackfillSegment::getToTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return Objects.equals(minFrom, status.getWindowFromTime())
                && Objects.equals(maxTo, status.getWindowToTime());
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static List<String> distinctIds(Collection<String> syncStatusIds) {
        if (syncStatusIds == null || syncStatusIds.isEmpty()) {
            return List.of();
        }
        return syncStatusIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
    }

    private record SegmentPlanningProfile(long targetBlocksPerSegment) {
    }
}
