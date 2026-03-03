package com.walletradar.domain.transaction.normalized;

import com.walletradar.domain.common.NetworkId;

import java.util.List;

/**
 * Custom queries for normalized_transactions.
 */
public interface NormalizedTransactionRepositoryCustom {

    List<String> findDistinctWalletAddressesByStatus(NormalizedTransactionStatus status);

    List<String> findDistinctAssetContractsByWalletAddressAndNetworkIdAndStatus(
            String walletAddress, NetworkId networkId, NormalizedTransactionStatus status);
}
