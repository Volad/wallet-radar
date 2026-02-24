package com.walletradar.costbasis.engine;

import com.walletradar.config.CaffeineConfig;
import com.walletradar.domain.EconomicEvent;
import com.walletradar.domain.EconomicEventRepository;
import com.walletradar.domain.EconomicEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * On-request cross-wallet AVCO aggregation (T-016). Loads events for asset across wallets, sorts by blockTimestamp ASC,
 * excludes INTERNAL_TRANSFER, runs AVCO on merged timeline. Never persists (INV-04). Cached per (sorted wallets, assetSymbol), TTL 5min.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossWalletAvcoAggregatorService {

    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final EconomicEventRepository economicEventRepository;

    /**
     * Cache key: sorted wallet addresses + assetSymbol (per 02-architecture).
     */
    public static String cacheKey(List<String> wallets, String assetSymbol) {
        if (wallets == null || wallets.isEmpty()) {
            return "|" + (assetSymbol != null ? assetSymbol : "");
        }
        List<String> sorted = new ArrayList<>(wallets);
        Collections.sort(sorted);
        return String.join(",", sorted) + "|" + (assetSymbol != null ? assetSymbol : "");
    }

    /**
     * Compute cross-wallet AVCO (and quantity) for the given wallets and asset. INTERNAL_TRANSFER excluded (AC-05).
     * Result is never persisted. Cached 5min per (sorted wallets, assetSymbol).
     */
    @Cacheable(cacheNames = CaffeineConfig.CROSS_WALLET_AVCO_CACHE,
            key = "T(com.walletradar.costbasis.engine.CrossWalletAvcoAggregatorService).cacheKey(#wallets, #assetSymbol)")
    public CrossWalletAvcoResult compute(List<String> wallets, String assetSymbol) {
        if (wallets == null || wallets.isEmpty()) {
            return CrossWalletAvcoResult.of(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        List<EconomicEvent> events = economicEventRepository
                .findByWalletAddressInAndAssetSymbolOrderByBlockTimestampAsc(wallets, assetSymbol);
        List<EconomicEvent> filtered = events.stream()
                .filter(e -> e.getEventType() != EconomicEventType.INTERNAL_TRANSFER)
                .collect(Collectors.toList());

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal avco = BigDecimal.ZERO;

        for (EconomicEvent event : filtered) {
            BigDecimal qtyDelta = event.getQuantityDelta() != null ? event.getQuantityDelta() : BigDecimal.ZERO;
            BigDecimal price = event.getPriceUsd() != null ? event.getPriceUsd() : BigDecimal.ZERO;
            EconomicEventType type = event.getEventType();

            if (AvcoEventTypeHelper.isInflow(type, qtyDelta)) {
                BigDecimal addQty = qtyDelta;
                if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                    avco = price;
                    quantity = addQty;
                } else {
                    BigDecimal newQty = quantity.add(addQty);
                    avco = (avco.multiply(quantity).add(price.multiply(addQty))).divide(newQty, SCALE, ROUNDING);
                    quantity = newQty;
                }
            } else if (AvcoEventTypeHelper.isSellType(type) && qtyDelta.compareTo(BigDecimal.ZERO) < 0) {
                quantity = quantity.add(qtyDelta);
            } else if (AvcoEventTypeHelper.isOutflow(type, qtyDelta)) {
                quantity = quantity.add(qtyDelta);
            }
        }

        BigDecimal finalQty = quantity.max(BigDecimal.ZERO);
        return CrossWalletAvcoResult.of(avco, finalQty);
    }
}
