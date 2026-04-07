package com.walletradar.domain.transaction.raw;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Persistence for immutable on-chain raw evidence.
 */
public interface RawTransactionRepository extends MongoRepository<RawTransaction, String> {

    List<RawTransaction> findAllByTxHashAndNetworkId(String txHash, String networkId);
}
