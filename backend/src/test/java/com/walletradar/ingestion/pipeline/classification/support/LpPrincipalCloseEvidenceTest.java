package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the B4 harvest-only gate:
 * LpPrincipalCloseEvidence.refineLifecycleType + hasZeroLiquidityDecrease.
 */
class LpPrincipalCloseEvidenceTest {

    // decreaseLiquidity selector: 0x0c49ccbe
    // ABI layout after selector (each slot = 64 hex chars / 32 bytes):
    //   slot 0: tokenId (uint256)
    //   slot 1: liquidity (uint128, zero-padded)
    private static final String TOKEN_ID_HEX =
            "000000000000000000000000000000000000000000000000000000000006cd77"; // 445831
    private static final String ZERO_LIQUIDITY =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String NONZERO_LIQUIDITY =
            "0000000000000000000000000000000000000000000000001bc16d674ec80000";

    // Multicall selector used in real PancakeSwap V3 CAKE-harvest transactions
    private static final String MULTICALL_PREFIX = "0xac9650d8";

    /**
     * CAKE-only inflows + decreaseLiquidity(liquidity=0) in calldata → LP_FEE_CLAIM.
     * This is the canonical PancakeSwap V3 "collect rewards without removing liquidity" shape.
     */
    @Test
    void cakeOnlyInflows_zeroLiquidityDecrease_refinesToFeeClm() {
        String calldata = MULTICALL_PREFIX
                + "0c49ccbe" + TOKEN_ID_HEX + ZERO_LIQUIDITY;
        OnChainRawTransactionView view = viewWithInputData("0x" + calldata.replace("0x", ""));

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "CAKE", new BigDecimal("12.5"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
    }

    /**
     * CAKE-only inflows + decreaseLiquidity(liquidity>0) in calldata → stays LP_EXIT.
     * Represents real CL-pool exit where the position is 100% in CAKE range.
     */
    @Test
    void cakeOnlyInflows_nonZeroLiquidityDecrease_staysLpExit() {
        String calldata = "0xac9650d8"
                + "0c49ccbe" + TOKEN_ID_HEX + NONZERO_LIQUIDITY;
        OnChainRawTransactionView view = viewWithInputData(calldata);

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "CAKE", new BigDecimal("8.0"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    /**
     * WETH + CAKE inflows + zero liquidity → stays LP_EXIT.
     * isHarvestOnlyRewardPattern is false (mixed principal), so no downgrade regardless of liquidity.
     */
    @Test
    void wethAndCakeInflows_zeroLiquidityDecrease_staysLpExit() {
        String calldata = "0xac9650d8"
                + "0c49ccbe" + TOKEN_ID_HEX + ZERO_LIQUIDITY;
        OnChainRawTransactionView view = viewWithInputData(calldata);

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "WETH", new BigDecimal("0.022")),
                RawLeg.asset("0x1", "CAKE", new BigDecimal("1.5"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    /**
     * NPM bare burn(tokenId) shape — no ERC721 Transfer to wallet in logs.
     * Such a call IS a genuine LP exit (position NFT is destroyed).
     * Stays LP_EXIT because isMasterChefWithdrawDirectCall requires
     * hasAnyErc721TransferToWallet=true, which is false here (no logs).
     * Discriminated from MasterChef harvest (T1) by absent ERC721 Transfer(MasterChef→wallet).
     */
    @Test
    void cakeOnlyInflows_burnSelectorOnlyNoDecreaseLiquidity_staysLpExit() {
        // BURN_SELECTOR = 0x00f714ce — triggers hasPositionReductionEvidence, but no decreaseLiquidity
        OnChainRawTransactionView view = viewWithMethodId("0x00f714ce");

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "CAKE", new BigDecimal("5.0"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // T1–T4: MasterChef withdraw (isMasterChefWithdrawDirectCall) gate
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * T1 — MasterChef withdraw: CAKE-only inflows + BURN_SELECTOR + ERC721 Transfer(MasterChef→wallet).
     * isMasterChefWithdrawDirectCall returns true → LP_FEE_CLAIM.
     */
    @Test
    void masterChefWithdraw_cakeOnlyInflows_erc721ToWallet_refinesToFeeClm() {
        OnChainRawTransactionView view = viewWithMethodIdAndLogs("0x00f714ce", List.of(erc721TransferToWalletLog()));

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "CAKE", new BigDecimal("9.0"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
    }

    /**
     * T2 — MasterChef withdraw shape but inputData embeds decreaseLiquidity → LP_EXIT.
     * isMasterChefWithdrawDirectCall guard fires because decreaseLiquidity is present,
     * making this a real principal exit.
     */
    @Test
    void masterChefWithdraw_cakeOnlyInflows_embeddedDecreaseLiquidity_staysLpExit() {
        // inputData outer selector = 0x00f714ce; decreaseLiquidity selector embedded in payload.
        // containsEmbeddedSelector checks inputData.substring(10), so 0c49ccbe must appear there.
        // Non-zero liquidity also neutralises the B4 gate.
        String inputData = "0x00f714ce" + "0000000000000000" + "0c49ccbe" + TOKEN_ID_HEX + NONZERO_LIQUIDITY;
        OnChainRawTransactionView view = viewWithMethodIdInputDataAndLogs(
                "0x00f714ce", inputData, List.of(erc721TransferToWalletLog()));

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "CAKE", new BigDecimal("3.0"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    /**
     * T3 — Multicall outer (not MasterChef): top-level methodId is multicall, 0x00f714ce embedded.
     * isMasterChefWithdrawDirectCall checks top-level selector only → returns false → LP_EXIT.
     */
    @Test
    void multicallOuter_burnEmbedded_cakeOnlyInflows_staysLpExit() {
        // Embed burn selector inside multicall calldata; no decreaseLiquidity embedded
        String inputData = "0xac9650d8" + "00f714ce" + TOKEN_ID_HEX;
        OnChainRawTransactionView view = viewWithMethodIdInputDataAndLogs(
                "0xac9650d8", inputData, List.of(erc721TransferToWalletLog()));

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "CAKE", new BigDecimal("2.5"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    /**
     * T4 — MasterChef withdraw but WETH + CAKE inflows.
     * isHarvestOnlyRewardPattern returns false (WETH is a principal token) → neither B4
     * nor the MasterChef branch fires → LP_EXIT.
     */
    @Test
    void masterChefWithdraw_wethAndCakeInflows_erc721ToWallet_staysLpExit() {
        OnChainRawTransactionView view = viewWithMethodIdAndLogs("0x00f714ce", List.of(erc721TransferToWalletLog()));

        List<RawLeg> legs = List.of(
                RawLeg.asset("0x0", "WETH", new BigDecimal("0.05")),
                RawLeg.asset("0x1", "CAKE", new BigDecimal("7.0"))
        );

        NormalizedTransactionType result = LpPrincipalCloseEvidence.refineLifecycleType(
                view, legs, NormalizedTransactionType.LP_EXIT);

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // hasZeroLiquidityDecrease direct tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void hasZeroLiquidityDecrease_withZeroLiquidity_returnsTrue() {
        String calldata = "0x0c49ccbe" + TOKEN_ID_HEX + ZERO_LIQUIDITY;
        assertThat(LpPrincipalCloseEvidence.hasZeroLiquidityDecrease(viewWithInputData(calldata))).isTrue();
    }

    @Test
    void hasZeroLiquidityDecrease_withNonZeroLiquidity_returnsFalse() {
        String calldata = "0x0c49ccbe" + TOKEN_ID_HEX + NONZERO_LIQUIDITY;
        assertThat(LpPrincipalCloseEvidence.hasZeroLiquidityDecrease(viewWithInputData(calldata))).isFalse();
    }

    @Test
    void hasZeroLiquidityDecrease_noDecreaseSelector_returnsFalse() {
        assertThat(LpPrincipalCloseEvidence.hasZeroLiquidityDecrease(viewWithInputData("0x00f714ce"))).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static OnChainRawTransactionView viewWithInputData(String inputData) {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("input", inputData);
        return OnChainRawTransactionView.wrap(raw);
    }

    private static OnChainRawTransactionView viewWithMethodId(String methodId) {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("methodId", methodId);
        return OnChainRawTransactionView.wrap(raw);
    }

    private static OnChainRawTransactionView viewWithMethodIdAndLogs(String methodId, List<Document> logs) {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("methodId", methodId);
        raw.getRawData().put("logs", logs);
        return OnChainRawTransactionView.wrap(raw);
    }

    private static OnChainRawTransactionView viewWithMethodIdInputDataAndLogs(
            String methodId, String inputData, List<Document> logs) {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("methodId", methodId);
        raw.getRawData().put("input", inputData);
        raw.getRawData().put("logs", logs);
        return OnChainRawTransactionView.wrap(raw);
    }

    /**
     * ERC721 Transfer log with Transfer(MasterChef→wallet).
     * topics[2] = wallet address (0x1111...1111) padded to 32 bytes.
     */
    private static Document erc721TransferToWalletLog() {
        return new Document("topics", List.of(
                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                "0x000000000000000000000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "0x0000000000000000000000001111111111111111111111111111111111111111"
        ));
    }

    private static RawTransaction baseRaw() {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.BASE.name());
        raw.setWalletAddress("0x1111111111111111111111111111111111111111");
        raw.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", "0x1111111111111111111111111111111111111111")
                .append("value", "0")
                .append("explorer", new Document("tokenTransfers", List.of()).append("internalTransfers", List.of())));
        return raw;
    }
}
