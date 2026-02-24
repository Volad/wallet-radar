package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for asset_positions. Used by AvcoEngine (costbasis) and snapshot/API read path.
 */
public interface AssetPositionRepository extends MongoRepository<AssetPosition, String> {

    Optional<AssetPosition> findByWalletAddressAndNetworkIdAndAssetContract(
            String walletAddress, String networkId, String assetContract);

    List<AssetPosition> findByWalletAddressIn(List<String> walletAddresses);

    void deleteByWalletAddressAndNetworkIdAndAssetContract(
            String walletAddress, String networkId, String assetContract);
}
