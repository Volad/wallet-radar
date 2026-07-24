package com.walletradar.application.lending.application;

import com.walletradar.application.lending.persistence.LendingMarketRateSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Refreshes live market rates across every registered {@link LendingMarketRateReader}, dispatching
 * per discovered market by {@link LendingMarketRateReader#supports(String, String)} (WS-3). The
 * hardcoded {@code AAVE} protocol gate was removed so any protocol/network reader plugs in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LendingMarketRateRefreshService {

    private final LendingActiveMarketDiscoveryService activeMarketDiscoveryService;
    private final List<LendingMarketRateReader> rateReaders;
    private final LendingMarketRateSnapshotService snapshotService;

    public RefreshResult refreshActiveMarkets() {
        List<LendingActiveMarketDiscoveryService.ActiveMarket> activeMarkets = activeMarketDiscoveryService.discover();
        int saved = 0;
        int unavailable = 0;
        for (LendingActiveMarketDiscoveryService.ActiveMarket market : activeMarkets) {
            LendingMarketRateReader reader = rateReaders.stream()
                    .filter(candidate -> candidate.supports(market.protocol(), market.networkId()))
                    .findFirst()
                    .orElse(null);
            if (reader == null) {
                continue;
            }
            LendingMarketRateSnapshot snapshot = reader.collect(market).orElse(null);
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
