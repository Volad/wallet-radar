package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for raw_transactions (ADR-020). Idempotent upsert on (txHash, networkId).
 * Used by RawFetchSegmentProcessor (Phase 1) and ClassificationProcessor (Phase 2).
 */
public interface RawTransactionRepository extends MongoRepository<RawTransaction, String> {

    /**
     * EVM: read raw transactions in block range for classification.
     */
    List<RawTransaction> findByWalletAddressAndNetworkIdAndBlockNumberBetweenOrderByBlockNumberAsc(
            String walletAddress, String networkId, long fromBlock, long toBlock);

    /**
     * Solana: read raw transactions in slot range for classification.
     */
    List<RawTransaction> findByWalletAddressAndNetworkIdAndSlotBetweenOrderBySlotAsc(
            String walletAddress, String networkId, long fromSlot, long toSlot);

    /**
     * Processor selects PENDING raw for classification (by block/slot ASC).
     */
    List<RawTransaction> findByWalletAddressAndNetworkIdAndClassificationStatusOrderByBlockNumberAsc(
            String walletAddress, String networkId, ClassificationStatus status);

    /**
     * Solana: PENDING by slot ASC.
     */
    List<RawTransaction> findByWalletAddressAndNetworkIdAndClassificationStatusOrderBySlotAsc(
            String walletAddress, String networkId, ClassificationStatus status);
}
