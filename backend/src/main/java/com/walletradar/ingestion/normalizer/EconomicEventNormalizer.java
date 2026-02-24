package com.walletradar.ingestion.normalizer;

import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.FlagCode;
import com.walletradar.domain.NetworkId;
import com.walletradar.domain.PriceSource;
import com.walletradar.ingestion.classifier.RawClassifiedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts raw classifier output to domain EconomicEvent (network-agnostic).
 * Sets gasIncludedInBasis per 03-accounting: true for BUY-type events, false otherwise.
 */
@Component
@RequiredArgsConstructor
public class EconomicEventNormalizer {

    private final GasCostCalculator gasCostCalculator;

    /**
     * Normalize a single raw event with tx context. Gas cost is computed if gasUsed/gasPriceWei/nativePriceUsd are set.
     *
     * @param raw             raw classified event
     * @param txHash          transaction hash
     * @param networkId       network
     * @param blockTimestamp  block time
     * @param nativePriceUsd  native token price in USD (for gas cost); can be null/zero
     * @return economic event ready for persistence
     */
    public EconomicEvent normalize(RawClassifiedEvent raw, String txHash, NetworkId networkId, Instant blockTimestamp,
                                  BigDecimal nativePriceUsd) {
        EconomicEvent e = new EconomicEvent();
        e.setTxHash(txHash);
        e.setNetworkId(networkId);
        e.setWalletAddress(raw.getWalletAddress());
        e.setBlockTimestamp(blockTimestamp);
        e.setEventType(raw.getEventType());
        e.setAssetSymbol(raw.getAssetSymbol() != null ? raw.getAssetSymbol() : "");
        e.setAssetContract(raw.getAssetContract() != null ? raw.getAssetContract() : "");
        e.setQuantityDelta(raw.getQuantityDelta() != null ? raw.getQuantityDelta() : BigDecimal.ZERO);
        e.setPriceUsd(raw.getPriceUsd());
        e.setPriceSource(PriceSource.UNKNOWN);
        e.setTotalValueUsd(totalValue(raw));
        BigDecimal gasCost = gasCostCalculator.gasCostUsd(raw.getGasUsed(), raw.getGasPriceWei(), nativePriceUsd);
        e.setGasCostUsd(gasCost != null ? gasCost : BigDecimal.ZERO);
        e.setGasIncludedInBasis(gasIncludedInBasis(raw.getEventType()));
        e.setRealisedPnlUsd(null);
        e.setAvcoAtTimeOfSale(null);
        e.setFlagCode(raw.getFlagCode());
        e.setFlagResolved(false);
        e.setCounterpartyAddress(raw.getCounterpartyAddress());
        e.setInternalTransfer(raw.getEventType() == EconomicEventType.INTERNAL_TRANSFER);
        e.setProtocolName(raw.getProtocolName());
        e.setClientId(null);
        return e;
    }

    /**
     * Normalize a list of raw events with the same tx context.
     */
    public List<EconomicEvent> normalizeAll(List<RawClassifiedEvent> rawEvents, String txHash, NetworkId networkId,
                                           Instant blockTimestamp, BigDecimal nativePriceUsd) {
        return rawEvents.stream()
                .map(raw -> normalize(raw, txHash, networkId, blockTimestamp, nativePriceUsd))
                .collect(Collectors.toList());
    }

    private static BigDecimal totalValue(RawClassifiedEvent raw) {
        if (raw.getPriceUsd() == null || raw.getQuantityDelta() == null) {
            return BigDecimal.ZERO;
        }
        return raw.getPriceUsd().multiply(raw.getQuantityDelta().abs()).setScale(18, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Per 03-accounting and INV-12: only BUY and acquisition-type events include gas in cost basis; TRANSFER, STAKE, LEND excluded.
     */
    private static boolean gasIncludedInBasis(EconomicEventType eventType) {
        return eventType == EconomicEventType.SWAP_BUY
                || eventType == EconomicEventType.BORROW;
    }
}
