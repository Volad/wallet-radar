package com.walletradar.pricing.application;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;

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
        // Cycle/9 S5: explicitly skip pricing for delisted / no-listing symbols.
        if (flow.getPriceSource() == PriceSource.PRICING_SKIPPED
                || CanonicalAssetCatalog.isPricingSkipped(flow.getAssetSymbol())) {
            return false;
        }
        // Cycle/15 R5 F3: pegged-native TRANSFER pricing is venue-specific — evaluate BEFORE
        // isContinuityPrincipal, which would otherwise skip FA-001-linked Bybit corridor deposits.
        if (flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() != 0
                && CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())
                && (isPeggedNativeExternalTransferPricingType(transaction.getType())
                || isBybitPeggedNativeInternalTransfer(transaction)
                || isBybitEarnProductMarketPricing(transaction, flow))) {
            return true;
        }
        // Cycle/15 Cluster A: Bybit Flexible Savings / Earn product moves (LENDING_*) need market
        // quotes on both legs so earn shortfall-only outbound can enqueue priced synthetic carry.
        if (isBybitEarnProductMarketPricing(transaction, flow)) {
            return true;
        }
        if (requiresInboundShortfallSpotPricing(transaction, flow)) {
            return true;
        }
        if (isContinuityPrincipal(transaction, flow)) {
            return false;
        }
        // Lending-loop close/decrease operations use TRANSFER-role principal inflows when the
        // protocol directly returns assets (e.g. ETH on UNICHAIN from Compound loop decrease).
        // Without explicit pricing, these inflows materialise with $0 basis in the replay, which
        // then propagates via bridge carries to downstream positions (AZKSWETH, AMANWETH, etc.)
        // and depresses the ETH-family AVCO. Forcing market-price lookup stores the canonical
        // rate in the historical price cache so replay can correctly provision the cost basis.
        if (flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0
                && isLendingLoopPrincipalInflowType(transaction.getType())) {
            return true;
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
        if (isBybitOnChainCorridorPrincipal(transaction)) {
            return true;
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

    private static boolean isLendingLoopPrincipalInflowType(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case LENDING_LOOP_DECREASE, LENDING_LOOP_CLOSE -> true;
            default -> false;
        };
    }

    private static boolean isPeggedNativeExternalTransferPricingType(NormalizedTransactionType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT -> true;
            default -> false;
        };
    }

    private static boolean isBybitPeggedNativeInternalTransfer(NormalizedTransaction transaction) {
        return transaction.getSource() == NormalizedTransactionSource.BYBIT
                && transaction.getType() == NormalizedTransactionType.INTERNAL_TRANSFER;
    }

    private static boolean isBybitEarnProductMarketPricing(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction.getSource() != NormalizedTransactionSource.BYBIT
                || flow.getRole() != NormalizedLegRole.TRANSFER
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type != NormalizedTransactionType.LENDING_DEPOSIT
                && type != NormalizedTransactionType.LENDING_WITHDRAW
                && type != NormalizedTransactionType.EARN_FLEXIBLE_SAVING) {
            return false;
        }
        if (CanonicalAssetCatalog.isPricingSkipped(flow.getAssetSymbol())) {
            return false;
        }
        return true;
    }

    /**
     * Cycle/18 R9b: FA-001 wallet↔Bybit corridor rows are promoted to {@code INTERNAL_TRANSFER}
     * after linking. They must skip market pricing so replay carries basis from the on-chain leg.
     */
    private static boolean isBybitOnChainCorridorPrincipal(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId != null
                && correlationId.startsWith("BYBIT-CORRIDOR:")
                && transaction.getSource() == NormalizedTransactionSource.BYBIT) {
            return true;
        }
        if (transaction.getSource() != NormalizedTransactionSource.BYBIT) {
            return false;
        }
        String matchedCounterparty = transaction.getMatchedCounterparty();
        return matchedCounterparty != null
                && matchedCounterparty.startsWith("0x")
                && matchedCounterparty.length() == 42;
    }

    /**
     * Cycle/16 R6: inbound TRANSFER legs that may receive market spot during normalization so
     * replay can promote residual uncov when continuity carry finds an empty sender pool.
     */
    private static boolean requiresInboundShortfallSpotPricing(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (!isInboundTransferFlow(flow)) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type == NormalizedTransactionType.LP_EXIT
                || type == NormalizedTransactionType.LP_EXIT_PARTIAL
                || type == NormalizedTransactionType.LP_EXIT_FINAL
                || type == NormalizedTransactionType.LP_EXIT_SETTLEMENT
                || type == NormalizedTransactionType.LENDING_WITHDRAW
                || type == NormalizedTransactionType.EARN_FLEXIBLE_SAVING) {
            return true;
        }
        if (type == NormalizedTransactionType.BRIDGE_IN) {
            return !Boolean.TRUE.equals(transaction.getContinuityCandidate());
        }
        if (type == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                && Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            return true;
        }
        if (type == NormalizedTransactionType.INTERNAL_TRANSFER) {
            if (isBybitOnChainCorridorPrincipal(transaction)) {
                return false;
            }
            if (isBybitPeggedNativeInternalTransfer(transaction)) {
                return true;
            }
            if (transaction.getSource() == NormalizedTransactionSource.BYBIT
                    && Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
                return true;
            }
            if (transaction.getSource() == NormalizedTransactionSource.ON_CHAIN
                    && CanonicalAssetCatalog.isPeggedNative(flow.getAssetSymbol())) {
                return false;
            }
            return Boolean.TRUE.equals(transaction.getContinuityCandidate());
        }
        return false;
    }

    private static boolean isInboundTransferFlow(NormalizedTransaction.Flow flow) {
        return flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0;
    }

    /**
     * After continuity retagging cleared flow prices, route to pricing when any principal leg
     * still needs a market quote for inbound shortfall fallback.
     */
    public static NormalizedTransactionStatus statusAfterContinuityRetag(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return NormalizedTransactionStatus.PENDING_STAT;
        }
        boolean needsPricing = transaction.getFlows().stream()
                .anyMatch(flow -> requiresMarketPrice(transaction, flow));
        return needsPricing
                ? NormalizedTransactionStatus.PENDING_PRICE
                : NormalizedTransactionStatus.PENDING_STAT;
    }
}
