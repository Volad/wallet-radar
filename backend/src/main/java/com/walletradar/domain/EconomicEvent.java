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
 * Manual compensating events have txHash=null and use clientId for idempotency.
 */
@Document(collection = "economic_events")
@CompoundIndexes({
    @CompoundIndex(name = "txHash_networkId", def = "{'txHash': 1, 'networkId': 1}", unique = true, sparse = true),
    @CompoundIndex(name = "wallet_network_block", def = "{'walletAddress': 1, 'networkId': 1, 'blockTimestamp': 1}"),
    @CompoundIndex(name = "wallet_asset_block", def = "{'walletAddress': 1, 'assetSymbol': 1, 'blockTimestamp': 1}")
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
    @Indexed(unique = true, sparse = true)
    private String clientId;
}
