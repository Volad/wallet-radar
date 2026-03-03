package com.walletradar.domain.transaction.raw;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for raw_transactions (ADR-020). Idempotent upsert on (txHash, networkId).
 * Used by RawFetchSegmentProcessor (Phase 1) and RawTxNormalizationJob (ADR-026).
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
    List<RawTransaction> findByWalletAddressAndNetworkIdAndNormalizationStatusOrderByBlockNumberAsc(
            String walletAddress, String networkId, NormalizationStatus status);

    /**
     * EVM: PENDING raw with Pageable for batch processing (ADR-021).
     */
    List<RawTransaction> findByWalletAddressAndNetworkIdAndNormalizationStatusOrderByBlockNumberAsc(
            String walletAddress, String networkId, NormalizationStatus status, Pageable pageable);

    /**
     * Solana: PENDING by slot ASC.
     */
    List<RawTransaction> findByWalletAddressAndNetworkIdAndNormalizationStatusOrderBySlotAsc(
            String walletAddress, String networkId, NormalizationStatus status);

    /**
     * Solana: PENDING raw with Pageable for batch processing (ADR-021).
     */
    List<RawTransaction> findByWalletAddressAndNetworkIdAndNormalizationStatusOrderBySlotAsc(
            String walletAddress, String networkId, NormalizationStatus status, Pageable pageable);

    /**
     * Global PENDING read for standalone classifier job (ADR-021).
     */
    List<RawTransaction> findByNormalizationStatus(NormalizationStatus status, Pageable pageable);

    /**
     * EVM-only PENDING read for v2 normalization job (SOLANA excluded).
     */
    List<RawTransaction> findByNormalizationStatusAndNetworkIdNot(
            NormalizationStatus status, String networkId, Pageable pageable);
}
