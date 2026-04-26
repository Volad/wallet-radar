package com.walletradar.pricing.application;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pricing policy for canonical flows.
 */
public final class PriceableFlowPolicy {

    public static final String PRICE_UNRESOLVABLE_REASON = "PRICE_UNRESOLVABLE";
    public static final String PRICING_EXECUTION_FAILED_REASON = "PRICING_EXECUTION_FAILED";

    private static final Set<NormalizedTransactionType> NON_PRICEABLE_TYPES = EnumSet.of(
            NormalizedTransactionType.APPROVE,
            NormalizedTransactionType.ADMIN_CONFIG,
            NormalizedTransactionType.UNKNOWN
    );

    private PriceableFlowPolicy() {
    }

    public static boolean requiresMarketPrice(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null || hasNoEconomicQuantity(flow)) {
            return false;
        }
        if (flow.getAssetSymbol() == null || flow.getAssetSymbol().isBlank()) {
            return false;
        }
        if (NON_PRICEABLE_TYPES.contains(transaction.getType())) {
            return false;
        }
        if (flow.getRole() == NormalizedLegRole.TRANSFER) {
            return false;
        }
        if (isContinuityPrincipal(transaction, flow)) {
            return false;
        }
        return flow.getRole() == NormalizedLegRole.FEE
                || flow.getRole() == NormalizedLegRole.BUY
                || flow.getRole() == NormalizedLegRole.SELL;
    }

    public static boolean isContinuityPrincipal(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null
                || flow == null
                || flow.getRole() == NormalizedLegRole.FEE
                || !Boolean.TRUE.equals(transaction.getContinuityCandidate())
                || ((transaction.getCorrelationId() == null || transaction.getCorrelationId().isBlank())
                && (transaction.getTxHash() == null || transaction.getTxHash().isBlank()))
        ) {
            return false;
        }
        return transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_OUT
                || transaction.getType() == NormalizedTransactionType.BRIDGE_IN;
    }

    public static boolean hasResolvedPrice(NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getUnitPriceUsd() != null
                && flow.getPriceSource() != null
                && flow.getPriceSource() != com.walletradar.domain.common.PriceSource.UNKNOWN;
    }

    public static boolean hasReplayRelevantUnresolvedPrice(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (!requiresMarketPrice(transaction, flow)) {
                continue;
            }
            if (!hasResolvedPrice(flow)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasNoEconomicQuantity(NormalizedTransaction.Flow flow) {
        return flow == null
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().compareTo(BigDecimal.ZERO) == 0;
    }
}
