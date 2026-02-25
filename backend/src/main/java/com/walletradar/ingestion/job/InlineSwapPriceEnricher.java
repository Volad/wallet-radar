package com.walletradar.ingestion.job;

import com.walletradar.common.StablecoinRegistry;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.PriceSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enriches swap events with inline USD price when one leg is a stablecoin.
 * Applied after normalization, before upsert — so enriched events skip PRICE_PENDING.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class InlineSwapPriceEnricher {

    private final StablecoinRegistry stablecoinRegistry;

    public void enrich(List<EconomicEvent> events) {
        if (events == null || events.size() < 2) return;

        Map<String, List<EconomicEvent>> byTxHash = events.stream()
                .filter(e -> e.getTxHash() != null)
                .collect(Collectors.groupingBy(EconomicEvent::getTxHash));

        for (List<EconomicEvent> txEvents : byTxHash.values()) {
            enrichTxGroup(txEvents);
        }
    }

    private void enrichTxGroup(List<EconomicEvent> txEvents) {
        EconomicEvent sell = null;
        EconomicEvent buy = null;

        for (EconomicEvent e : txEvents) {
            if (e.getEventType() == EconomicEventType.SWAP_SELL) {
                if (sell != null) return;
                sell = e;
            } else if (e.getEventType() == EconomicEventType.SWAP_BUY) {
                if (buy != null) return;
                buy = e;
            }
        }

        if (sell == null || buy == null) return;
        if (sell.getQuantityDelta() == null || buy.getQuantityDelta() == null) return;
        if (sell.getAssetContract() == null || buy.getAssetContract() == null) return;
        if (sell.getAssetContract().equalsIgnoreCase(buy.getAssetContract())) return;

        boolean sellIsStable = stablecoinRegistry.isStablecoin(sell.getAssetContract());
        boolean buyIsStable = stablecoinRegistry.isStablecoin(buy.getAssetContract());

        if (sellIsStable && buyIsStable) {
            applyStablecoin(sell);
            applyStablecoin(buy);
        } else if (sellIsStable) {
            applyStablecoin(sell);
            applyDerived(buy, sell);
        } else if (buyIsStable) {
            applyStablecoin(buy);
            applyDerived(sell, buy);
        }
    }

    private static void applyStablecoin(EconomicEvent event) {
        event.setPriceUsd(BigDecimal.ONE);
        event.setPriceSource(PriceSource.STABLECOIN);
        event.setTotalValueUsd(event.getQuantityDelta().abs());
    }

    private static void applyDerived(EconomicEvent target, EconomicEvent stableLeg) {
        BigDecimal stableAmount = stableLeg.getQuantityDelta().abs();
        BigDecimal otherAmount = target.getQuantityDelta().abs();

        if (stableAmount.signum() == 0 || otherAmount.signum() == 0) return;

        BigDecimal priceUsd = stableAmount.divide(otherAmount, MathContext.DECIMAL128);
        target.setPriceUsd(priceUsd);
        target.setPriceSource(PriceSource.SWAP_DERIVED);
        target.setTotalValueUsd(otherAmount.multiply(priceUsd, MathContext.DECIMAL128));
    }
}
