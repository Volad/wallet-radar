package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for sync_status per (walletAddress, networkId). Used by BackfillJobRunner and API.
 */
public interface SyncStatusRepository extends MongoRepository<SyncStatus, String> {

    List<SyncStatus> findByWalletAddress(String walletAddress);

    Optional<SyncStatus> findByWalletAddressAndNetworkId(String walletAddress, String networkId);

    /** Incomplete backfills (resume after restart). */
    List<SyncStatus> findByStatusIn(Set<SyncStatus.SyncStatusValue> statuses);
}
