package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-1 (ADR-018): CL-NFT position identity must be a pure function of the NonfungiblePositionManager
 * contract, never the protocol slug, so a position's LP_ENTRY and LP_EXIT can never split into two
 * receipt pools.
 */
class LpPositionCorrelationSupportContractIdentityTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String PANCAKE_NFPM = "0x46a15b0b27311cedf172ab29e4f4766fbe7f4364";
    private static final String UNISWAP_BASE_NFPM = "0x03a520b32c04bf3beef7beb72e919cf822ed34f1";
    private static final String AGGREGATOR_ROUTER = "0x3067bdba0e6628497d527bef511c22da8b32ca3f";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    @Test
    @DisplayName("entry (mint to NFPM) and exit (decrease on same NFPM) resolve the identical contract-keyed identity")
    void entryAndExitShareContractKeyedIdentity() {
        OnChainRawTransactionView entry = mintEntryView(NetworkId.ARBITRUM, PANCAKE_NFPM, PANCAKE_NFPM, 196975);
        OnChainRawTransactionView exit = decreaseExitView(NetworkId.ARBITRUM, PANCAKE_NFPM, 196975);

        String entryId = LpPositionCorrelationSupport.lifecycleCorrelationId(
                entry, NormalizedTransactionType.LP_ENTRY, "Uniswap");
        String exitId = LpPositionCorrelationSupport.lifecycleCorrelationId(
                exit, NormalizedTransactionType.LP_EXIT, "PancakeSwap");

        assertThat(entryId)
                .isEqualTo("lp-position:arbitrum:" + PANCAKE_NFPM + ":196975")
                .isEqualTo(exitId)
                .doesNotContain("uniswap")
                .doesNotContain("pancakeswap");
    }

    @Test
    @DisplayName("identity does not depend on the protocol slug argument (display-only)")
    void identityIgnoresProtocolSlugArgument() {
        OnChainRawTransactionView exit = decreaseExitView(NetworkId.ARBITRUM, PANCAKE_NFPM, 196975);

        String asUniswap = LpPositionCorrelationSupport.lifecycleCorrelationId(
                exit, NormalizedTransactionType.LP_EXIT, "Uniswap");
        String asPancake = LpPositionCorrelationSupport.lifecycleCorrelationId(
                exit, NormalizedTransactionType.LP_EXIT, "PancakeSwap");
        String asNull = LpPositionCorrelationSupport.lifecycleCorrelationId(
                exit, NormalizedTransactionType.LP_EXIT, null);

        assertThat(asUniswap).isEqualTo(asPancake).isEqualTo(asNull);
    }

    @Test
    @DisplayName("same tokenId on different NFPM contracts yields distinct identities (no collision)")
    void sameTokenIdDifferentContractsDoNotCollide() {
        OnChainRawTransactionView pancake = decreaseExitView(NetworkId.ARBITRUM, PANCAKE_NFPM, 938761);
        OnChainRawTransactionView uniswap = decreaseExitView(NetworkId.ARBITRUM, UNISWAP_BASE_NFPM, 938761);

        String pancakeId = LpPositionCorrelationSupport.lifecycleCorrelationId(
                pancake, NormalizedTransactionType.LP_EXIT, "PancakeSwap");
        String uniswapId = LpPositionCorrelationSupport.lifecycleCorrelationId(
                uniswap, NormalizedTransactionType.LP_EXIT, "Uniswap");

        assertThat(pancakeId).isNotEqualTo(uniswapId);
        assertThat(pancakeId).isEqualTo("lp-position:arbitrum:" + PANCAKE_NFPM + ":938761");
        assertThat(uniswapId).isEqualTo("lp-position:arbitrum:" + UNISWAP_BASE_NFPM + ":938761");
    }

    @Test
    @DisplayName("router-wrapped entry resolves the NFPM from the position-NFT ERC-721 log, not rawData.to")
    void routerWrappedEntryResolvesNfpmFromLog() {
        OnChainRawTransactionView routerEntry = mintEntryView(NetworkId.BASE, AGGREGATOR_ROUTER, PANCAKE_NFPM, 555);

        String contract = LpPositionCorrelationSupport.resolvePositionManagerContract(routerEntry);

        assertThat(contract).isEqualTo(PANCAKE_NFPM);
    }

    @Test
    @DisplayName("falls back to rawData.to when no mint/burn log is present")
    void fallsBackToRawDataTo() {
        OnChainRawTransactionView exit = decreaseExitView(NetworkId.BASE, UNISWAP_BASE_NFPM, 5248110);

        String contract = LpPositionCorrelationSupport.resolvePositionManagerContract(exit);

        assertThat(contract).isEqualTo(UNISWAP_BASE_NFPM);
    }

    @Test
    @DisplayName("identity resolution is idempotent for the same view")
    void identityIsIdempotent() {
        OnChainRawTransactionView exit = decreaseExitView(NetworkId.ARBITRUM, PANCAKE_NFPM, 196975);

        String first = LpPositionCorrelationSupport.contractKeyedCorrelationId(exit, "196975");
        String second = LpPositionCorrelationSupport.contractKeyedCorrelationId(exit, "196975");

        assertThat(first).isEqualTo(second).isEqualTo("lp-position:arbitrum:" + PANCAKE_NFPM + ":196975");
    }

    private static OnChainRawTransactionView mintEntryView(
            NetworkId networkId, String to, String nfpm, long tokenId) {
        RawTransaction raw = baseRaw(networkId);
        raw.getRawData().put("to", to);
        raw.getRawData().put("methodId", "0x88316456");
        raw.getRawData().put("logs", List.of(
                new Document("address", nfpm)
                        .append("topics", List.of(
                                ERC721_TRANSFER_TOPIC,
                                zeroTopic(),
                                addressTopic(WALLET),
                                uintTopic(tokenId)
                        ))
                        .append("data", "0x")
        ));
        return OnChainRawTransactionView.wrap(raw);
    }

    private static OnChainRawTransactionView decreaseExitView(NetworkId networkId, String nfpm, long tokenId) {
        RawTransaction raw = baseRaw(networkId);
        raw.getRawData().put("to", nfpm);
        raw.getRawData().put("methodId", "0x0c49ccbe");
        raw.getRawData().put("input", "0x0c49ccbe" + uint256Hex(tokenId));
        return OnChainRawTransactionView.wrap(raw);
    }

    private static RawTransaction baseRaw(NetworkId networkId) {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(networkId.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("value", "0"));
        return raw;
    }

    private static String zeroTopic() {
        return "0x" + "0".repeat(64);
    }

    private static String addressTopic(String address) {
        return "0x" + "0".repeat(24) + address.substring(2).toLowerCase();
    }

    private static String uintTopic(long value) {
        return "0x" + uint256Hex(value);
    }

    private static String uint256Hex(long value) {
        String hex = Long.toHexString(value);
        return "0".repeat(64 - hex.length()) + hex;
    }
}
