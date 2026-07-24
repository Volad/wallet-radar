package com.walletradar.application.pricing.application;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.MissingDataReasons;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.pricing.domain.CanonicalAssetCatalog;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pricing policy for canonical flows.
 */
public final class PriceableFlowPolicy {

    public static final String PRICE_UNRESOLVABLE_REASON = MissingDataReasons.PRICE_UNRESOLVABLE;
    public static final String PRICING_EXECUTION_FAILED_REASON = MissingDataReasons.PRICING_EXECUTION_FAILED;

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
        // D1 (ADR-054 §9): a principal TRANSFER leg on a cross-canonical staking/vault identity
        // change must be priced. Both the disposed leg and the acquired receipt (e.g. mETH received
        // when staking ETH on Bybit) enter the pricing chain so replay books a real cost basis
        // instead of silently admitting a $0-basis acquisition that strips the underlying family's
        // cost basis. Reuses the ADR-054 identity registry — no symbol/contract hardcoding.
        if (isCrossCanonicalStakingPrincipal(transaction, flow)) {
            return true;
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
        // D4: LP exit fee-income legs (the R1 split off Collect − DecreaseLiquidity) are received at
        // the exit block, so they need a market quote to book fee income at FMV in the tax lane
        // (net lane stays $0 — zero-cost). Without this they land 100% unpriced (valueUsd=0) and the
        // ~$24 ETH + ~$24 USDC of fee income is dropped. Pricing here does not double-count: the
        // fee legs are the sole fee-income representation (principal is carried as return-of-capital).
        if (flow.getRole() == NormalizedLegRole.LP_FEE_INCOME) {
            return true;
        }
        return flow.getRole() == NormalizedLegRole.FEE
                || flow.getRole() == NormalizedLegRole.BUY
                || flow.getRole() == NormalizedLegRole.SELL;
    }

    /**
     * D1: {@code true} when {@code flow} is an economically significant ({@code quantityDelta}
     * signum ≠ 0) principal {@code TRANSFER} leg of a cross-canonical staking/vault identity-change
     * transaction (e.g. ETH → mETH).
     *
     * <p>Cross-canonical identity is decided at normalization time by the ADR-054 accounting C1/C2
     * registry and persisted on the row as {@link NormalizedTransaction#getCrossCanonicalStakingConversion()}.
     * The pricing layer reads that pre-stamped domain flag rather than importing the accounting
     * registry (which the module boundary forbids) or re-deriving identity from pricing-domain
     * canonical symbols (which diverges for same-family receipts such as mETH → cmETH). A same-family
     * carry is never flagged, so it stays a continuity-carry leg rather than being force-priced.</p>
     */
    public static boolean isCrossCanonicalStakingPrincipal(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(transaction.getCrossCanonicalStakingConversion())) {
            return false;
        }
        return flow.getRole() == NormalizedLegRole.TRANSFER
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() != 0;
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
        // F-1: USD-pegged inbound transfers (USDC/USDT/USD₮0/USDE and their curated aToken aliases)
        // always get a $1 quote so the replay inbound-shortfall fallback can promote any qty that
        // continuity carry left uncovered, instead of admitting a $0-basis stablecoin lot that
        // collapses AVCO and books fabricated gains on disposal. The fallback only covers the
        // uncovered delta, so a fully-carried leg is unaffected.
        if (CanonicalAssetCatalog.isUsdStablecoin(
                transaction.getNetworkId(),
                flow.getAssetContract(),
                flow.getAssetSymbol(),
                transaction.getSource()
        )) {
            return true;
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
