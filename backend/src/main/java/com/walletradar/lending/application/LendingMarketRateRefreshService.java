package com.walletradar.lending.application;

import com.walletradar.lending.persistence.LendingMarketRateSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LendingMarketRateRefreshService {

    private final LendingActiveMarketDiscoveryService activeMarketDiscoveryService;
    private final LendingAaveV3MarketRateCollector aaveV3MarketRateCollector;
    private final LendingMarketRateSnapshotService snapshotService;

    public RefreshResult refreshActiveMarkets() {
        List<LendingActiveMarketDiscoveryService.ActiveMarket> activeMarkets = activeMarketDiscoveryService.discover();
        int saved = 0;
        int unavailable = 0;
        for (LendingActiveMarketDiscoveryService.ActiveMarket market : activeMarkets) {
            if (!"Aave".equalsIgnoreCase(market.protocol())) {
                continue;
            }
            LendingMarketRateSnapshot snapshot = aaveV3MarketRateCollector.collect(market).orElse(null);
            if (snapshot == null) {
                continue;
            }
            snapshotService.save(snapshot);
            if (LendingMarketRateStatus.UNAVAILABLE.equals(snapshot.getRateStatus())) {
                unavailable++;
            } else {
                saved++;
            }
        }
        log.info("Lending market rate refresh complete activeMarkets={} saved={} unavailable={}",
                activeMarkets.size(), saved, unavailable);
        return new RefreshResult(activeMarkets.size(), saved, unavailable);
    }

    public record RefreshResult(int activeMarkets, int saved, int unavailable) {
    }
}
