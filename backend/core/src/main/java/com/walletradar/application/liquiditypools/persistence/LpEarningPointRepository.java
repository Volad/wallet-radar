package com.walletradar.application.liquiditypools.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface LpEarningPointRepository extends MongoRepository<LpEarningPoint, String> {

    List<LpEarningPoint> findByCorrelationIdOrderByDayAsc(String correlationId);
}
