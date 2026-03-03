package com.walletradar.ingestion.classifier;

import com.walletradar.domain.transaction.normalized.EconomicEventType;
import com.walletradar.domain.common.FlagCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Raw event shape produced by classifiers; builder converts this into normalized transaction legs.
 * No txHash, networkId, blockTimestamp here — they come from RawTransaction context.
 */
@NoArgsConstructor
@Getter
@Setter
public class RawClassifiedEvent {

    private EconomicEventType eventType;
    /** Wallet this event belongs to (from or to in the tx). */
    private String walletAddress;
    private String assetSymbol;
    private String assetContract;
    private BigDecimal quantityDelta;
    private BigDecimal priceUsd;
    private String counterpartyAddress;
    private String protocolName;
    private Long gasUsed;
    private BigInteger gasPriceWei;
    private FlagCode flagCode;
    /** Log index within the tx (from receipt) for deterministic ordering when blockTimestamp is equal. */
    private Integer logIndex;
    /**
     * LP position identifier (e.g. concentrated-liquidity NFT tokenId for v3/v4 style pools).
     * Optional; set only when classifier can deterministically resolve it.
     */
    private String positionId;
}
