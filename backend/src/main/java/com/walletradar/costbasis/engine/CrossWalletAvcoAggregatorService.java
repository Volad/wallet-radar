package com.walletradar.costbasis.engine;

import com.walletradar.domain.accounting.CostBasisOverride;
import com.walletradar.domain.accounting.CostBasisOverrideRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * On-request cross-wallet AVCO aggregation (T-016) based on confirmed normalized legs.
 * Never persists (INV-04). Cached per (sorted wallets, assetSymbol), TTL 5min.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossWalletAvcoAggregatorService {

    private static final int SCALE = 18;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final CostBasisOverrideRepository costBasisOverrideRepository;

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
     * Compute cross-wallet AVCO (and quantity) for the given wallets and asset.
     * Result is never persisted. Cached 5min per (sorted wallets, assetSymbol).
     */
    @Cacheable(cacheNames = "crossWalletAvcoCache",
            key = "T(com.walletradar.costbasis.engine.CrossWalletAvcoAggregatorService).cacheKey(#wallets, #assetSymbol)")
    public CrossWalletAvcoResult compute(List<String> wallets, String assetSymbol) {
        if (wallets == null || wallets.isEmpty()) {
            return CrossWalletAvcoResult.of(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        String needle = assetSymbol == null ? "" : assetSymbol.toLowerCase(Locale.ROOT);

        record FlowCursor(NormalizedTransaction tx, NormalizedTransaction.Flow leg, int legIndex) {}

        List<FlowCursor> timeline = new ArrayList<>();
        for (String wallet : wallets) {
            List<NormalizedTransaction> confirmed = normalizedTransactionRepository
                    .findByWalletAddressAndStatusOrderByBlockTimestampAsc(wallet, NormalizedTransactionStatus.CONFIRMED);
            for (NormalizedTransaction tx : confirmed) {
                if (isNonEconomicLpType(tx.getType())) {
                    continue;
                }
                if (tx.getFlows() == null) {
                    continue;
                }
                for (int i = 0; i < tx.getFlows().size(); i++) {
                    NormalizedTransaction.Flow leg = tx.getFlows().get(i);
                    String symbol = leg.getAssetSymbol() == null ? "" : leg.getAssetSymbol().toLowerCase(Locale.ROOT);
                    if (!needle.equals(symbol)) {
                        continue;
                    }
                    timeline.add(new FlowCursor(tx, leg, i));
                }
            }
        }

        timeline.sort(Comparator
                .comparing((FlowCursor c) -> c.tx().getBlockTimestamp(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(c -> c.leg().getLogIndex(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FlowCursor::legIndex));

        List<String> legIds = timeline.stream()
                .map(c -> c.tx().getId() + ":" + c.legIndex())
                .toList();
        Map<String, BigDecimal> overridePrices = Map.of();
        if (!legIds.isEmpty()) {
            List<CostBasisOverride> overrides = costBasisOverrideRepository.findByNormalizedLegIdInAndActiveTrue(legIds);
            overridePrices = overrides.stream().collect(Collectors.toMap(
                    CostBasisOverride::getNormalizedLegId,
                    CostBasisOverride::getPriceUsd,
                    (a, b) -> a
            ));
        }

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal avco = BigDecimal.ZERO;

        for (FlowCursor cursor : timeline) {
            NormalizedTransaction tx = cursor.tx();
            NormalizedTransaction.Flow leg = cursor.leg();
            BigDecimal qtyDelta = leg.getQuantityDelta() != null ? leg.getQuantityDelta() : BigDecimal.ZERO;
            String legId = tx.getId() + ":" + cursor.legIndex();
            BigDecimal effectivePrice = effectivePrice(tx, leg, legId, overridePrices);

            if (isInflow(tx.getType(), qtyDelta)) {
                BigDecimal addQty = qtyDelta;
                if (quantity.compareTo(BigDecimal.ZERO) == 0) {
                    avco = effectivePrice;
                    quantity = addQty;
                } else {
                    BigDecimal newQty = quantity.add(addQty);
                    avco = (avco.multiply(quantity).add(effectivePrice.multiply(addQty))).divide(newQty, SCALE, ROUNDING);
                    quantity = newQty;
                }
            } else if (isSell(tx.getType(), qtyDelta) || isOutflow(qtyDelta)) {
                quantity = quantity.add(qtyDelta);
            }
        }

        BigDecimal finalQty = quantity.max(BigDecimal.ZERO);
        return CrossWalletAvcoResult.of(avco, finalQty);
    }

    private static BigDecimal effectivePrice(
            NormalizedTransaction tx,
            NormalizedTransaction.Flow leg,
            String legId,
            Map<String, BigDecimal> overridePrices
    ) {
        if (tx.getType() != NormalizedTransactionType.MANUAL_COMPENSATING && overridePrices.containsKey(legId)) {
            return overridePrices.get(legId);
        }
        return leg.getUnitPriceUsd() != null ? leg.getUnitPriceUsd() : BigDecimal.ZERO;
    }

    private static boolean isSell(NormalizedTransactionType type, BigDecimal qtyDelta) {
        if (qtyDelta == null || qtyDelta.compareTo(BigDecimal.ZERO) >= 0) {
            return false;
        }
        return type == NormalizedTransactionType.SWAP
                || type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL;
    }

    private static boolean isInflow(NormalizedTransactionType type, BigDecimal qtyDelta) {
        if (qtyDelta == null || qtyDelta.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return type == NormalizedTransactionType.SWAP
                || type == NormalizedTransactionType.BORROW
                || type == NormalizedTransactionType.STAKE_WITHDRAWAL
                || type == NormalizedTransactionType.LEND_WITHDRAWAL
                || type == NormalizedTransactionType.LP_FEE_CLAIM
                || type == NormalizedTransactionType.EXTERNAL_INBOUND
                || type == NormalizedTransactionType.MANUAL_COMPENSATING;
    }

    private static boolean isOutflow(BigDecimal qtyDelta) {
        return qtyDelta != null && qtyDelta.compareTo(BigDecimal.ZERO) < 0;
    }

    private static boolean isNonEconomicLpType(NormalizedTransactionType type) {
        return type == NormalizedTransactionType.LP_ADJUST
                || type == NormalizedTransactionType.LP_POSITION_STAKE
                || type == NormalizedTransactionType.LP_POSITION_UNSTAKE;
    }
}
