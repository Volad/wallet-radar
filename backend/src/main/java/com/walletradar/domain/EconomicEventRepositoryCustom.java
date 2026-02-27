package com.walletradar.domain;

import java.util.List;

/**
 * Custom repository methods using MongoTemplate (e.g. findDistinct).
 */
public interface EconomicEventRepositoryCustom {

    /** Distinct asset contracts for a wallet×network; used by current balance refresh token scope. */
    List<String> findDistinctAssetContractsByWalletAddressAndNetworkId(String walletAddress, NetworkId networkId);
}
