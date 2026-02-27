package com.walletradar.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Central domain object: normalised financial event. All monetary fields are BigDecimal (INV-06).
 * On-chain idempotency key: (txHash, networkId, walletAddress, assetContract) so one tx can have multiple events (e.g. SWAP_SELL + SWAP_BUY). Manual events: txHash=null, idempotency by clientId.
 */
@Document(collection = "economic_events")
@CompoundIndexes({
    @CompoundIndex(name = "txHash_networkId_wallet_asset", def = "{'txHash': 1, 'networkId': 1, 'walletAddress': 1, 'assetContract': 1}", unique = true, sparse = true),
    @CompoundIndex(name = "wallet_network_block", def = "{'walletAddress': 1, 'networkId': 1, 'blockTimestamp': 1}"),
    @CompoundIndex(name = "wallet_asset_block", def = "{'walletAddress': 1, 'assetSymbol': 1, 'blockTimestamp': 1}"),
    @CompoundIndex(name = "wallet_network_asset_block", def = "{'walletAddress': 1, 'networkId': 1, 'assetContract': 1, 'blockTimestamp': 1}"),
    @CompoundIndex(name = "flagCode_wallet", def = "{'flagCode': 1, 'walletAddress': 1}")
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class EconomicEvent {

    @Id
    @EqualsAndHashCode.Include
    private String id;
    private String txHash;
    private NetworkId networkId;
    private String walletAddress;
    private Instant blockTimestamp;
    private EconomicEventType eventType;
    private String assetSymbol;
    private String assetContract;
    private BigDecimal quantityDelta;
    private BigDecimal priceUsd;
    private PriceSource priceSource;
    private BigDecimal totalValueUsd;
    private BigDecimal gasCostUsd;
    private boolean gasIncludedInBasis;
    private BigDecimal realisedPnlUsd;
    private BigDecimal avcoAtTimeOfSale;
    private FlagCode flagCode;
    private boolean flagResolved;
    private String counterpartyAddress;
    private boolean isInternalTransfer;
    private String protocolName;
    /** Log index within the tx (from receipt) for deterministic AVCO ordering when blockTimestamp is equal. */
    private Integer logIndex;
    @Indexed(unique = true, sparse = true)
    private String clientId;
}
