package com.walletradar.lending.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Optional;

public interface LendingHealthFactorSnapshotRepository extends MongoRepository<LendingHealthFactorSnapshot, String> {

    Optional<LendingHealthFactorSnapshot> findFirstBySessionIdAndProtocolKeyAndNetworkIdAndWalletAddressAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
            String sessionId,
            String protocolKey,
            String networkId,
            String walletAddress,
            Instant capturedAt
    );

    Optional<LendingHealthFactorSnapshot> findFirstBySessionIdAndProtocolKeyAndNetworkIdAndWalletAddressOrderByCapturedAtDesc(
            String sessionId,
            String protocolKey,
            String networkId,
            String walletAddress
    );
}
