package com.walletradar.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * MongoTemplate-backed custom queries for normalized_transactions.
 */
@Repository
@RequiredArgsConstructor
public class NormalizedTransactionRepositoryImpl implements NormalizedTransactionRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public List<String> findDistinctWalletAddressesByStatus(NormalizedTransactionStatus status) {
        Query query = new Query(where("status").is(status));
        return mongoTemplate.findDistinct(query, "walletAddress", NormalizedTransaction.class, String.class);
    }

    @Override
    public List<String> findDistinctAssetContractsByWalletAddressAndNetworkIdAndStatus(
            String walletAddress, NetworkId networkId, NormalizedTransactionStatus status) {
        Query query = new Query(where("walletAddress").is(walletAddress)
                .and("networkId").is(networkId)
                .and("status").is(status));
        return mongoTemplate.findDistinct(query, "legs.assetContract", NormalizedTransaction.class, String.class);
    }
}
