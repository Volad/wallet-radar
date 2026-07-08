package com.walletradar.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for immutable accounting replay timeline points.
 */
public interface AssetLedgerPointRepository extends MongoRepository<AssetLedgerPoint, String> {

    List<AssetLedgerPoint> findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
            String accountingUniverseId,
            String accountingFamilyIdentity
    );

    void deleteAllByAccountingUniverseId(String accountingUniverseId);

    long countByAccountingUniverseId(String accountingUniverseId);
}
