package com.walletradar.application.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface LpReceiptBasisPoolRepository extends MongoRepository<LpReceiptBasisPool, String> {

    List<LpReceiptBasisPool> findByUniverseId(String universeId);

    void deleteByUniverseId(String universeId);
}
