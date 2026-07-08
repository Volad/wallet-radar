package com.walletradar.application.liquiditypools.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LpPoolDepthCacheRepository extends MongoRepository<LpPoolDepthCache, String> {
}
