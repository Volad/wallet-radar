package com.walletradar.application.normalization.pipeline.classification.lp;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.support.ParityFlowSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * LP registry flow builder: parity roles first, then NFT CL receipt materialization.
 */
public final class LpClassificationFlowSupport {

    private LpClassificationFlowSupport() {
    }

    public static List<NormalizedTransaction.Flow> flows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String correlationId
    ) {
        return flows(view, movementLegs, type, correlationId, null);
    }

    /**
     * Builds LP flows with optional pre-computed V4 fee fractions.
     *
     * @param externalFeeFractions per-contract fee fractions from {@code LpV4ExitFeeDecomposer},
     *                             or {@code null} to use the V3 auto-decode path
     */
    public static List<NormalizedTransaction.Flow> flows(
            OnChainRawTransactionView view,
            List<RawLeg> movementLegs,
            NormalizedTransactionType type,
            String correlationId,
            Map<String, BigDecimal> externalFeeFractions
    ) {
        List<NormalizedTransaction.Flow> baseFlows = ParityFlowSupport.flows(view, movementLegs, type);
        return LpNftClFlowMaterializer.enrich(view, movementLegs, type, correlationId, baseFlows, externalFeeFractions);
    }
}
