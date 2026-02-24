package com.walletradar.ingestion.classifier;

import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.FlagCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Raw event shape produced by classifiers; normalizer converts to domain EconomicEvent.
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
}
