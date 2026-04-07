package com.walletradar.domain.transaction.normalized;

import com.walletradar.domain.common.NetworkId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for canonical normalized accounting documents.
 */
public interface NormalizedTransactionRepository extends MongoRepository<NormalizedTransaction, String> {

    Optional<NormalizedTransaction> findByTxHashAndNetworkIdAndWalletAddress(
            String txHash,
            NetworkId networkId,
            String walletAddress
    );

    Optional<NormalizedTransaction> findByClientId(String clientId);

    List<NormalizedTransaction> findAllByTxHashAndNetworkIdAndSource(
            String txHash,
            NetworkId networkId,
            NormalizedTransactionSource source
    );

    List<NormalizedTransaction> findAllByStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
            NormalizedTransactionStatus status
    );

    List<NormalizedTransaction> findAllByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
            Collection<String> walletAddresses,
            NormalizedTransactionStatus status
    );

    List<NormalizedTransaction> findAllByCorrelationIdInAndSourceAndWalletAddressAndNetworkId(
            Collection<String> correlationIds,
            NormalizedTransactionSource source,
            String walletAddress,
            NetworkId networkId
    );

    List<NormalizedTransaction> findAllByMatchedCounterpartyAndWalletAddressAndSource(
            String matchedCounterparty,
            String walletAddress,
            NormalizedTransactionSource source
    );

    List<NormalizedTransaction> findAllByMatchedCounterpartyAndSource(
            String matchedCounterparty,
            NormalizedTransactionSource source
    );
}
