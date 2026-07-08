package com.walletradar.application.normalization.pipeline.classification.lp;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.pipeline.classification.support.ParityFlowSupport;
import com.walletradar.application.normalization.pipeline.classification.support.RawLeg;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;

import java.util.List;

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
        List<NormalizedTransaction.Flow> baseFlows = ParityFlowSupport.flows(view, movementLegs, type);
        return LpNftClFlowMaterializer.enrich(view, movementLegs, type, correlationId, baseFlows);
    }
}
