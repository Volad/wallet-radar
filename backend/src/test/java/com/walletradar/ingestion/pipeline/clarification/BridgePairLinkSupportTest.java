package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

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

    @Test
    void applyLinkedBridgeCounterpartyOverwritesUnknownEoaWithBridge() {
        NormalizedTransaction source = new NormalizedTransaction();
        source.setType(NormalizedTransactionType.BRIDGE_OUT);
        source.setTxHash("0xD9D3000000000000000000000000000000000000000000000000000000000000");
        NormalizedTransaction.Flow out = flowWithRole(NormalizedLegRole.TRANSFER, "ETH", "-0.5");
        source.setFlows(java.util.List.of(out));

        NormalizedTransaction destination = new NormalizedTransaction();
        destination.setType(NormalizedTransactionType.BRIDGE_IN);
        NormalizedTransaction.Flow in = flowWithRole(NormalizedLegRole.TRANSFER, "ETH", "0.5");
        // RC-4: clarification stamped the relayer EOA as UNKNOWN_EOA.
        in.setCounterpartyAddress("0x8c826f79de4ef9c7e4e1f3c6a8f70000000000aa");
        in.setCounterpartyType(CounterpartyType.UNKNOWN_EOA);
        destination.setFlows(java.util.List.of(in));

        boolean changed = BridgePairLinkSupport.applyLinkedBridgeCounterparty(source, destination, Instant.now());

        assertThat(changed).isTrue();
        assertThat(in.getCounterpartyType()).isEqualTo(CounterpartyType.BRIDGE);
    }

    @Test
    void applyLinkedBridgeCounterpartyDoesNotDowngradeStrongerType() {
        NormalizedTransaction source = new NormalizedTransaction();
        source.setType(NormalizedTransactionType.BRIDGE_OUT);
        source.setTxHash("0xabc0000000000000000000000000000000000000000000000000000000000000");
        source.setFlows(java.util.List.of(flowWithRole(NormalizedLegRole.TRANSFER, "ETH", "-0.5")));

        NormalizedTransaction destination = new NormalizedTransaction();
        destination.setType(NormalizedTransactionType.BRIDGE_IN);
        NormalizedTransaction.Flow in = flowWithRole(NormalizedLegRole.TRANSFER, "ETH", "0.5");
        in.setCounterpartyAddress("0x1111111111111111111111111111111111111111");
        in.setCounterpartyType(CounterpartyType.CEX);
        destination.setFlows(java.util.List.of(in));

        BridgePairLinkSupport.applyLinkedBridgeCounterparty(source, destination, Instant.now());

        assertThat(in.getCounterpartyType()).isEqualTo(CounterpartyType.CEX);
    }

    @Test
    void retagSupplementalInboundFlowRetagsMatchedEthLegOnly() {
        NormalizedTransaction destination = new NormalizedTransaction();
        destination.setType(NormalizedTransactionType.BRIDGE_IN);
        NormalizedTransaction.Flow stable = flowWithRole(NormalizedLegRole.TRANSFER, "USD₮0", "862.833378");
        NormalizedTransaction.Flow eth = flowWithRole(NormalizedLegRole.BUY, "ETH", "0.013689");
        eth.setUnitPriceUsd(new BigDecimal("2500"));
        destination.setFlows(java.util.List.of(stable, eth));

        boolean changed = BridgePairLinkSupport.retagSupplementalInboundFlow(
                destination,
                eth,
                "0x585aefbf6646c0b978a6ea4e1dc1dd411e28dd394fef7100932a61d24cf53a3b",
                Instant.now()
        );

        assertThat(changed).isTrue();
        assertThat(eth.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(eth.getUnitPriceUsd()).isNull();
        assertThat(eth.getCounterpartyAddress())
                .isEqualTo("LINKED:0x585aefbf6646c0b978a6ea4e1dc1dd411e28dd394fef7100932a61d24cf53a3b");
        assertThat(stable.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    void bridgeOutPositiveTransferFlowRevertedToBuyToPreventCarryIn() {
        NormalizedTransaction bridgeOut = new NormalizedTransaction();
        bridgeOut.setType(NormalizedTransactionType.BRIDGE_OUT);
        NormalizedTransaction.Flow outboundFlow = flowWithRole(NormalizedLegRole.TRANSFER, "ETH", "-0.000221");
        // Positive TRANSFER flow from normalization — must be reverted to BUY to prevent CARRY_IN
        NormalizedTransaction.Flow secondaryInbound = flowWithRole(NormalizedLegRole.TRANSFER, "ETH", "0.00000025");
        bridgeOut.setFlows(java.util.List.of(outboundFlow, secondaryInbound));

        boolean changed = BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(bridgeOut, Instant.now());

        assertThat(changed).isTrue();
        assertThat(secondaryInbound.getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(outboundFlow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
    }

    @Test
    void bridgeOutPositiveBuyFlowLeftUntouched() {
        NormalizedTransaction bridgeOut = new NormalizedTransaction();
        bridgeOut.setType(NormalizedTransactionType.BRIDGE_OUT);
        NormalizedTransaction.Flow outboundFlow = flowWithRole(NormalizedLegRole.SELL, "ETH", "-0.000221");
        outboundFlow.setUnitPriceUsd(new BigDecimal("2500"));
        // Positive BUY flow (e.g. fee refund) must remain BUY — not be demoted to TRANSFER
        NormalizedTransaction.Flow refundFlow = flowWithRole(NormalizedLegRole.BUY, "ETH", "0.00000025");
        bridgeOut.setFlows(java.util.List.of(outboundFlow, refundFlow));

        boolean changed = BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(bridgeOut, Instant.now());

        assertThat(changed).isTrue();
        assertThat(refundFlow.getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(outboundFlow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(outboundFlow.getUnitPriceUsd()).isNull();
    }

    @Test
    void singleFlowBridgeOutRetagsOutboundToTransfer() {
        NormalizedTransaction bridgeOut = new NormalizedTransaction();
        bridgeOut.setType(NormalizedTransactionType.BRIDGE_OUT);
        NormalizedTransaction.Flow outboundFlow = flowWithRole(NormalizedLegRole.SELL, "ETH", "-0.05");
        outboundFlow.setUnitPriceUsd(new BigDecimal("2500"));
        bridgeOut.setFlows(java.util.List.of(outboundFlow));

        boolean changed = BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(bridgeOut, Instant.now());

        assertThat(changed).isTrue();
        assertThat(outboundFlow.getRole()).isEqualTo(NormalizedLegRole.TRANSFER);
        assertThat(outboundFlow.getUnitPriceUsd()).isNull();
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }

    private static NormalizedTransaction.Flow flowWithRole(NormalizedLegRole role, String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
