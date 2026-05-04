package com.walletradar.lending.application;

import com.walletradar.lending.persistence.LendingMarketRateSnapshot;
import com.walletradar.lending.persistence.LendingMarketRateSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LendingMarketRateSnapshotService {

    private static final Duration FRESH_WINDOW = Duration.ofHours(36);

    private final LendingMarketRateSnapshotRepository repository;

    public Optional<LendingMarketRateSnapshot> latestFresh(
            String sessionId,
            String protocol,
            String networkId,
            String marketKey,
            String underlyingSymbol,
            String side
    ) {
        if (sessionId == null || protocol == null || networkId == null || marketKey == null
                || underlyingSymbol == null || side == null) {
            return Optional.empty();
        }
        return repository.findFirstBySessionIdAndProtocolAndNetworkIdAndMarketKeyAndUnderlyingSymbolAndSideAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
                sessionId,
                protocol,
                networkId,
                marketKey,
                underlyingSymbol,
                side,
                Instant.now().minus(FRESH_WINDOW)
        );
    }

    public LendingMarketRateSnapshot save(LendingMarketRateSnapshot snapshot) {
        return repository.save(snapshot);
    }
}
