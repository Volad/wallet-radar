package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for normalized_transactions canonical pipeline (ADR-025).
 */
public interface NormalizedTransactionRepository extends MongoRepository<NormalizedTransaction, String>, NormalizedTransactionRepositoryCustom {

    Optional<NormalizedTransaction> findByTxHashAndNetworkIdAndWalletAddress(
            String txHash, NetworkId networkId, String walletAddress);

    Optional<NormalizedTransaction> findByClientId(String clientId);

    List<NormalizedTransaction> findByStatusOrderByBlockTimestampAsc(NormalizedTransactionStatus status);

    List<NormalizedTransaction> findByWalletAddressAndStatusOrderByBlockTimestampAsc(
            String walletAddress, NormalizedTransactionStatus status);

    List<NormalizedTransaction> findByWalletAddressAndNetworkIdAndStatusOrderByBlockTimestampAsc(
            String walletAddress, NetworkId networkId, NormalizedTransactionStatus status);
}
