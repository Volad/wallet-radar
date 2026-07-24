package com.walletradar.application.lending.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LendingLivePositionSnapshotRepository extends MongoRepository<LendingLivePositionSnapshot, String> {

    Optional<LendingLivePositionSnapshot>
    findFirstBySessionIdAndProtocolKeyAndNetworkIdAndWalletAddressAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
            String sessionId, String protocolKey, String networkId, String walletAddress, Instant capturedAtFloor);

    /**
     * Recent live-position snapshots for the given sessions (uses the {@code sessionId} prefix of the
     * {@code lending_live_pos_session_group_latest_idx} compound index — no full scan). Callers reduce
     * to the latest snapshot per {@code (sessionId, protocolKey, networkId, walletAddress)} group.
     */
    List<LendingLivePositionSnapshot> findBySessionIdInAndCapturedAtGreaterThanEqual(
            Collection<String> sessionIds, Instant capturedAtFloor);

    Optional<LendingLivePositionSnapshot>
    findFirstByWalletAddressAndNetworkIdAndCapturedAtGreaterThanEqualOrderByCapturedAtDesc(
            String walletAddress, String networkId, Instant capturedAtFloor);
}
