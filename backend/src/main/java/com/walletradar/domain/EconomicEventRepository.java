package com.walletradar.domain;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for economic_events. Used by IdempotentEventStore (ingestion) and AvcoEngine (costbasis).
 * Uniqueness: (txHash, networkId, walletAddress, assetContract) for on-chain events so one tx can have multiple legs (e.g. SWAP_SELL + SWAP_BUY); clientId for MANUAL_COMPENSATING (INV-11).
 */
public interface EconomicEventRepository extends MongoRepository<EconomicEvent, String> {

    Optional<EconomicEvent> findByTxHashAndNetworkIdAndWalletAddressAndAssetContract(
            String txHash, NetworkId networkId, String walletAddress, String assetContract);

    Optional<EconomicEvent> findByClientId(String clientId);

    /** For InternalTransferReclassifier: find EXTERNAL_INBOUND events whose counterparty is in the given list. */
    List<EconomicEvent> findByEventTypeAndCounterpartyAddressIn(EconomicEventType eventType, List<String> counterpartyAddresses);

    /** For AvcoEngine: load events for (wallet, network, asset) in blockTimestamp ASC (INV-01). Includes MANUAL_COMPENSATING. */
    List<EconomicEvent> findByWalletAddressAndNetworkIdAndAssetContractOrderByBlockTimestampAsc(
            String walletAddress, NetworkId networkId, String assetContract);

    /** For CrossWalletAvcoAggregatorService: load events for asset across wallets, ASC. */
    List<EconomicEvent> findByWalletAddressInAndAssetSymbolOrderByBlockTimestampAsc(
            List<String> walletAddresses, String assetSymbol);

    /** For AvcoEngine.recalculateForWallet (replay from beginning): distinct (networkId, assetContract) for a wallet. */
    @Query(value = "{ 'walletAddress' : ?0 }", fields = "{ 'networkId' : 1, 'assetContract' : 1 }")
    List<EconomicEvent> findNetworkIdAndAssetContractByWalletAddress(String walletAddress);
}
