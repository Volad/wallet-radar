package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.config.NativeSettlementRecoveryProperties;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-044 D2 — router-agnostic native-settlement recovery via WETH {@code Withdrawal} evidence.
 */
class NativeSettlementRecoveryTest {

    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String ROUTER = "0x2626664c2603336e57b271c5c0b26f421741e481"; // generic aggregator/router
    private static final String WETH_BASE = "0x4200000000000000000000000000000000000006";
    private static final String WITHDRAWAL_TOPIC =
            "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";

    // Generic 0x/LiFi/multicall style calldata (not a 1inch/ParaSwap/LP selector).
    private static final String GENERIC_ROUTER_INPUT = "0x3593564c00000000000000000000000000000000";
    // multicall input embedding decreaseLiquidity (0x0c49ccbe) — LP exit shape.
    private static final String LP_EXIT_INPUT =
            "0xac9650d8000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000010c49ccbe0000000000000000000000000000000000000000000000000000000000000001";

    private final NativeAssetSymbolResolver resolver = new NativeAssetSymbolResolver();

    private static NativeSettlementRecoveryProperties props(boolean enabled, NetworkId... chains) {
        NativeSettlementRecoveryProperties properties = new NativeSettlementRecoveryProperties();
        properties.setEnabled(enabled);
        properties.setChains(List.of(chains));
        return properties;
    }

    private static Document withdrawalLog(String emitter, String srcAddress, String wadHex) {
        String normalizedSrc = srcAddress.toLowerCase().replace("0x", "");
        String paddedSrc = "0x" + "0".repeat(64 - normalizedSrc.length()) + normalizedSrc;
        String wadNorm = wadHex.toLowerCase().replace("0x", "");
        String paddedWad = "0x" + "0".repeat(64 - wadNorm.length()) + wadNorm;
        return new Document()
                .append("address", emitter)
                .append("topics", List.of(WITHDRAWAL_TOPIC, paddedSrc))
                .append("data", paddedWad);
    }

    private static OnChainRawTransactionView view(
            NetworkId network,
            String inputData,
            List<Document> logs,
            List<Document> internalTransfers
    ) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(network.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("hash", "0xabc123")
                .append("blockNumber", "10000000")
                .append("timeStamp", "1750000000")
                .append("transactionIndex", "3")
                .append("input", inputData)
                .append("logs", logs)
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of())
                        .append("internalTransfers", internalTransfers)));
        return OnChainRawTransactionView.wrap(raw);
    }

    private static Document internalTransferTo(String to, String valueWei) {
        return new Document().append("from", ROUTER).append("to", to).append("value", valueWei);
    }

    @Test
    @DisplayName("recovers generic-router native output via WETH Withdrawal(src != wallet)")
    void recoversGenericRouterNativeOutput() {
        // 1 ETH = 0x0de0b6b3a7640000
        Document log = withdrawalLog(WETH_BASE, ROUTER, "0x0de0b6b3a7640000");
        OnChainRawTransactionView view = view(NetworkId.BASE, GENERIC_ROUTER_INPUT, List.of(log), List.of());

        List<RawLeg> result = NativeSettlementRecovery.enrichLegs(
                view, resolver, List.of(RawLeg.asset("0xusdc", "USDC", new BigDecimal("-1000"))),
                props(true, NetworkId.BASE));

        assertThat(result).hasSize(2);
        RawLeg native0 = result.get(1);
        assertThat(native0.assetContract()).isNull();
        assertThat(native0.assetSymbol()).isEqualTo("ETH");
        assertThat(native0.quantityDelta()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("1"));
    }

    @Test
    @DisplayName("recovers LP-exit native output via WETH Withdrawal (no LP-selector gate required)")
    void recoversLpExitNativeOutput() {
        Document log = withdrawalLog(WETH_BASE, ROUTER, "0x06f05b59d3b20000"); // 0.5 ETH
        OnChainRawTransactionView view = view(NetworkId.BASE, LP_EXIT_INPUT, List.of(log), List.of());

        List<RawLeg> result = NativeSettlementRecovery.enrichLegs(
                view, resolver, List.of(RawLeg.asset("0xusdc", "USDC", new BigDecimal("200"))),
                props(true));

        assertThat(result).hasSize(2);
        assertThat(result.get(1).assetSymbol()).isEqualTo("ETH");
        assertThat(result.get(1).quantityDelta()).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("0.5"));
    }

    @Test
    @DisplayName("no-op (double-count guard) when a native inbound leg already exists")
    void noOpWhenNativeInboundAlreadyPresent() {
        Document log = withdrawalLog(WETH_BASE, ROUTER, "0x0de0b6b3a7640000");
        OnChainRawTransactionView view = view(NetworkId.BASE, GENERIC_ROUTER_INPUT, List.of(log), List.of());

        List<RawLeg> input = List.of(RawLeg.nativeAsset("ETH", new BigDecimal("1")));
        List<RawLeg> result = NativeSettlementRecovery.enrichLegs(view, resolver, input, props(true));

        assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName("no-op (double-count guard) when an indexed internal transfer to wallet exists")
    void noOpWhenIndexedInternalTransferPresent() {
        Document log = withdrawalLog(WETH_BASE, ROUTER, "0x0de0b6b3a7640000");
        OnChainRawTransactionView view = view(
                NetworkId.BASE, GENERIC_ROUTER_INPUT, List.of(log),
                List.of(internalTransferTo(WALLET, "1000000000000000000")));

        List<RawLeg> input = List.of(RawLeg.asset("0xusdc", "USDC", new BigDecimal("-1000")));
        List<RawLeg> result = NativeSettlementRecovery.enrichLegs(view, resolver, input, props(true));

        assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName("sell-side rejection: Withdrawal src == wallet is not a payout")
    void rejectsSellSideWhenSrcIsWallet() {
        Document log = withdrawalLog(WETH_BASE, WALLET, "0x0de0b6b3a7640000");
        OnChainRawTransactionView view = view(NetworkId.BASE, GENERIC_ROUTER_INPUT, List.of(log), List.of());

        List<RawLeg> input = List.of(RawLeg.asset("0xusdc", "USDC", new BigDecimal("-1000")));
        List<RawLeg> result = NativeSettlementRecovery.enrichLegs(view, resolver, input, props(true));

        assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName("sell-side rejection: a matching wallet WETH outbound of the same wad excludes the log")
    void rejectsSellSideWhenMatchingWalletWethOutbound() {
        Document log = withdrawalLog(WETH_BASE, ROUTER, "0x0de0b6b3a7640000"); // 1 ETH
        OnChainRawTransactionView view = view(NetworkId.BASE, GENERIC_ROUTER_INPUT, List.of(log), List.of());

        // Wallet sold exactly 1 WETH; the router unwrapped it → not a native payout to the wallet.
        List<RawLeg> input = List.of(RawLeg.asset(WETH_BASE, "WETH", new BigDecimal("-1")));
        List<RawLeg> result = NativeSettlementRecovery.enrichLegs(view, resolver, input, props(true));

        assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName("per-chain gating: disabled globally or chain not on allow-list is a no-op; enabled recovers")
    void perChainGating() {
        Document log = withdrawalLog(WETH_BASE, ROUTER, "0x0de0b6b3a7640000");
        OnChainRawTransactionView baseView = view(NetworkId.BASE, GENERIC_ROUTER_INPUT, List.of(log), List.of());
        List<RawLeg> input = List.of(RawLeg.asset("0xusdc", "USDC", new BigDecimal("-1000")));

        // Global disabled → no-op.
        assertThat(NativeSettlementRecovery.enrichLegs(baseView, resolver, input, props(false)))
                .isSameAs(input);

        // Enabled but allow-list excludes BASE → no-op.
        assertThat(NativeSettlementRecovery.enrichLegs(baseView, resolver, input, props(true, NetworkId.LINEA)))
                .isSameAs(input);

        // Enabled for BASE → recovers.
        assertThat(NativeSettlementRecovery.enrichLegs(baseView, resolver, input, props(true, NetworkId.BASE)))
                .hasSize(2);
    }
}
