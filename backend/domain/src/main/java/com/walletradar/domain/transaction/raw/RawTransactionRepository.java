package com.walletradar.domain.transaction.raw;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Counts sibling raw transactions of the same wallet that carry the same jetton transfer
     * identity ({@code rawData.jettonTransfers.transaction_hash}) but sort strictly before the
     * given {@code txHash}. TON's async message model duplicates a single logical jetton transfer
     * across every trace transaction touching the wallet (jetton_transfer, excess, jetton_notify,
     * …). The normalization path books the transfer only on the canonical (lowest-{@code txHash})
     * sibling; when this count is {@code 0} the caller's raw is that canonical owner.
     */
    @Query(value = "{ 'networkId': ?0, 'walletAddress': ?1, "
            + "'rawData.jettonTransfers.transaction_hash': ?2, 'txHash': { $lt: ?3 } }",
            count = true)
    long countTonJettonFanoutSiblingsBefore(
            @Param("networkId") String networkId,
            @Param("walletAddress") String walletAddress,
            @Param("jettonTransactionHash") String jettonTransactionHash,
            @Param("txHash") String txHash
    );
}
