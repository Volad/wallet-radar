package com.walletradar.accounting.support.leverage;

import com.walletradar.accounting.support.AccountingAssetIdentitySupport;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.pricing.domain.CanonicalAssetCatalog;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects an on-chain <em>leveraged acquisition</em> shape (ADR-028).
 *
 * <p>A leveraged buy is an acquisition SWAP whose received collateral is worth materially more than
 * the consideration paid, because the difference is funded by an embedded borrow (Aave borrow,
 * ERC-3156 flash loan, or a leverage/aggregator router that opens the debt inside the same call).
 * Without modelling, the collateral lands at the swap-implied (depressed) basis, fabricating a gain
 * when it is later disposed.</p>
 *
 * <p>This detector runs at classification: it determines the acquisition shape and the presence of
 * borrow evidence from the raw receipt/selectors (no price oracle needed). The value-divergence
 * sizing of the synthetic borrow is finalised at replay, where both legs are market-priced. The
 * pure {@link #decide(BigDecimal, BigDecimal, boolean)} method encodes the full truth table the
 * architect specified and is shared by the replay hook.</p>
 */
@Component
public class LeverageAcquisitionDetector {

    /** Below this absolute USD gap, a value divergence is treated as ordinary swap slippage. */
    public static final BigDecimal DIVERGENCE_ABS_FLOOR_USD = new BigDecimal("100");

    /** Relative gap (fraction of consideration) above which an acquisition is divergent. */
    public static final BigDecimal DIVERGENCE_FRACTION = new BigDecimal("0.30");

    public static final String PENDING_REASON = "LEVERAGE_BORROW_INFERENCE_REQUIRED";

    /** Aave V3 {@code Borrow(...)} event topic0. */
    private static final String AAVE_BORROW_EVENT_TOPIC =
            "0xb3d084820fb1a9decffb176436bd02558d15fac9b0ddfed8c465bc7359d7dce0";
    /** Aave V3 {@code FlashLoan(...)} event topic0. */
    private static final String AAVE_FLASH_LOAN_EVENT_TOPIC =
            "0xefefaba5e921573100900a3ad9cf29f222d995fb3b6045797eaea7521bd8d6f0";
    /** Balancer V2 {@code FlashLoan(...)} event topic0. */
    private static final String BALANCER_FLASH_LOAN_EVENT_TOPIC =
            "0x0d7d75e01ab95780d3cd1c8ec0dd6c2ce19e3a20427eec8bf53283b6fb8e95f0";

    private static final Set<String> FLASH_LOAN_EVENT_TOPICS = Set.of(
            AAVE_FLASH_LOAN_EVENT_TOPIC,
            BALANCER_FLASH_LOAN_EVENT_TOPIC
    );

    /**
     * Known leverage / aggregator router selectors that open a borrow inside the swap call. These
     * are protocol-general method signatures, never per-tx hashes.
     */
    private static final Set<String> LEVERAGE_ROUTER_SELECTORS = Set.of(
            "0x247d4981", // Mantle aggregation router execute(tuple)
            "0xc81f847a"  // Pendle swapExactTokenForPt (PT-zap leverage entry)
    );

    /** Routing outcome of the leverage decision (architect E1 truth table). */
    public enum LeverageDecision {
        /** Not leveraged — handle as an ordinary swap (unchanged). */
        ORDINARY,
        /** Divergent acquisition backed by borrow evidence — record the synthetic borrow. */
        LEVERAGED,
        /** Divergent acquisition without usable borrow evidence — never fabricate, clarify. */
        PENDING_INFERENCE
    }

    public enum EvidenceKind {
        AAVE_BORROW_LOG,
        FLASH_LOAN_LOG,
        LEVERAGE_ROUTER_SELECTOR
    }

    /**
     * Classification-stage annotation describing an inferred leveraged buy. The synthetic borrow is
     * sized at replay (value divergence needs market prices), so this record carries only what is
     * deterministically known at classification.
     */
    public record LeverageAnnotation(
            boolean borrowEvidence,
            EvidenceKind evidenceKind,
            String loanCorrelationId,
            String collateralContract,
            String collateralSymbol
    ) {
    }

    /**
     * Pure decision over the value gap and borrow evidence (architect E1). Shared by the replay
     * hook to size/skip the synthetic borrow.
     *
     * @param receivedMarketUsd market value of the received collateral
     * @param considerationUsd  value of the consideration paid out
     * @param borrowEvidence    whether borrow evidence (log/selector) was found at classification
     */
    public LeverageDecision decide(
            BigDecimal receivedMarketUsd,
            BigDecimal considerationUsd,
            boolean borrowEvidence
    ) {
        if (receivedMarketUsd == null || considerationUsd == null) {
            return LeverageDecision.ORDINARY;
        }
        BigDecimal gap = receivedMarketUsd.subtract(considerationUsd);
        if (!isDivergent(gap, considerationUsd)) {
            return LeverageDecision.ORDINARY;
        }
        return borrowEvidence ? LeverageDecision.LEVERAGED : LeverageDecision.PENDING_INFERENCE;
    }

    /**
     * Returns {@code true} when {@code gap} exceeds {@code max(ABS_FLOOR, FRACTION × consideration)}.
     */
    public boolean isDivergent(BigDecimal gap, BigDecimal considerationUsd) {
        if (gap == null || gap.signum() <= 0) {
            return false;
        }
        BigDecimal relativeThreshold = considerationUsd == null
                ? BigDecimal.ZERO
                : considerationUsd.abs().multiply(DIVERGENCE_FRACTION);
        BigDecimal threshold = relativeThreshold.max(DIVERGENCE_ABS_FLOOR_USD);
        return gap.compareTo(threshold) > 0;
    }

    /**
     * Detects the leveraged-acquisition annotation at classification, or {@code null} when the
     * transaction is an ordinary acquisition / not an acquisition shape.
     *
     * <p>Returns an annotation with {@link LeverageAnnotation#borrowEvidence()} {@code = false} only
     * when the caller must route the row to {@code PENDING_CLARIFICATION}: an acquisition shape with
     * borrow evidence but no usable correlation key (e.g. native-token collateral with no contract).
     * In every other non-leveraged case it returns {@code null} (ordinary swap, unchanged).</p>
     */
    public LeverageAnnotation detect(
            OnChainRawTransactionView view,
            NetworkId networkId,
            String walletAddress,
            List<NormalizedTransaction.Flow> flows
    ) {
        if (view == null || flows == null || flows.isEmpty()) {
            return null;
        }
        // F-4 guard: an explicit debt-identity mint means an Aave BORROW/REPAY lifecycle already
        // owns the liability — never double-subtract by inferring a second one here.
        if (hasDebtIdentityLeg(flows)) {
            return null;
        }
        NormalizedTransaction.Flow collateral = soleReceivedCollateral(flows);
        if (collateral == null || !hasConsiderationPaid(flows)) {
            return null;
        }
        EvidenceKind evidenceKind = resolveEvidenceKind(view);
        if (evidenceKind == null) {
            // Acquisition shape with no borrow evidence is an ordinary swap. The value-divergence
            // refinement (and any PENDING for unexplained gaps) is intentionally not forced here,
            // to avoid re-pricing every swap. PENDING is reserved for the evidence-present case.
            return null;
        }
        String loanCorrelationId = AaveDebtLoanCorrelationSupport.leverageLoanCorrelationId(
                networkId,
                null,
                collateral.getAssetContract(),
                walletAddress
        );
        if (loanCorrelationId == null) {
            // Borrow evidence present but no usable correlation key (native collateral with no
            // contract) — never fabricate a liability we cannot key deterministically.
            return new LeverageAnnotation(false, evidenceKind, null,
                    collateral.getAssetContract(), collateral.getAssetSymbol());
        }
        return new LeverageAnnotation(
                true,
                evidenceKind,
                loanCorrelationId,
                collateral.getAssetContract(),
                collateral.getAssetSymbol()
        );
    }

    private EvidenceKind resolveEvidenceKind(OnChainRawTransactionView view) {
        if (hasEventTopic(view, AAVE_BORROW_EVENT_TOPIC)) {
            return EvidenceKind.AAVE_BORROW_LOG;
        }
        if (hasAnyEventTopic(view, FLASH_LOAN_EVENT_TOPICS)) {
            return EvidenceKind.FLASH_LOAN_LOG;
        }
        if (hasLeverageRouterSelector(view)) {
            return EvidenceKind.LEVERAGE_ROUTER_SELECTOR;
        }
        return null;
    }

    private boolean hasLeverageRouterSelector(OnChainRawTransactionView view) {
        String methodId = view.methodId();
        return methodId != null && LEVERAGE_ROUTER_SELECTORS.contains(methodId.toLowerCase(Locale.ROOT));
    }

    private boolean hasEventTopic(OnChainRawTransactionView view, String topic0) {
        return hasAnyEventTopic(view, Set.of(topic0));
    }

    private boolean hasAnyEventTopic(OnChainRawTransactionView view, Set<String> topic0s) {
        for (Document log : view.persistedLogs()) {
            String topic = firstTopic(log);
            if (topic != null && topic0s.contains(topic.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String firstTopic(Document log) {
        if (log == null) {
            return null;
        }
        Object topicsObject = log.get("topics");
        if (!(topicsObject instanceof List<?> topics) || topics.isEmpty()) {
            return null;
        }
        Object first = topics.getFirst();
        return first == null ? null : first.toString();
    }

    /**
     * The sole received priceable collateral leg of an acquisition shape, or {@code null} when the
     * received side is not a single non-stable asset (e.g. a stablecoin-for-stablecoin swap, or
     * multiple distinct received assets where leverage cannot be unambiguously attributed).
     */
    private NormalizedTransaction.Flow soleReceivedCollateral(List<NormalizedTransaction.Flow> flows) {
        NormalizedTransaction.Flow collateral = null;
        String collateralSymbol = null;
        for (NormalizedTransaction.Flow flow : flows) {
            if (!isReceivedNonFee(flow)) {
                continue;
            }
            // A stablecoin received side is not collateral being levered into.
            if (CanonicalAssetCatalog.isUsdStablecoinBySymbol(flow.getAssetSymbol())) {
                return null;
            }
            String symbol = normalizeSymbol(flow.getAssetSymbol());
            if (symbol == null) {
                return null;
            }
            if (collateralSymbol == null) {
                collateral = flow;
                collateralSymbol = symbol;
            } else if (!collateralSymbol.equals(symbol)) {
                // More than one distinct received asset — not a single-collateral leveraged buy.
                return null;
            }
        }
        return collateral;
    }

    private boolean hasConsiderationPaid(List<NormalizedTransaction.Flow> flows) {
        for (NormalizedTransaction.Flow flow : flows) {
            if (isPaidNonFee(flow)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDebtIdentityLeg(List<NormalizedTransaction.Flow> flows) {
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow != null && AccountingAssetIdentitySupport.isDebtIdentity(flow.getAssetSymbol())) {
                return true;
            }
        }
        return false;
    }

    private boolean isReceivedNonFee(NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() > 0;
    }

    private boolean isPaidNonFee(NormalizedTransaction.Flow flow) {
        return flow != null
                && flow.getRole() != NormalizedLegRole.FEE
                && flow.getQuantityDelta() != null
                && flow.getQuantityDelta().signum() < 0;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
