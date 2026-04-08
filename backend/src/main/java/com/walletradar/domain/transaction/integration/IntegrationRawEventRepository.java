package com.walletradar.domain.transaction.integration;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface IntegrationRawEventRepository extends MongoRepository<IntegrationRawEvent, String> {
}
