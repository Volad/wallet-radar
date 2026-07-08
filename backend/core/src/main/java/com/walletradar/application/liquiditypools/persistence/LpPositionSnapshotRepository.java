package com.walletradar.application.liquiditypools.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface LpPositionSnapshotRepository extends MongoRepository<LpPositionSnapshot, String> {

    List<LpPositionSnapshot> findByUniverseId(String universeId);

    Optional<LpPositionSnapshot> findByCorrelationId(String correlationId);
}
