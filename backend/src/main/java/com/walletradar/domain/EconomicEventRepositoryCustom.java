package com.walletradar.domain;

import java.util.List;

/**
 * Custom repository methods using MongoTemplate (e.g. findDistinct).
 */
public interface EconomicEventRepositoryCustom {

    /** Distinct wallet addresses where flagCode matches. Used by DeferredPriceResolutionJob. */
    List<String> findDistinctWalletAddressesByFlagCode(FlagCode flagCode);

    /** Distinct asset contracts for a wallet×network; used by current balance refresh token scope. */
    List<String> findDistinctAssetContractsByWalletAddressAndNetworkId(String walletAddress, NetworkId networkId);
}
