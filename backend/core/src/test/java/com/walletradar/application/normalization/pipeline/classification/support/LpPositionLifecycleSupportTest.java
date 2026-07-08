package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LpPositionLifecycleSupport}.
 */
class LpPositionLifecycleSupportTest {

    private static final String WALLET = "0xaabbccdd00000000000000000000000000000001";
    private static final String OTHER = "0x1234000000000000000000000000000000000001";
    private static final String CONTRACT = "0x03a520b32c04bf3beef7beb72e919cf822ed34f1";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String MODIFY_LIQUIDITY_TOPIC =
            "0xf208f4912782fd25c7f114ca3723a2d5dd6f3bcc3ac8db5af63baa85f711d5ec";
    private static final String MODIFY_LIQUIDITIES_SELECTOR = "0xdd46508f";

    // ─── helpers ──────────────────────────────────────────────────────────────

    /** Pads a hex address (without 0x) to a 32-byte topic. */
    private static String padAddress(String address) {
        String stripped = address.startsWith("0x") ? address.substring(2) : address;
        return "0x" + String.format("%64s", stripped).replace(' ', '0');
    }

    /** Encodes a decimal tokenId as a 32-byte padded hex topic. */
    private static String tokenIdTopic(long tokenId) {
        return "0x" + String.format("%064x", tokenId);
    }

    private static OnChainRawTransactionView viewWithLogs(List<Document> logs) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.BASE.name());
        raw.setWalletAddress(WALLET);
        // persistedLogs() falls back to rawData.logs when no clarification evidence is set
        raw.setRawData(new Document()
                .append("hash", "0xdeadbeef")
                .append("blockNumber", "123456")
                .append("timeStamp", "1748800000")
                .append("transactionIndex", "1")
                .append("input", "0xac9650d8")
                .append("to", CONTRACT)
                .append("from", WALLET)
                .append("value", "0")
                .append("logs", logs)
                .append("explorer", new Document()
                        .append("tokenTransfers", List.of())
                        .append("internalTransfers", List.of())));
        return OnChainRawTransactionView.wrap(raw);
    }

    private static OnChainRawTransactionView modifyLiquiditiesView(List<Document> logs,
                                                                    List<Document> tokenTransfers) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.BSC.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("hash", "0xcafe1234")
                .append("blockNumber", "99000000")
                .append("timeStamp", "1748800000")
                .append("transactionIndex", "0")
                .append("input", MODIFY_LIQUIDITIES_SELECTOR + "00")
                .append("methodId", MODIFY_LIQUIDITIES_SELECTOR)
                .append("to", CONTRACT)
                .append("from", WALLET)
                .append("value", "0")
                .append("logs", logs)
                .append("explorer", new Document()
                        .append("tokenTransfers", tokenTransfers)
                        .append("internalTransfers", List.of())));
        return OnChainRawTransactionView.wrap(raw);
    }

    /**
     * Builds a ModifyLiquidity event log with the given liquidityDelta (word index 2).
     * word0 = tickLower, word1 = tickUpper (both negative ticks to confirm word index matters),
     * word2 = liquidityDelta, word3 = salt=0.
     */
    private static Document modifyLiquidityLog(String liquidityDeltaHex) {
        // tickLower = -212970 → negative int256 (to verify word 0/1 are NOT used)
        String tickLower = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffcc016";
        // tickUpper = -188296 → also negative
        String tickUpper = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd1c78";
        String salt = "0000000000000000000000000000000000000000000000000000000000000000";
        String data = "0x" + tickLower + tickUpper + liquidityDeltaHex + salt;
        return new Document()
                .append("address", "0x146b020399769339509c98b7b353d19130c150ec")
                .append("topics", List.of(
                        MODIFY_LIQUIDITY_TOPIC,
                        "0x04b7dd024db64cfbe325191c818266e4776918cd9eaf021c26949a859e654b16",
                        "0x0000000000000000000000004529a01c7a0410167c5740c487a8de60232617bf"
                ))
                .append("data", data);
    }

    /** Inbound ERC-20 token transfer (e.g. fee tokens received from pool). */
    private static RawLeg inboundTokenLeg(String symbol) {
        return new RawLeg(
                "0xtoken" + symbol,
                symbol,
                new BigDecimal("10.5"),
                false
        );
    }

    private static NormalizedTransactionType classifyModifyLiquidities(
            List<Document> logs, List<RawLeg> movementLegs) {
        OnChainRawTransactionView view = modifyLiquiditiesView(logs, List.of());
        return LpPositionLifecycleSupport.resolvePositionManagerType(
                view, movementLegs, Optional.empty(), Optional.empty());
    }

    private static Document erc721TransferLog(String from, String to, long tokenId) {
        return new Document()
                .append("address", CONTRACT)
                .append("topics", List.of(
                        ERC721_TRANSFER_TOPIC,
                        padAddress(from),
                        padAddress(to),
                        tokenIdTopic(tokenId)
                ))
                .append("data", "0x");
    }

    private static Document erc20TransferLog(String from, String to) {
        return new Document()
                .append("address", CONTRACT)
                .append("topics", List.of(
                        ERC721_TRANSFER_TOPIC,  // same event signature
                        padAddress(from),
                        padAddress(to)
                        // no 4th topic → ERC-20
                ))
                .append("data", "0x000000000000000000000000000000000000000000000000000000003b9aca00");
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    void erc721MintToWallet_returnsTokenIdAsDecimalString() {
        String zeroAddress = "0x0000000000000000000000000000000000000000";
        long tokenId = 1_234_567L;
        OnChainRawTransactionView view = viewWithLogs(List.of(
                erc721TransferLog(zeroAddress, WALLET, tokenId)
        ));

        String result = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(view);

        assertThat(result).isEqualTo(String.valueOf(tokenId));
    }

    @Test
    void erc721TransferToWallet_nonMintOrigin_returnsTokenId() {
        long tokenId = 922_846L;
        OnChainRawTransactionView view = viewWithLogs(List.of(
                erc721TransferLog(OTHER, WALLET, tokenId)
        ));

        String result = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(view);

        assertThat(result).isEqualTo(String.valueOf(tokenId));
    }

    @Test
    void erc20TransferLog_threeTopics_returnsNull() {
        OnChainRawTransactionView view = viewWithLogs(List.of(
                erc20TransferLog(OTHER, WALLET)
        ));

        String result = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(view);

        assertThat(result).isNull();
    }

    @Test
    void erc721TransferToOtherAddress_returnsNull() {
        long tokenId = 12345L;
        OnChainRawTransactionView view = viewWithLogs(List.of(
                erc721TransferLog(OTHER, OTHER, tokenId)
        ));

        String result = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(view);

        assertThat(result).isNull();
    }

    @Test
    void noLogs_returnsNull() {
        OnChainRawTransactionView view = viewWithLogs(List.of());

        String result = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(view);

        assertThat(result).isNull();
    }

    @Test
    void multipleLogsFirstMatchReturned() {
        String zeroAddress = "0x0000000000000000000000000000000000000000";
        long firstTokenId = 111L;
        long secondTokenId = 222L;
        OnChainRawTransactionView view = viewWithLogs(List.of(
                erc721TransferLog(zeroAddress, WALLET, firstTokenId),
                erc721TransferLog(zeroAddress, WALLET, secondTokenId)
        ));

        String result = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(view);

        assertThat(result).isEqualTo(String.valueOf(firstTokenId));
    }

    @Test
    void largeTokenId_correctDecimalConversion() {
        // Real Uniswap V3 tokenId range: can be > 2^32
        long tokenId = 1_048_576_000_001L;
        String zeroAddress = "0x0000000000000000000000000000000000000000";
        OnChainRawTransactionView view = viewWithLogs(List.of(
                erc721TransferLog(zeroAddress, WALLET, tokenId)
        ));

        String result = LpPositionLifecycleSupport.extractErc721TokenIdForWallet(view);

        assertThat(result).isEqualTo(String.valueOf(tokenId));
    }

    // ─── modifyLiquidities: liquidityDelta-based classification ───────────────

    @Test
    void modifyLiquidities_deltaZero_noTokenLegs_returnsFeeClaim() {
        // liquidityDelta = 0 → pure fee collection
        String zeroDelta = "0000000000000000000000000000000000000000000000000000000000000000";
        NormalizedTransactionType result = classifyModifyLiquidities(
                List.of(modifyLiquidityLog(zeroDelta)), List.of());

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
    }

    @Test
    void modifyLiquidities_deltaZero_withInboundTokenLegs_returnsFeeClaim() {
        // Core fix: even when tokens flow in, delta=0 means LP_FEE_CLAIM (not LP_EXIT)
        // Previously this returned LP_EXIT because inbound legs were checked before logs.
        String zeroDelta = "0000000000000000000000000000000000000000000000000000000000000000";
        NormalizedTransactionType result = classifyModifyLiquidities(
                List.of(modifyLiquidityLog(zeroDelta)),
                List.of(inboundTokenLeg("USDT"), inboundTokenLeg("ETH")));

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
    }

    @Test
    void modifyLiquidities_negativeDelta_withInboundTokenLegs_returnsLpExit() {
        // liquidityDelta < 0 → liquidity removed → LP_EXIT
        String negativeDelta = "ffffffffffffffffffffffffffffffffffffffffffffffffffffcc3fb1ffb8ba";
        NormalizedTransactionType result = classifyModifyLiquidities(
                List.of(modifyLiquidityLog(negativeDelta)),
                List.of(inboundTokenLeg("USDT"), inboundTokenLeg("ETH")));

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    @Test
    void modifyLiquidities_positiveDelta_returnsLpEntry() {
        // liquidityDelta > 0 → liquidity added → LP_ENTRY
        String positiveDelta = "0000000000000000000000000000000000000000000000000000000000000001";
        NormalizedTransactionType result = classifyModifyLiquidities(
                List.of(modifyLiquidityLog(positiveDelta)), List.of());

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_ENTRY);
    }

    @Test
    void modifyLiquidities_ticksNegative_deltaZero_stillReturnsFeeClaim() {
        // Regression: old code read words 0/1 (tickLower/tickUpper, both negative) and returned
        // LP_EXIT. New code reads word 2 (liquidityDelta) and returns LP_FEE_CLAIM when delta=0.
        // The log helper sets tickLower=-212970 and tickUpper=-188296 (both negative) intentionally.
        String zeroDelta = "0000000000000000000000000000000000000000000000000000000000000000";
        NormalizedTransactionType result = classifyModifyLiquidities(
                List.of(modifyLiquidityLog(zeroDelta)),
                List.of(inboundTokenLeg("USDC")));

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_FEE_CLAIM);
    }

    @Test
    void modifyLiquidities_noLog_inboundTokenLegs_fallsBackToLpExit() {
        // When no ModifyLiquidity event is stored (e.g. Etherscan-sourced Unichain tx),
        // fall back to token-leg heuristic: inbound → LP_EXIT.
        NormalizedTransactionType result = classifyModifyLiquidities(
                List.of(),
                List.of(inboundTokenLeg("USDT")));

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_EXIT);
    }

    @Test
    void modifyLiquidities_noLog_outboundTokenLegs_fallsBackToLpEntry() {
        NormalizedTransactionType result = classifyModifyLiquidities(
                List.of(),
                List.of(new RawLeg("0xtoken", "ETH", new BigDecimal("-1.0"), false)));

        assertThat(result).isEqualTo(NormalizedTransactionType.LP_ENTRY);
    }

    @Test
    void modifyLiquidities_noLog_noLegs_returnsNull() {
        NormalizedTransactionType result = classifyModifyLiquidities(List.of(), List.of());

        assertThat(result).isNull();
    }
}
