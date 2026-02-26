package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for on_chain_balances keyed by (walletAddress, networkId, assetContract).
 */
public interface OnChainBalanceRepository extends MongoRepository<OnChainBalance, String> {

    Optional<OnChainBalance> findByWalletAddressAndNetworkIdAndAssetContract(
            String walletAddress, String networkId, String assetContract);

    List<OnChainBalance> findByWalletAddressAndNetworkId(String walletAddress, String networkId);
}

