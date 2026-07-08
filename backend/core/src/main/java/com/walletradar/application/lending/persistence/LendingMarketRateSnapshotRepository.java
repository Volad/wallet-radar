package com.walletradar.application.lending.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Optional;

public interface LendingMarketRateSnapshotRepository extends MongoRepository<LendingMarketRateSnapshot, String> {

    Optional<LendingMarketRateSnapshot> findFirstBySessionIdAndProtocolAndNetworkIdAndMarketKeyAndUnderlyingSymbolAndSideAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
            String sessionId,
            String protocol,
            String networkId,
            String marketKey,
            String underlyingSymbol,
            String side,
            Instant capturedAt
    );

    Optional<LendingMarketRateSnapshot> findTopBySessionIdAndProtocolAndNetworkIdAndWalletAddressOrderByCapturedAtDesc(
            String sessionId,
            String protocol,
            String networkId,
            String walletAddress
    );
}
