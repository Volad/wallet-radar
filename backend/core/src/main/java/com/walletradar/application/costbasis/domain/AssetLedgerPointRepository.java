package com.walletradar.application.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

/**
 * Persistence for immutable accounting replay timeline points.
 */
public interface AssetLedgerPointRepository extends MongoRepository<AssetLedgerPoint, String> {

    List<AssetLedgerPoint> findAllByAccountingUniverseIdAndAccountingFamilyIdentityOrderByBlockTimestampAscTransactionIndexAscReplaySequenceAsc(
            String accountingUniverseId,
            String accountingFamilyIdentity
    );

    /**
     * B-ETH-06: loads every ledger point sharing one of the given correlation ids within a universe,
     * across all accounting families. Scoping by {@code correlationId} (rather than the parked-out
     * {@code normalizedTransactionId}) captures cross-family settlements that land in a SEPARATE
     * transaction from the escrow/park leg (e.g. a DEX ETH→wstETH order: park tx settles wstETH in a
     * different tx sharing the correlation). No dedicated {@code correlationId} index is added: the
     * query is bound to a single universe and MongoDB satisfies it via the {@code accountingUniverseId}
     * leading prefix of the existing compound indexes, filtering {@code correlationId} during the
     * indexed universe scan. Zero RPC; filtered further in-memory to parked correlations.
     */
    List<AssetLedgerPoint> findAllByAccountingUniverseIdAndCorrelationIdIn(
            String accountingUniverseId,
            Collection<String> correlationIds
    );

    void deleteAllByAccountingUniverseId(String accountingUniverseId);

    long countByAccountingUniverseId(String accountingUniverseId);
}
