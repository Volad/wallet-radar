package com.walletradar.domain.transaction.raw;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for immutable on-chain raw evidence.
 */
public interface RawTransactionRepository extends MongoRepository<RawTransaction, String> {

    List<RawTransaction> findAllByTxHashAndNetworkId(String txHash, String networkId);

    Optional<RawTransaction> findByTxHashAndNetworkIdAndWalletAddress(
            String txHash,
            String networkId,
            String walletAddress
    );
}
