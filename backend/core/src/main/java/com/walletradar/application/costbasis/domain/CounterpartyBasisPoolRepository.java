package com.walletradar.application.costbasis.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CounterpartyBasisPoolRepository extends MongoRepository<CounterpartyBasisPool, String> {

    List<CounterpartyBasisPool> findByUniverseId(String universeId);

    void deleteByUniverseId(String universeId);
}
