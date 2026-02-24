package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for economic_events. Used by IdempotentEventStore (ingestion) and AvcoEngine (costbasis).
 * Uniqueness: (txHash, networkId) for on-chain events; clientId (sparse unique) for MANUAL_COMPENSATING (INV-11).
 */
public interface EconomicEventRepository extends MongoRepository<EconomicEvent, String> {

    Optional<EconomicEvent> findByTxHashAndNetworkId(String txHash, NetworkId networkId);

    Optional<EconomicEvent> findByClientId(String clientId);

    /** For InternalTransferReclassifier: find EXTERNAL_INBOUND events whose counterparty is in the given list. */
    List<EconomicEvent> findByEventTypeAndCounterpartyAddressIn(EconomicEventType eventType, List<String> counterpartyAddresses);
}
