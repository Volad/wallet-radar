package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.BridgeAssetFamilySupport;
import com.walletradar.application.costbasis.support.BridgeSettlementMetadataSupport;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * B-ETH-01 / B-ETH-03: stamps the settlement sub-mode and realize-on-convert decision on the two
 * legs of a linked bridge pair at the OUTBOUND link decision, so AVCO replay can distinguish the two
 * {@code BRIDGE_IN} sub-modes and realize P&L on asset-converting, non-peg corridors without
 * re-deriving asset family.
 *
 * <p>The destination fair market value is captured here (link time), before any downstream principal
 * retagging clears the destination price fields, and persisted as canonical metadata on both legs.</p>
 *
 * <ul>
 *   <li><b>Same-asset continuity</b> → stamp
 *       {@link BridgeSettlementMetadataSupport#SUB_MODE_SAME_ASSET} (observability only).</li>
 *   <li><b>Asset-converting settlement</b> → stamp
 *       {@link BridgeSettlementMetadataSupport#SUB_MODE_ASSET_CONVERTING}. Realize-on-convert is set
 *       only when the source family differs from the destination family, the source is NOT
 *       peg-neutral, and a positive destination fair value is resolvable. Peg-neutral cross-asset
 *       corridors (e.g. USDC→ETH) stay byte-identical: sub-mode stamped, realize flag {@code false}.</li>
 * </ul>
 */
public final class BridgeSettlementLinkStamper {

    private static final MathContext MC = MathContext.DECIMAL64;

    private BridgeSettlementLinkStamper() {
    }

    /**
     * Stamps the same-asset continuity sub-mode on the given leg. Returns {@code true} when the
     * leg's stored metadata changed.
     */
    public static boolean stampSameAssetContinuity(NormalizedTransaction leg) {
        return BridgeSettlementMetadataSupport.stampSameAssetContinuity(leg);
    }

    /**
     * Stamps the asset-converting settlement sub-mode + realize-on-convert decision (derived from the
     * pair) on the given leg. Returns {@code true} when the leg's stored metadata changed.
     *
     * <p>Must be invoked before the destination principal price fields are cleared so the captured
     * destination fair value reflects the settlement-timestamp market value.</p>
     */
    public static boolean stampAssetConvertingSettlement(
            NormalizedTransaction leg,
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        ConvertDecision decision = decideConvert(source, destination);
        return BridgeSettlementMetadataSupport.stampAssetConvertingSettlement(
                leg, decision.realizeOnConvert(), decision.destFairValueUsd());
    }

    private static ConvertDecision decideConvert(
            NormalizedTransaction source,
            NormalizedTransaction destination
    ) {
        Optional<NormalizedTransaction.Flow> sourceFlow =
                BridgePairLinkSupport.selectPrimaryPrincipalFlow(source, -1);
        Optional<NormalizedTransaction.Flow> destinationFlow =
                BridgePairLinkSupport.selectPrimaryPrincipalFlow(destination, 1);
        if (sourceFlow.isEmpty() || destinationFlow.isEmpty()) {
            return new ConvertDecision(false, null);
        }
        NormalizedTransaction.Flow src = sourceFlow.orElseThrow();
        NormalizedTransaction.Flow dst = destinationFlow.orElseThrow();
        String sourceFamily = BridgeAssetFamilySupport.continuityIdentity(src);
        String destFamily = BridgeAssetFamilySupport.continuityIdentity(dst);
        boolean crossFamily = sourceFamily != null && !sourceFamily.equals(destFamily);
        boolean pegNeutral = PegNeutralBridgeAssumptionSupport.isPegNeutral(src.getAssetSymbol());
        BigDecimal destFairValueUsd = resolveFlowUsdValue(dst);
        boolean realizeOnConvert = crossFamily
                && !pegNeutral
                && destFairValueUsd != null
                && destFairValueUsd.signum() > 0;
        return new ConvertDecision(realizeOnConvert, destFairValueUsd);
    }

    private static BigDecimal resolveFlowUsdValue(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        if (flow.getValueUsd() != null && flow.getValueUsd().signum() > 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0
                && flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0) {
            BigDecimal usd = flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
            if (usd.signum() > 0) {
                return usd;
            }
        }
        return null;
    }

    private record ConvertDecision(boolean realizeOnConvert, BigDecimal destFairValueUsd) {
    }
}
