package com.walletradar.application.lending.application;

import com.walletradar.application.lending.view.*;
import com.walletradar.application.lending.config.LendingMarketRateProperties;
import com.walletradar.application.lending.persistence.LendingHealthFactorSnapshot;
import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LendingOnDemandRefreshService {

    private static final String AAVE_PROTOCOL_KEY = "Aave";

    private final SessionLendingQueryService lendingQueryService;
    private final LendingActiveMarketDiscoveryService activeMarketDiscoveryService;
    private final LendingAaveV3HealthCollector healthCollector;
    private final LendingAaveV3MarketRateCollector marketRateCollector;
    private final LendingHealthFactorSnapshotService healthSnapshotService;
    private final LendingMarketRateSnapshotService marketRateSnapshotService;
    private final LendingGroupRefreshStateService refreshStateService;
    private final LendingMarketRateProperties marketRateProperties;

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

        if (shouldRefreshHealth(group, marketRateProperties)) {
            LendingAaveV3HealthCollector.ActiveBorrowGroup borrowGroup =
                    new LendingAaveV3HealthCollector.ActiveBorrowGroup(
                            sessionId,
                            AAVE_PROTOCOL_KEY,
                            group.networkId(),
                            group.walletAddress()
                    );
            LendingHealthFactorSnapshot snapshot = healthCollector.collect(borrowGroup).orElse(null);
            if (snapshot == null) {
                skipped++;
            } else {
                healthSnapshotService.save(snapshot);
                saved++;
            }
        }

        List<LendingActiveMarketDiscoveryService.ActiveMarket> markets =
                activeMarketsForGroup(sessionId, group);
        for (LendingActiveMarketDiscoveryService.ActiveMarket market : markets) {
            if (!LendingProtocolNameSupport.AAVE.equalsIgnoreCase(market.protocol())) {
                skipped++;
                continue;
            }
            LendingMarketRateSnapshot snapshot = marketRateCollector.collect(market).orElse(null);
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

    private static boolean shouldRefreshHealth(
            LendingGroupView group,
            LendingMarketRateProperties marketRateProperties
    ) {
        if (!AAVE_PROTOCOL_KEY.equalsIgnoreCase(group.protocol())) {
            return false;
        }
        if (group.borrowUsd() == null || group.borrowUsd().signum() <= 0) {
            return false;
        }
        String networkId = group.networkId() == null ? "" : group.networkId().trim();
        return marketRateProperties.isAaveV3HealthFetchEnabled(networkId);
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
