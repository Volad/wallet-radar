package com.walletradar.application.lending.application;

import com.walletradar.application.lending.view.*;
import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;
import com.walletradar.application.lending.spi.LivePositionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit user-triggered lending refresh. Refactored (WS-3) to dispatch to the live-position and
 * market-rate reader SPIs by {@link com.walletradar.application.lending.spi.LendingLivePositionReader#supports}
 * / {@link LendingMarketRateReader#supports} instead of the hardcoded Aave gate, so any protocol/network
 * (Aave, Jupiter Lend, …) refreshes. It never applies the borrow-liability true-up (that stays
 * background-only in {@link LendingHealthFactorRefreshService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LendingOnDemandRefreshService {

    private final SessionLendingQueryService lendingQueryService;
    private final LendingActiveMarketDiscoveryService activeMarketDiscoveryService;
    private final LendingHealthFactorRefreshService healthFactorRefreshService;
    private final List<LendingMarketRateReader> rateReaders;
    private final LendingMarketRateSnapshotService marketRateSnapshotService;
    private final LendingGroupRefreshStateService refreshStateService;

    public RefreshResult refreshGroupWithState(String sessionId, String groupId) {
        return lendingQueryService.findSessionLending(sessionId)
                .map(view -> view.groups().stream()
                        .filter(group -> groupId.equalsIgnoreCase(group.id()))
                        .findFirst()
                        .map(group -> refreshSingleGroupWithState(sessionId, group))
                        .orElse(new RefreshResult(0, 0, 0)))
                .orElse(new RefreshResult(0, 0, 0));
    }

    public RefreshResult refreshAllOpenGroupsWithState(String sessionId) {
        return lendingQueryService.findSessionLending(sessionId)
                .map(view -> {
                    int groups = 0;
                    int saved = 0;
                    int skipped = 0;
                    for (LendingGroupView group : view.groups()) {
                        if (!"OPEN".equals(group.status())) {
                            continue;
                        }
                        groups++;
                        RefreshResult result = refreshSingleGroupWithState(sessionId, group);
                        saved += result.saved();
                        skipped += result.skipped();
                    }
                    log.info("Lending session refresh complete sessionId={} groups={} saved={} skipped={}",
                            sessionId, groups, saved, skipped);
                    return new RefreshResult(groups, saved, skipped);
                })
                .orElse(new RefreshResult(0, 0, 0));
    }

    private RefreshResult refreshSingleGroupWithState(
            String sessionId,
            LendingGroupView group
    ) {
        String groupId = group.id();
        refreshStateService.markUpdating(groupId);
        try {
            RefreshResult result = refreshSingleGroup(sessionId, group);
            refreshStateService.markSynced(groupId);
            return result;
        } catch (Exception error) {
            refreshStateService.markFailed(groupId, error.toString());
            throw error;
        }
    }

    private RefreshResult refreshSingleGroup(
            String sessionId,
            LendingGroupView group
    ) {
        if (!"OPEN".equals(group.status())) {
            return new RefreshResult(0, 0, 1);
        }
        int saved = 0;
        int skipped = 0;

        if (shouldRefreshHealth(group)) {
            String networkId = group.networkId() == null ? "" : group.networkId().trim().toUpperCase(Locale.ROOT);
            LivePositionRequest request = new LivePositionRequest(
                    sessionId, group.protocol(), networkId, group.walletAddress());
            if (healthFactorRefreshService.refreshPositionSnapshots(request).isPresent()) {
                saved++;
            } else {
                skipped++;
            }
        }

        List<LendingActiveMarketDiscoveryService.ActiveMarket> markets =
                activeMarketsForGroup(sessionId, group);
        for (LendingActiveMarketDiscoveryService.ActiveMarket market : markets) {
            LendingMarketRateReader reader = rateReaders.stream()
                    .filter(candidate -> candidate.supports(market.protocol(), market.networkId()))
                    .findFirst()
                    .orElse(null);
            if (reader == null) {
                skipped++;
                continue;
            }
            LendingMarketRateSnapshot snapshot = reader.collect(market).orElse(null);
            if (snapshot == null) {
                skipped++;
                continue;
            }
            marketRateSnapshotService.save(snapshot);
            saved++;
        }

        return new RefreshResult(1, saved, skipped);
    }

    private List<LendingActiveMarketDiscoveryService.ActiveMarket> activeMarketsForGroup(
            String sessionId,
            LendingGroupView group
    ) {
        String normalizedWallet = normalizeAddress(group.walletAddress());
        String normalizedNetwork = group.networkId() == null ? "" : group.networkId().trim().toUpperCase(Locale.ROOT);
        String normalizedProtocol = group.protocol() == null ? "" : group.protocol().trim();
        Map<String, LendingActiveMarketDiscoveryService.ActiveMarket> markets = new LinkedHashMap<>();
        for (LendingActiveMarketDiscoveryService.ActiveMarket market : activeMarketDiscoveryService.discover()) {
            if (!sessionId.equals(market.sessionId())) {
                continue;
            }
            if (!normalizedProtocol.equalsIgnoreCase(market.protocol())) {
                continue;
            }
            if (!normalizedNetwork.equalsIgnoreCase(market.networkId())) {
                continue;
            }
            if (!normalizedWallet.equals(normalizeAddress(market.walletAddress()))) {
                continue;
            }
            markets.putIfAbsent(marketKey(market), market);
        }
        return markets.values().stream().toList();
    }

    private static String marketKey(LendingActiveMarketDiscoveryService.ActiveMarket market) {
        return String.join(":",
                market.sessionId(),
                market.protocol(),
                market.networkId(),
                market.marketKey(),
                market.underlyingSymbol(),
                market.side(),
                market.assetContract()
        ).toLowerCase(Locale.ROOT);
    }

    private static boolean shouldRefreshHealth(LendingGroupView group) {
        // Protocol-agnostic (WS-3): refresh any OPEN borrow group; reader dispatch + per-network
        // enablement is decided by the readers' supports()/read() (Aave config gate, Jupiter enabled).
        return group.borrowUsd() != null && group.borrowUsd().signum() > 0;
    }

    private static String normalizeAddress(String address) {
        if (address == null) {
            return "";
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }

    public record RefreshResult(int groups, int saved, int skipped) {
    }
}
