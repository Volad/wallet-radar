package com.walletradar.ingestion.pipeline.enrichment;

import com.walletradar.common.StablecoinRegistry;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventType;
import com.walletradar.domain.PriceSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
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

    /**
     * Supports multiple SWAP_SELL / SWAP_BUY per tx (e.g. same asset from several Transfer logs).
     * Aggregates by distinct asset: exactly one sell-asset and one buy-asset (different) with one stablecoin → enrich all events of those assets.
     */
    private void enrichTxGroup(List<EconomicEvent> txEvents) {
        List<EconomicEvent> sells = new ArrayList<>();
        List<EconomicEvent> buys = new ArrayList<>();
        for (EconomicEvent e : txEvents) {
            if (e.getEventType() == EconomicEventType.SWAP_SELL) sells.add(e);
            else if (e.getEventType() == EconomicEventType.SWAP_BUY) buys.add(e);
        }
        if (sells.isEmpty() || buys.isEmpty()) return;

        Map<String, List<EconomicEvent>> sellByAsset = sells.stream()
                .filter(e -> e.getAssetContract() != null)
                .collect(Collectors.groupingBy(a -> a.getAssetContract().toLowerCase()));
        Map<String, List<EconomicEvent>> buyByAsset = buys.stream()
                .filter(e -> e.getAssetContract() != null)
                .collect(Collectors.groupingBy(a -> a.getAssetContract().toLowerCase()));

        if (sellByAsset.size() != 1 || buyByAsset.size() != 1) return;
        String sellAsset = sellByAsset.keySet().iterator().next();
        String buyAsset = buyByAsset.keySet().iterator().next();
        if (sellAsset.equalsIgnoreCase(buyAsset)) return;

        List<EconomicEvent> sellList = sellByAsset.get(sellAsset);
        List<EconomicEvent> buyList = buyByAsset.get(buyAsset);
        boolean sellIsStable = stablecoinRegistry.isStablecoin(sellList.get(0).getAssetContract());
        boolean buyIsStable = stablecoinRegistry.isStablecoin(buyList.get(0).getAssetContract());
        BigDecimal totalSellQty = sumAbsQuantities(sellList);
        BigDecimal totalBuyQty = sumAbsQuantities(buyList);

        if (sellIsStable && buyIsStable) {
            for (EconomicEvent e : sellList) applyStablecoin(e);
            for (EconomicEvent e : buyList) applyStablecoin(e);
        } else if (sellIsStable) {
            for (EconomicEvent e : sellList) applyStablecoin(e);
            if (totalSellQty.signum() == 0 || totalBuyQty.signum() == 0) return;
            BigDecimal stableTotal = totalSellQty;
            for (EconomicEvent e : buyList) applyDerivedFromTotals(e, e.getQuantityDelta().abs(), stableTotal, totalBuyQty);
        } else if (buyIsStable) {
            for (EconomicEvent e : buyList) applyStablecoin(e);
            if (totalBuyQty.signum() == 0 || totalSellQty.signum() == 0) return;
            BigDecimal stableTotal = totalBuyQty;
            for (EconomicEvent e : sellList) applyDerivedFromTotals(e, e.getQuantityDelta().abs(), stableTotal, totalSellQty);
        }
    }

    private static BigDecimal sumAbsQuantities(List<EconomicEvent> events) {
        return events.stream()
                .map(EconomicEvent::getQuantityDelta)
                .filter(q -> q != null)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void applyDerivedFromTotals(EconomicEvent target, BigDecimal eventQty, BigDecimal stableTotal, BigDecimal otherTotal) {
        if (stableTotal.signum() == 0 || otherTotal.signum() == 0) return;
        BigDecimal priceUsd = stableTotal.divide(otherTotal, MathContext.DECIMAL128);
        target.setPriceUsd(priceUsd);
        target.setPriceSource(PriceSource.SWAP_DERIVED);
        target.setTotalValueUsd(eventQty.multiply(priceUsd, MathContext.DECIMAL128));
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
