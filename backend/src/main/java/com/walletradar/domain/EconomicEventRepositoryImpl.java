package com.walletradar.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Implementation of EconomicEventRepositoryCustom using MongoTemplate.findDistinct.
 */
@Repository
@RequiredArgsConstructor
public class EconomicEventRepositoryImpl implements EconomicEventRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<String> findDistinctWalletAddressesByFlagCode(FlagCode flagCode) {
        Query query = new Query(where("flagCode").is(flagCode));
        return mongoTemplate.findDistinct(query, "walletAddress", EconomicEvent.class, String.class);
    }

    @Override
    public List<String> findDistinctAssetContractsByWalletAddressAndNetworkId(String walletAddress, NetworkId networkId) {
        Query query = new Query(where("walletAddress").is(walletAddress).and("networkId").is(networkId));
        return mongoTemplate.findDistinct(query, "assetContract", EconomicEvent.class, String.class);
    }
}
