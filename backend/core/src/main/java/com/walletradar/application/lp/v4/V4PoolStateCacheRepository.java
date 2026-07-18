package com.walletradar.application.lp.v4;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface V4PoolStateCacheRepository extends MongoRepository<V4PoolStateCache, String> {
}
