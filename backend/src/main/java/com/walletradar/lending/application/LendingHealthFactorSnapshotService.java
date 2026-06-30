package com.walletradar.lending.application;

import com.walletradar.lending.persistence.LendingHealthFactorSnapshot;
import com.walletradar.lending.persistence.LendingHealthFactorSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LendingHealthFactorSnapshotService {

    static final Duration FRESH_WINDOW = Duration.ofMinutes(5);
    static final String LIVE_PROTOCOL = "LIVE_PROTOCOL";

    private final LendingHealthFactorSnapshotRepository repository;

    public Optional<LendingHealthFactorSnapshot> latestFresh(
            String sessionId,
            String protocolKey,
            String networkId,
            String walletAddress
    ) {
        if (sessionId == null || protocolKey == null || networkId == null || walletAddress == null) {
            return Optional.empty();
        }
        return repository.findFirstBySessionIdAndProtocolKeyAndNetworkIdAndWalletAddressAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
                sessionId,
                protocolKey,
                networkId,
                walletAddress,
                Instant.now().minus(FRESH_WINDOW)
        ).filter(snapshot -> LIVE_PROTOCOL.equals(snapshot.getSource()) && snapshot.getHealthFactor() != null);
    }

    public Optional<LendingHealthFactorSnapshot> latest(
            String sessionId,
            String protocolKey,
            String networkId,
            String walletAddress
    ) {
        if (sessionId == null || protocolKey == null || networkId == null || walletAddress == null) {
            return Optional.empty();
        }
        return repository.findFirstBySessionIdAndProtocolKeyAndNetworkIdAndWalletAddressOrderByCapturedAtDesc(
                sessionId,
                protocolKey,
                networkId,
                walletAddress
        );
    }

    public LendingHealthFactorSnapshot save(LendingHealthFactorSnapshot snapshot) {
        return repository.save(snapshot);
    }
}
