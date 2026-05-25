package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BridgePairLinkSupportTest {

    @Test
    void supportsPlainMoveBasisForWethOutEthIn() {
        NormalizedTransaction source = new NormalizedTransaction();
        NormalizedTransaction.Flow out = flow("WETH", "-0.208");
        source.setFlows(java.util.List.of(out));

        NormalizedTransaction destination = new NormalizedTransaction();
        NormalizedTransaction.Flow in = flow("ETH", "0.208");
        destination.setFlows(java.util.List.of(in));

        assertThat(BridgePairLinkSupport.supportsPlainMoveBasis(source, destination)).isTrue();
        assertThat(BridgePairLinkSupport.supportsBridgeContinuity(out, in)).isTrue();
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
