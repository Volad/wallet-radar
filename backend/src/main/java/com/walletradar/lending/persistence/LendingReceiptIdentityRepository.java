package com.walletradar.lending.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface LendingReceiptIdentityRepository extends MongoRepository<LendingReceiptIdentityDocument, String> {

    Optional<LendingReceiptIdentityDocument> findByNetworkIdAndContractAddress(String networkId, String contractAddress);
}
