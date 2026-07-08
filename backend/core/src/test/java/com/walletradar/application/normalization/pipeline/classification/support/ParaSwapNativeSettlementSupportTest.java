package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ParaSwapNativeSettlementSupport}.
 *
 * <p>Covers both {@code swapExactAmountOut} (0x7f457675) and {@code swapExactAmountIn}
 * (0xe3ead59e) selectors for Paraswap V6 when the explorer's internal transfers are missing
 * due to indexer lag (e.g. BlockScout BASE not indexing blocks yet).
 */
class ParaSwapNativeSettlementSupportTest {

    private static final String WALLET = "0x1a87f12a00000000000000000000000000000001";
    private static final String CONTRACT = "0xdef1c0ded9bec7f1a1670819833240f027b25eff";
    private static final String NATIVE_SENTINEL = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String WETH_CONTRACT = "0x4200000000000000000000000000000000000006";
    private static final String USDC_CONTRACT = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913";

    private final NativeAssetSymbolResolver resolver = new NativeAssetSymbolResolver(NetworkTestFixtures.registry());

    // ─── calldata helpers ─────────────────────────────────────────────────────

    /**
     * Builds swapExactAmountIn calldata with an inline SwapData struct:
     * [selector][executor][srcToken][destToken][srcAmount][destAmount][expectedAmount]
     * [beneficiary][partner][...][embedded WETH withdraw selector + amount]
     *
     * <p>The struct is encoded inline (all fields are static types).
     * Argument indices used by {@link CalldataDecodingSupport}:
     * <ul>
     *   <li>arg[0] executor</li>
     *   <li>arg[1] srcToken</li>
     *   <li>arg[2] destToken</li>
     *   <li>arg[3] srcAmount</li>
     *   <li>arg[4] destAmount</li>
     * </ul>
     */
    private static String swapExactAmountInCalldata(
            String destTokenAddress,
            long srcAmountWei,
            long destAmountWei,
            boolean embedWethWithdraw
    ) {
        String selector = "e3ead59e";
        String executor = pad("8faa706e35e22b3f1de8d35f1d46ad18ccc50e7");
        String srcToken = pad("833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        String destToken = pad(destTokenAddress.startsWith("0x") ? destTokenAddress.substring(2) : destTokenAddress);
        String srcAmount = padUint(srcAmountWei);
        String destAmount = padUint(destAmountWei);
        String expectedAmount = padUint(destAmountWei);
        String beneficiary = pad("1a87f12a00000000000000000000000000000001");
        String partner = pad("0000000000000000000000000000000000000000");

        String wethWithdrawEmbedded = embedWethWithdraw
                ? "2e1a7d4d" + padUint(destAmountWei)
                : "";

        return "0x" + selector
                + executor + srcToken + destToken + srcAmount + destAmount
                + expectedAmount + beneficiary + partner
                + wethWithdrawEmbedded;
    }

    /**
     * Builds swapExactAmountOut calldata for regression testing.
     * Selector 0x7f457675; arg layout: executor, srcToken, destToken, srcAmountMax,
     * destAmount (exact out), srcAmount (actual, for refund), ..., beneficiary.
     *
     * <p>For the exact-out test we embed the WETH withdraw with the exact dest amount
     * so that the existing validation {@code exactOutRaw == embeddedUnwrapAmount} passes.
     */
    private static String swapExactAmountOutCalldata(long destAmountWei) {
        String selector = "7f457675";
        String executor = pad("8faa706e35e22b3f1de8d35f1d46ad18ccc50e7");
        String srcToken = pad("833589fcd6edb6e08f4c7c32d4f71b54bda02913");
        // arg[2] destToken = native sentinel
        String destToken = pad("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        String srcAmountMax = padUint(1_200_000_000L);
        // arg[4] = exact out amount
        String exactOut = padUint(destAmountWei);
        // more args …
        String pad5 = padUint(0);
        String pad6 = padUint(0);
        // arg[7] = beneficiary = wallet
        String beneficiary = pad("1a87f12a00000000000000000000000000000001");
        String wethWithdraw = "2e1a7d4d" + padUint(destAmountWei);

        return "0x" + selector
                + executor + srcToken + destToken + srcAmountMax + exactOut
                + pad5 + pad6 + beneficiary
                + wethWithdraw;
    }

    private static String pad(String addressHex) {
        return String.format("%64s", addressHex).replace(' ', '0');
    }

    private static String padUint(long value) {
        return String.format("%064x", value);
    }

    // ─── view helpers ─────────────────────────────────────────────────────────

    private static OnChainRawTransactionView viewWithInput(
            String walletAddress,
            String inputData,
            List<Document> internalTransfers
    ) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.BASE.name());
        raw.setWalletAddress(walletAddress);
        raw.setRawData(new Document()
                .append("hash", "0xdeadbeef01")
                .append("blockNumber", "46863177")
                .append("timeStamp", "1748800000")
                .append("transactionIndex", "10")
                .append("input", inputData)
                .append("to", CONTRACT)
                .append("from", walletAddress)
                .append("value", "0")
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of(
                                new Document()
                                        .append("contractAddress", USDC_CONTRACT)
                                        .append("tokenSymbol", "USDC")
                                        .append("tokenDecimal", "6")
                                        .append("from", walletAddress)
                                        .append("to", CONTRACT)
                                        .append("value", "1000000000")
                        ))
                        .append("internalTransfers", internalTransfers)));
        return OnChainRawTransactionView.wrap(raw);
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    void swapExactAmountIn_nativeDestToken_noExistingNativeLeg_synthethizesEthInbound() {
        long destAmountWei = 1_000_000_000_000_000_000L; // 1 ETH
        String input = swapExactAmountInCalldata(NATIVE_SENTINEL, 1_000_000_000L, destAmountWei, true);

        OnChainRawTransactionView view = viewWithInput(WALLET, input, List.of());

        // Only the USDC outbound leg (no native from internal transfers)
        List<RawLeg> legs = List.of(RawLeg.asset(USDC_CONTRACT, "USDC", BigDecimal.valueOf(-1000)));

        List<RawLeg> enriched = ParaSwapNativeSettlementSupport.enrichLegs(view, resolver, legs);

        assertThat(enriched).hasSize(2);
        RawLeg nativeLeg = enriched.stream()
                .filter(l -> l.assetContract() == null && !l.fee())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No native leg added"));
        assertThat(nativeLeg.quantityDelta()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(nativeLeg.assetSymbol()).isEqualToIgnoringCase("ETH");
    }

    @Test
    void swapExactAmountIn_nativeDestToken_smallerDestAmount_correctDecimalConversion() {
        // 0x07a1740bf724c0da ≈ 549848223855387978 wei ≈ 0.5498 ETH (from the real tx)
        long destAmountWei = 549_848_223_855_387_978L;
        String input = swapExactAmountInCalldata(NATIVE_SENTINEL, 1_000_000_000L, destAmountWei, true);

        OnChainRawTransactionView view = viewWithInput(WALLET, input, List.of());
        List<RawLeg> legs = List.of(RawLeg.asset(USDC_CONTRACT, "USDC", BigDecimal.valueOf(-1000)));

        List<RawLeg> enriched = ParaSwapNativeSettlementSupport.enrichLegs(view, resolver, legs);

        assertThat(enriched).hasSize(2);
        RawLeg nativeLeg = enriched.stream()
                .filter(l -> l.assetContract() == null && !l.fee())
                .findFirst()
                .orElseThrow();
        BigDecimal expectedEth = new BigDecimal(destAmountWei).movePointLeft(18);
        assertThat(nativeLeg.quantityDelta()).isEqualByComparingTo(expectedEth);
    }

    @Test
    void swapExactAmountIn_nativeDestToken_existingNativeLegPresent_doesNotDuplicate() {
        long destAmountWei = 1_000_000_000_000_000_000L;
        String input = swapExactAmountInCalldata(NATIVE_SENTINEL, 1_000_000_000L, destAmountWei, true);

        OnChainRawTransactionView view = viewWithInput(WALLET, input, List.of());

        // Simulate BlockScout already provided an ETH inbound leg (internal transfer indexed)
        List<RawLeg> legs = List.of(
                RawLeg.asset(USDC_CONTRACT, "USDC", BigDecimal.valueOf(-1000)),
                RawLeg.nativeAsset("ETH", BigDecimal.ONE)
        );

        List<RawLeg> enriched = ParaSwapNativeSettlementSupport.enrichLegs(view, resolver, legs);

        assertThat(enriched).hasSize(2);
        long nativeInboundCount = enriched.stream()
                .filter(l -> l.assetContract() == null && !l.fee() && l.quantityDelta().signum() > 0)
                .count();
        assertThat(nativeInboundCount).isEqualTo(1);
    }

    @Test
    void swapExactAmountIn_nonNativeDestToken_doesNotSynthesize() {
        long destAmountWei = 1_000_000_000_000_000_000L;
        String input = swapExactAmountInCalldata(WETH_CONTRACT, 1_000_000_000L, destAmountWei, true);

        OnChainRawTransactionView view = viewWithInput(WALLET, input, List.of());
        List<RawLeg> legs = List.of(RawLeg.asset(USDC_CONTRACT, "USDC", BigDecimal.valueOf(-1000)));

        List<RawLeg> enriched = ParaSwapNativeSettlementSupport.enrichLegs(view, resolver, legs);

        assertThat(enriched).hasSize(1);
    }

    @Test
    void swapExactAmountIn_nativeDestToken_withoutWethWithdrawEmbedded_doesNotSynthesize() {
        long destAmountWei = 1_000_000_000_000_000_000L;
        // embedWethWithdraw = false
        String input = swapExactAmountInCalldata(NATIVE_SENTINEL, 1_000_000_000L, destAmountWei, false);

        OnChainRawTransactionView view = viewWithInput(WALLET, input, List.of());
        List<RawLeg> legs = List.of(RawLeg.asset(USDC_CONTRACT, "USDC", BigDecimal.valueOf(-1000)));

        List<RawLeg> enriched = ParaSwapNativeSettlementSupport.enrichLegs(view, resolver, legs);

        assertThat(enriched).hasSize(1);
    }

    @Test
    void swapExactAmountOut_nativeDestToken_noExistingNativeLeg_synthethizesEthInbound() {
        long destAmountWei = 500_000_000_000_000_000L; // 0.5 ETH
        String input = swapExactAmountOutCalldata(destAmountWei);

        OnChainRawTransactionView view = viewWithInput(WALLET, input, List.of());
        List<RawLeg> legs = List.of(RawLeg.asset(USDC_CONTRACT, "USDC", BigDecimal.valueOf(-1000)));

        List<RawLeg> enriched = ParaSwapNativeSettlementSupport.enrichLegs(view, resolver, legs);

        assertThat(enriched).hasSize(2);
        RawLeg nativeLeg = enriched.stream()
                .filter(l -> l.assetContract() == null && !l.fee())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No native leg added"));
        BigDecimal expectedEth = new BigDecimal(destAmountWei).movePointLeft(18);
        assertThat(nativeLeg.quantityDelta()).isEqualByComparingTo(expectedEth);
    }

    @Test
    void nullOrEmptyInputs_returnOriginalLegs() {
        List<RawLeg> legs = List.of(RawLeg.asset(USDC_CONTRACT, "USDC", BigDecimal.valueOf(-100)));
        assertThat(ParaSwapNativeSettlementSupport.enrichLegs(null, resolver, legs)).isSameAs(legs);
        assertThat(ParaSwapNativeSettlementSupport.enrichLegs(
                viewWithInput(WALLET, "0xe3ead59e", List.of()),
                null,
                legs
        )).isSameAs(legs);
        assertThat(ParaSwapNativeSettlementSupport.enrichLegs(
                viewWithInput(WALLET, "0xe3ead59e", List.of()),
                resolver,
                null
        )).isNull();
        assertThat(ParaSwapNativeSettlementSupport.enrichLegs(
                viewWithInput(WALLET, "0xe3ead59e", List.of()),
                resolver,
                List.of()
        )).isEmpty();
    }
}
