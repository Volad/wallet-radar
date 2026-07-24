package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.lp.v4.V4PoolStateLookupService;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LpV4ExitFeeDecomposer} (R4 — Uniswap V4 / Pancake Infinity CL fee split).
 *
 * <p>Regression anchor: Unichain USD₮0/ETH exit {@code 0x628c0047…} (tokenId/salt 42775). V4 emits
 * only {@code ModifyLiquidity} (no amounts), so principal is derived from CL tick math and fee is
 * the residual over the received amount, clamped so principal never exceeds received.
 */
class LpV4ExitFeeDecomposerTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String WETH_ETHEREUM = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2";
    private static final String USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
    private static final String POOL_ID =
            "04b7dd024db64cfbe325191c818266e4776918cd9eaf021c26949a859e654b16";
    private static final long BLOCK = 14158899L;
    private static final BigInteger Q96 = BigInteger.ONE.shiftLeft(96);

    @BeforeAll
    static void bindNetworkNativeAssets() {
        NetworkTestFixtures.registry();
    }

    // ---- dual-token in-range: native ETH (token0) + ERC-20 (token1), price present ----

    @Test
    void dualTokenInRange_splitsPrincipalAndFee_nativeEthKeyedByWeth() {
        int tickLower = -60;
        int tickUpper = 60;
        BigInteger liquidity = new BigInteger("1000000000000"); // 1e12
        LpClMathSupport.Amounts principal =
                LpClMathSupport.getAmountsForLiquidity(Q96, tickLower, tickUpper, liquidity);

        BigInteger fee0 = new BigInteger("1000");   // native ETH (token0) fee, wei
        BigInteger fee1 = new BigInteger("500");    // USDC (token1) fee, raw
        BigInteger received0 = principal.amount0().add(fee0);
        BigInteger received1 = principal.amount1().add(fee1);

        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, tickLower, tickUpper, liquidity.negate()),
                List.of(erc20TransferToWallet(USDC, received1)),
                List.of(internalEthToWallet(received0))
        );

        Map<String, BigDecimal> result = decomposer(Optional.of(Q96))
                .feeFractionsForContracts(view)
                .orElseThrow();

        assertThat(result).containsOnlyKeys(WETH_ETHEREUM, USDC);
        assertThat(result.get(WETH_ETHEREUM)).isEqualByComparingTo(fraction(fee0, received0));
        assertThat(result.get(USDC)).isEqualByComparingTo(fraction(fee1, received1));
    }

    @Test
    void dualTokenInRange_priceUnavailable_returnsEmpty_noFabrication() {
        int tickLower = -60;
        int tickUpper = 60;
        BigInteger liquidity = new BigInteger("1000000000000");

        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, tickLower, tickUpper, liquidity.negate()),
                List.of(erc20TransferToWallet(USDC, new BigInteger("1000000"))),
                List.of(internalEthToWallet(new BigInteger("500000000000000000")))
        );

        assertThat(decomposer(Optional.empty()).feeFractionsForContracts(view)).isEmpty();
    }

    // ---- single-token out-of-range with price (all token1) ----

    @Test
    void singleTokenOutOfRange_withPrice_splitsAgainstNonZeroSlot() {
        int tickLower = -120;
        int tickUpper = -60; // price=1 (Q96) is above the range → all token1
        BigInteger liquidity = new BigInteger("1000000000000");
        LpClMathSupport.Amounts amounts =
                LpClMathSupport.getAmountsForLiquidity(Q96, tickLower, tickUpper, liquidity);
        assertThat(amounts.amount0()).isEqualByComparingTo(BigInteger.ZERO);

        BigInteger fee = new BigInteger("777");
        BigInteger received = amounts.amount1().add(fee);

        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, tickLower, tickUpper, liquidity.negate()),
                List.of(erc20TransferToWallet(USDC, received)),
                List.of()
        );

        Map<String, BigDecimal> result = decomposer(Optional.of(Q96))
                .feeFractionsForContracts(view)
                .orElseThrow();

        assertThat(result).containsOnlyKeys(USDC);
        assertThat(result.get(USDC)).isEqualByComparingTo(fraction(fee, received));
    }

    // ---- single-token out-of-range without price (ticks + liquidity only) ----

    @Test
    void singleTokenOutOfRange_noPrice_computesPrincipalFromTicks() {
        int tickLower = -120;
        int tickUpper = -60;
        BigInteger liquidity = new BigInteger("1000000000000");
        BigInteger sqrtA = LpClMathSupport.getSqrtRatioAtTick(tickLower);
        BigInteger sqrtB = LpClMathSupport.getSqrtRatioAtTick(tickUpper);
        BigInteger amount1Above = LpClMathSupport.getAmount1ForLiquidity(sqrtA, sqrtB, liquidity);

        // Tiny fee so received barely exceeds amount1Above and excludes the (larger) token0 branch.
        BigInteger received = amount1Above.add(BigInteger.ONE);

        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, tickLower, tickUpper, liquidity.negate()),
                List.of(erc20TransferToWallet(USDC, received)),
                List.of()
        );

        Map<String, BigDecimal> result = decomposer(Optional.empty())
                .feeFractionsForContracts(view)
                .orElseThrow();

        assertThat(result).containsOnlyKeys(USDC);
        assertThat(result.get(USDC)).isEqualByComparingTo(fraction(BigInteger.ONE, received));
    }

    @Test
    void singleTokenOutOfRange_noPrice_ambiguousSlot_returnsEmpty() {
        int tickLower = -120;
        int tickUpper = -60;
        BigInteger liquidity = new BigInteger("1000000000000");
        // Received far larger than any computed degenerate amount → principal/received below floor.
        BigInteger received = new BigInteger("999999999999999999999999");

        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, tickLower, tickUpper, liquidity.negate()),
                List.of(erc20TransferToWallet(USDC, received)),
                List.of()
        );

        assertThat(decomposer(Optional.empty()).feeFractionsForContracts(view)).isEmpty();
    }

    // ---- conservation clamp: computed principal >= received → fee 0 (no fabricated income) ----

    @Test
    void computedPrincipalExceedsReceived_clampsFeeToZero() {
        int tickLower = -60;
        int tickUpper = 60;
        BigInteger liquidity = new BigInteger("1000000000000");
        LpClMathSupport.Amounts principal =
                LpClMathSupport.getAmountsForLiquidity(Q96, tickLower, tickUpper, liquidity);

        // token1 (USDC) received is LESS than computed principal → clamp → fee 0 (absent).
        BigInteger received1 = principal.amount1().subtract(BigInteger.valueOf(100));
        // token0 (ETH) has a real fee → present.
        BigInteger fee0 = new BigInteger("250");
        BigInteger received0 = principal.amount0().add(fee0);

        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, tickLower, tickUpper, liquidity.negate()),
                List.of(erc20TransferToWallet(USDC, received1)),
                List.of(internalEthToWallet(received0))
        );

        Map<String, BigDecimal> result = decomposer(Optional.of(Q96))
                .feeFractionsForContracts(view)
                .orElseThrow();

        assertThat(result).containsOnlyKeys(WETH_ETHEREUM);
        assertThat(result.get(WETH_ETHEREUM)).isEqualByComparingTo(fraction(fee0, received0));
    }

    // ---- evidence / guards ----

    @Test
    void positiveLiquidityDelta_isEntry_returnsEmpty() {
        BigInteger liquidity = new BigInteger("1000000000000");
        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, -60, 60, liquidity), // positive → add liquidity (entry)
                List.of(erc20TransferToWallet(USDC, new BigInteger("1000000"))),
                List.of()
        );
        assertThat(decomposer(Optional.of(Q96)).feeFractionsForContracts(view)).isEmpty();
    }

    @Test
    void noModifyLiquidityLog_returnsEmpty() {
        OnChainRawTransactionView view = viewFor(
                erc20TransferToWallet(USDC, new BigInteger("1000000"))
        );
        assertThat(LpV4ExitFeeDecomposer.hasModifyLiquidityEvidence(view)).isFalse();
        assertThat(decomposer(Optional.of(Q96)).feeFractionsForContracts(view)).isEmpty();
    }

    @Test
    void hasModifyLiquidityEvidence_detectsTopic() {
        OnChainRawTransactionView view = viewFor(
                modifyLiquidityLog(POOL_ID, -60, 60, new BigInteger("-1000000000000")),
                List.of(erc20TransferToWallet(USDC, new BigInteger("1000000"))),
                List.of()
        );
        assertThat(LpV4ExitFeeDecomposer.hasModifyLiquidityEvidence(view)).isTrue();
    }

    // ---- helpers ----

    private static LpV4ExitFeeDecomposer decomposer(Optional<BigInteger> sqrtPrice) {
        V4PoolStateLookupService lookup = mock(V4PoolStateLookupService.class);
        when(lookup.getSqrtPriceX96(eq(NetworkId.ETHEREUM), anyString(), anyLong())).thenReturn(sqrtPrice);
        return new LpV4ExitFeeDecomposer(lookup);
    }

    private static BigDecimal fraction(BigInteger fee, BigInteger received) {
        return new BigDecimal(fee).divide(new BigDecimal(received), 18, RoundingMode.HALF_DOWN);
    }

    private OnChainRawTransactionView viewFor(Document... logs) {
        return viewFor(logs.length == 0 ? null : logs[0], List.of(), List.of(), logs);
    }

    private OnChainRawTransactionView viewFor(
            Document modifyLog,
            List<Document> erc20Transfers,
            List<Document> internalTransfers
    ) {
        Document[] logs = new Document[1 + erc20Transfers.size()];
        logs[0] = modifyLog;
        for (int i = 0; i < erc20Transfers.size(); i++) {
            logs[i + 1] = erc20Transfers.get(i);
        }
        return viewFor(modifyLog, erc20Transfers, internalTransfers, logs);
    }

    private OnChainRawTransactionView viewFor(
            Document modifyLog,
            List<Document> erc20Transfers,
            List<Document> internalTransfers,
            Document... logs
    ) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.ETHEREUM.name());
        raw.setWalletAddress(WALLET);

        Document receipt = new Document("logs", List.of(logs));
        Document clarificationEvidence = new Document("receipt", receipt);

        Document rawData = new Document()
                .append("timeStamp", "1744907258")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("value", "0")
                .append("blockNumber", Long.toString(BLOCK))
                .append("clarificationEvidence", clarificationEvidence)
                .append("explorer", new Document("tokenTransfers", List.of())
                        .append("internalTransfers", internalTransfers));
        raw.setRawData(rawData);
        return OnChainRawTransactionView.wrap(raw);
    }

    private static Document modifyLiquidityLog(String poolId, int tickLower, int tickUpper, BigInteger liquidityDelta) {
        String data = "0x"
                + encodeInt256(BigInteger.valueOf(tickLower))
                + encodeInt256(BigInteger.valueOf(tickUpper))
                + encodeInt256(liquidityDelta)
                + pad64("a717"); // salt = tokenId 42775
        return new Document("address", "0x1f98400000000000000000000000000000000004")
                .append("topics", List.of(
                        LpV4ExitFeeDecomposer.MODIFY_LIQUIDITY_TOPIC,
                        "0x" + poolId,
                        "0x0000000000000000000000004529a01c7a0410167c5740c487a8de60232617bf"
                ))
                .append("data", data);
    }

    private static Document erc20TransferToWallet(String contract, BigInteger rawAmount) {
        return new Document("address", contract)
                .append("topics", List.of(
                        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                        "0x0000000000000000000000004529a01c7a0410167c5740c487a8de60232617bf",
                        "0x" + pad64(WALLET.substring(2))
                ))
                .append("data", "0x" + pad64(rawAmount.toString(16)));
    }

    private static Document internalEthToWallet(BigInteger wei) {
        return new Document("from", "0x4529a01c7a0410167c5740c487a8de60232617bf")
                .append("to", WALLET)
                .append("value", wei.toString())
                .append("isError", "0");
    }

    private static String encodeInt256(BigInteger value) {
        BigInteger v = value.signum() < 0 ? value.add(BigInteger.ONE.shiftLeft(256)) : value;
        return pad64(v.toString(16));
    }

    private static String pad64(String hex) {
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        return "0".repeat(Math.max(0, 64 - h.length())) + h;
    }
}
