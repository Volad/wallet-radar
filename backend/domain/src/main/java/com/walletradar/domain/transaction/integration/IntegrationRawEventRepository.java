package com.walletradar.domain.transaction.integration;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IntegrationRawEventRepository extends MongoRepository<IntegrationRawEvent, String> {

    List<IntegrationRawEvent> findByIntegrationIdAndStream(String integrationId, String stream);
}
