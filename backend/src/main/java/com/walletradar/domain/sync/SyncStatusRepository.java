package com.walletradar.domain.sync;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for sync_status per (walletAddress, networkId). Used by backfill package and API.
 */
public interface SyncStatusRepository extends MongoRepository<SyncStatus, String> {

    List<SyncStatus> findByWalletAddress(String walletAddress);

    List<SyncStatus> findByWalletAddressIn(List<String> walletAddresses);

    Optional<SyncStatus> findByWalletAddressAndNetworkId(String walletAddress, String networkId);

    /** Incomplete backfills (resume after restart). */
    List<SyncStatus> findByStatusIn(Set<SyncStatus.SyncStatusValue> statuses);

    List<SyncStatus> findBySourceKindAndStatusIn(
            SyncStatus.SourceKind sourceKind,
            Set<SyncStatus.SyncStatusValue> statuses
    );

    @Query("{'$or':[{'sourceKind': ?0},{'sourceKind': {$exists:false}},{'sourceKind': null}], 'walletAddress': {'$in': ?1}}")
    List<SyncStatus> findOnChainByWalletAddressIn(SyncStatus.SourceKind sourceKind, List<String> walletAddresses);

    @Query("{'$or':[{'sourceKind': ?0},{'sourceKind': {$exists:false}},{'sourceKind': null}], 'walletAddress': ?1, 'networkId': ?2}")
    Optional<SyncStatus> findOnChainByWalletAddressAndNetworkId(
            SyncStatus.SourceKind sourceKind,
            String walletAddress,
            String networkId
    );

    @Query("{'$or':[{'sourceKind': ?0},{'sourceKind': {$exists:false}},{'sourceKind': null}], 'status': {'$in': ?1}}")
    List<SyncStatus> findOnChainByStatusIn(SyncStatus.SourceKind sourceKind, Set<SyncStatus.SyncStatusValue> statuses);

    List<SyncStatus> findAllByIntegrationIdOrderByUpdatedAtDescIdDesc(String integrationId);

    default Optional<SyncStatus> findLatestByIntegrationId(String integrationId) {
        List<SyncStatus> rows = findAllByIntegrationIdOrderByUpdatedAtDescIdDesc(integrationId);
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.get(0));
    }

    void deleteByIntegrationId(String integrationId);
}
