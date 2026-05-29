package com.walletradar.ingestion.pipeline.classification.lp;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.pipeline.classification.support.ParityFlowSupport;
import com.walletradar.ingestion.pipeline.classification.support.RawLeg;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;

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
            String protocolName
    ) {
        List<NormalizedTransaction.Flow> baseFlows = ParityFlowSupport.flows(view, movementLegs, type);
        return LpNftClFlowMaterializer.enrich(view, movementLegs, type, protocolName, baseFlows);
    }
}
