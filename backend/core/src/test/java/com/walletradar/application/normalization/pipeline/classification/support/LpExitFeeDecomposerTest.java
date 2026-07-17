package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import com.walletradar.testsupport.NetworkTestFixtures;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LpExitFeeDecomposer}.
 *
 * <p>Event layouts used:</p>
 * <pre>
 * DecreaseLiquidity(uint256 indexed tokenId, uint128 liquidity, uint256 amount0, uint256 amount1)
 *   topic0  = 0x26f6a048...
 *   data[0] = liquidity (uint128 padded to 32B)
 *   data[1] = amount0
 *   data[2] = amount1
 *
 * Collect(uint256 indexed tokenId, address recipient, uint256 amount0, uint256 amount1)
 *   topic0  = 0x40d0efd1...
 *   data[0] = recipient (address, padded to 32B)
 *   data[1] = amount0Collected
 *   data[2] = amount1Collected
 * </pre>
 */
class LpExitFeeDecomposerTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String WETH_CONTRACT = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2";
    private static final String USDC_CONTRACT = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";

    @BeforeAll
    static void bindNetworkNativeAssets() {
        // NativeWrappedTokenSupport.canonicalWeth now reads from network-descriptors.yml via the
        // NetworkNativeAssets bridge, which NetworkRegistry binds on construction. Building the test
        // registry (same convention as NetworkStablecoinContracts) populates the bridge.
        NetworkTestFixtures.registry();
    }

    // ---- scenario A: normal V3 exit with principal + fee in both slots ----

    @Test
    void decode_normal_v3Exit_returnsAmounts() {
        // WETH principal = 157000000000000000 (0.157 ETH in wei), total = 157168000000000000 (includes fee)
        BigInteger principal0 = new BigInteger("157000000000000000");
        BigInteger fee0 = new BigInteger("168000000000000000"); // intentionally large for test
        BigInteger total0 = principal0.add(fee0);

        // USDC principal = 322230000 (322.23 USDC in 1e6), fee = 1000000 (1.0 USDC)
        BigInteger principal1 = new BigInteger("322230000");
        BigInteger fee1 = new BigInteger("1000000");
        BigInteger total1 = principal1.add(fee1);

        OnChainRawTransactionView view = viewWithLogs(
                decreaseLiquidityLog(principal0, principal1),
                collectLog(total0, total1)
        );

        Optional<LpExitFeeAmounts> result = LpExitFeeDecomposer.decode(view);

        assertThat(result).isPresent();
        LpExitFeeAmounts amounts = result.get();
        assertThat(amounts.principalRaw0()).isEqualTo(principal0);
        assertThat(amounts.principalRaw1()).isEqualTo(principal1);
        assertThat(amounts.totalRaw0()).isEqualTo(total0);
        assertThat(amounts.totalRaw1()).isEqualTo(total1);
        assertThat(amounts.feeRaw0()).isEqualTo(fee0);
        assertThat(amounts.feeRaw1()).isEqualTo(fee1);
        assertThat(amounts.hasFee()).isTrue();
    }

    // ---- scenario B: fee-only asset (principal = 0 for slot 1) ----

    @Test
    void decode_principalZero_forSlot1_feeEqualsTotal() {
        BigInteger principal0 = new BigInteger("157000000000000000");
        BigInteger total0 = new BigInteger("157168000000000000");

        BigInteger principal1 = BigInteger.ZERO; // fully out-of-range, no principal returned
        BigInteger total1 = new BigInteger("1000000"); // only fees

        OnChainRawTransactionView view = viewWithLogs(
                decreaseLiquidityLog(principal0, principal1),
                collectLog(total0, total1)
        );

        Optional<LpExitFeeAmounts> result = LpExitFeeDecomposer.decode(view);

        assertThat(result).isPresent();
        LpExitFeeAmounts amounts = result.get();
        assertThat(amounts.feeRaw1()).isEqualTo(total1);
        assertThat(amounts.principalRaw1()).isEqualTo(BigInteger.ZERO);
        assertThat(amounts.hasFee()).isTrue();
    }

    // ---- scenario C: zero-fee exit (collect == decrease, no fees earned) ----

    @Test
    void decode_zeroFee_exit_hasFeeIsFalse() {
        BigInteger principal0 = new BigInteger("157000000000000000");
        BigInteger principal1 = new BigInteger("322230000");

        OnChainRawTransactionView view = viewWithLogs(
                decreaseLiquidityLog(principal0, principal1),
                collectLog(principal0, principal1) // total == principal → no fee
        );

        Optional<LpExitFeeAmounts> result = LpExitFeeDecomposer.decode(view);

        assertThat(result).isPresent();
        assertThat(result.get().hasFee()).isFalse();
        assertThat(result.get().feeRaw0()).isEqualTo(BigInteger.ZERO);
        assertThat(result.get().feeRaw1()).isEqualTo(BigInteger.ZERO);
    }

    // ---- scenario D: missing events → empty ----

    @Test
    void decode_noLogs_returnsEmpty() {
        OnChainRawTransactionView view = viewWithLogs();
        assertThat(LpExitFeeDecomposer.decode(view)).isEmpty();
    }

    @Test
    void decode_onlyDecreaseLiquidity_noCollect_returnsEmpty() {
        OnChainRawTransactionView view = viewWithLogs(
                decreaseLiquidityLog(new BigInteger("100"), new BigInteger("200"))
        );
        assertThat(LpExitFeeDecomposer.decode(view)).isEmpty();
    }

    // ---- scenario E: feeFractionsForContracts matches ERC-20 Transfers ----

    @Test
    void feeFractions_matchByTransferAmount_returnsFractionPerContract() {
        BigInteger principal0 = new BigInteger("157000000000000000"); // WETH slot0
        BigInteger fee0 = new BigInteger("168000000000000000");
        BigInteger total0 = principal0.add(fee0);

        BigInteger principal1 = new BigInteger("322230000"); // USDC slot1
        BigInteger fee1 = new BigInteger("1000000");
        BigInteger total1 = principal1.add(fee1);

        LpExitFeeAmounts amounts = new LpExitFeeAmounts(principal0, principal1, total0, total1);

        Document wethTransfer = erc20TransferToWallet(WETH_CONTRACT, total0);
        Document usdcTransfer = erc20TransferToWallet(USDC_CONTRACT, total1);
        OnChainRawTransactionView view = viewWithLogs(wethTransfer, usdcTransfer);

        Map<String, BigDecimal> fractions = LpExitFeeDecomposer.feeFractionsForContracts(amounts, view);

        assertThat(fractions).containsKey(WETH_CONTRACT);
        assertThat(fractions).containsKey(USDC_CONTRACT);

        BigDecimal expectedFraction0 = new BigDecimal(fee0).divide(new BigDecimal(total0), 18, java.math.RoundingMode.HALF_DOWN);
        BigDecimal expectedFraction1 = new BigDecimal(fee1).divide(new BigDecimal(total1), 18, java.math.RoundingMode.HALF_DOWN);
        assertThat(fractions.get(WETH_CONTRACT)).isEqualByComparingTo(expectedFraction0);
        assertThat(fractions.get(USDC_CONTRACT)).isEqualByComparingTo(expectedFraction1);
    }

    @Test
    void feeFractions_canonicalWeth_transferToOtherAddress_matchedAsNativeEthSlot() {
        // C2 (R1): WETH Transfer going to a non-wallet address (e.g. unwrapWETH9 burns WETH to 0x0
        // or sends to NonfungiblePositionManager before ETH reaches the wallet). Pass 2 picks this up
        // and emits a fee fraction keyed by the canonical WETH address so the materializer can split
        // the native-ETH flow.
        BigInteger principal0 = new BigInteger("157000000000000000");
        BigInteger total0 = new BigInteger("157168000000000000");
        LpExitFeeAmounts amounts = new LpExitFeeAmounts(principal0, BigInteger.ZERO, total0, BigInteger.ZERO);

        // WETH Transfer to a non-wallet address (simulates unwrapWETH9 burn or intermediate step)
        Document transferToOther = erc20TransferTo("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef", WETH_CONTRACT, total0);
        OnChainRawTransactionView view = viewWithLogs(transferToOther);

        Map<String, BigDecimal> fractions = LpExitFeeDecomposer.feeFractionsForContracts(amounts, view);
        // C2: canonical WETH Transfer (any recipient) resolves the unmatched slot → fraction emitted.
        BigDecimal expectedFraction = new BigDecimal(principal0.subtract(principal0)).add(
                new BigDecimal(total0.subtract(principal0)).divide(new BigDecimal(total0), 18, java.math.RoundingMode.HALF_DOWN));
        // fee = total0 - principal0 = 168000000000000000; fraction = 168000000000000000 / 157168000000000000
        BigDecimal fee0 = new BigDecimal(total0.subtract(principal0));
        BigDecimal expectedFrac = fee0.divide(new BigDecimal(total0), 18, java.math.RoundingMode.HALF_DOWN);
        assertThat(fractions).hasSize(1);
        assertThat(fractions.get(WETH_CONTRACT)).isEqualByComparingTo(expectedFrac);
    }

    @Test
    void feeFractions_nonCanonicalToken_transferToOtherAddress_notMatched() {
        // A non-canonical ERC-20 (not canonical WETH) going to a non-wallet address is still NOT matched.
        BigInteger principal0 = new BigInteger("157000000000000000");
        BigInteger total0 = new BigInteger("157168000000000000");
        LpExitFeeAmounts amounts = new LpExitFeeAmounts(principal0, BigInteger.ZERO, total0, BigInteger.ZERO);

        // Transfer of a random ERC-20 (not canonical WETH) to a different address — not matched.
        Document transferToOther = erc20TransferTo("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", total0);
        OnChainRawTransactionView view = viewWithLogs(transferToOther);

        Map<String, BigDecimal> fractions = LpExitFeeDecomposer.feeFractionsForContracts(amounts, view);
        assertThat(fractions).isEmpty();
    }

    // ---- helpers ----

    private OnChainRawTransactionView viewWithLogs(Document... logs) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.ETHEREUM.name());
        raw.setWalletAddress(WALLET);

        Document clarificationDoc = new Document();
        clarificationDoc.append("logs", List.of(logs));
        Document clarificationEvidence = new Document("receipt", clarificationDoc);

        Document rawData = new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("value", "0")
                .append("clarificationEvidence", clarificationEvidence)
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of()));
        raw.setRawData(rawData);
        return OnChainRawTransactionView.wrap(raw);
    }

    private Document decreaseLiquidityLog(BigInteger amount0, BigInteger amount1) {
        // data = [liquidity(32B), amount0(32B), amount1(32B)]
        String liquidity = "0000000000000000000000000000000000000000000000001bc16d674ec80000";
        String a0 = pad64(amount0.toString(16));
        String a1 = pad64(amount1.toString(16));
        return new Document("topics", List.of(
                LpExitFeeDecomposer.DECREASE_LIQUIDITY_TOPIC,
                "0x0000000000000000000000000000000000000000000000000000000000006cd7"  // tokenId
        )).append("data", "0x" + liquidity + a0 + a1);
    }

    private Document collectLog(BigInteger total0, BigInteger total1) {
        // data = [recipient(32B), amount0(32B), amount1(32B)]
        String recipient = "0000000000000000000000001111111111111111111111111111111111111111";
        String a0 = pad64(total0.toString(16));
        String a1 = pad64(total1.toString(16));
        return new Document("topics", List.of(
                LpExitFeeDecomposer.COLLECT_TOPIC,
                "0x0000000000000000000000000000000000000000000000000000000000006cd7"
        )).append("data", "0x" + recipient + a0 + a1);
    }

    private Document erc20TransferToWallet(String tokenContract, BigInteger rawAmount) {
        return erc20TransferTo(WALLET, tokenContract, rawAmount);
    }

    private Document erc20TransferTo(String toAddress, String tokenContract, BigInteger rawAmount) {
        String toTopic = "0x" + pad64(toAddress.replace("0x", "").toLowerCase());
        String amountHex = pad64(rawAmount.toString(16));
        return new Document("address", tokenContract)
                .append("topics", List.of(
                        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                        "0x000000000000000000000000c02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",  // from (any)
                        toTopic
                ))
                .append("data", "0x" + amountHex);
    }

    private static String pad64(String hex) {
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        return "0".repeat(Math.max(0, 64 - h.length())) + h;
    }
}
