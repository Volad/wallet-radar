package com.walletradar.pricing.application;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceableFlowPolicyTest {

    @Test
    void pricingSkippedSymbolDoesNotRequireMarketPrice() {
        NormalizedTransaction tx = transferIn("PAWS");
        NormalizedTransaction.Flow flow = tx.getFlows().get(0);
        flow.setRole(NormalizedLegRole.BUY);

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isFalse();
    }

    @Test
    void flowAlreadyStampedPricingSkippedDoesNotRequireMarketPrice() {
        NormalizedTransaction tx = transferIn("ETH");
        NormalizedTransaction.Flow flow = tx.getFlows().get(0);
        flow.setRole(NormalizedLegRole.BUY);
        flow.setPriceSource(PriceSource.PRICING_SKIPPED);

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isFalse();
    }

    @Test
    void pricedSymbolStillRequiresMarketPrice() {
        NormalizedTransaction tx = transferIn("ETH");
        NormalizedTransaction.Flow flow = tx.getFlows().get(0);
        flow.setRole(NormalizedLegRole.BUY);

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void peggedNativeTransferOnInternalTransferDoesNotRequireMarketPrice() {
        // On-chain wallet↔wallet cmETH uses continuity carry, not stat-time market price.
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setContinuityCandidate(true);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setQuantityDelta(new BigDecimal("0.144"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isFalse();
    }

    @Test
    void peggedNativeBybitExternalTransferInRequiresMarketPriceDespiteContinuityPrincipal() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId("BYBIT-CORRIDOR:MANTLE:0xabc");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setQuantityDelta(new BigDecimal("0.862"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void bybitPeggedNativeInternalTransferRequiresMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setContinuityCandidate(true);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setQuantityDelta(new BigDecimal("0.144"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void bybitEthContinuityInternalTransferRequiresMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setContinuityCandidate(true);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.919"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void bybitFa001CorridorEthDepositDoesNotRequireMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xbc3fe1");
        tx.setMatchedCounterparty("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("3.06"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isFalse();
    }

    @Test
    void peggedNativeTransferOnExternalTransferInRequiresMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setContinuityCandidate(false);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setQuantityDelta(new BigDecimal("0.144"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void nonPeggedTransferOnInternalTransferDoesNotRequireMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setContinuityCandidate(false);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("LDO");
        flow.setQuantityDelta(new BigDecimal("10"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isFalse();
    }

    @Test
    void continuityInternalTransferEthInboundRequiresMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId("BYBIT-CORRIDOR:ARBITRUM:0xabc");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.918"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void bridgeInEthInboundRequiresMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BRIDGE_IN);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId("bridge:lifi:0xabc");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.5"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void lpExitEthInboundRequiresMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.LP_EXIT);
        tx.setCorrelationId("lp-position:arbitrum:pancakeswap:196975");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.622"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isTrue();
    }

    @Test
    void bridgeOutEthOutboundDoesNotRequireMarketPrice() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BRIDGE_OUT);
        tx.setContinuityCandidate(true);
        tx.setCorrelationId("bridge:lifi:0xabc");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("-0.5"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));

        assertThat(PriceableFlowPolicy.requiresMarketPrice(tx, flow)).isFalse();
    }

    private NormalizedTransaction transferIn(String symbol) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setContinuityCandidate(false);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal("1"));
        tx.setFlows(new java.util.ArrayList<>(java.util.List.of(flow)));
        return tx;
    }
}
