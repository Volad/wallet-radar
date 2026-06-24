package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RC-6 (ADR-018): Uniswap V4 LP identity must be keyed by the FULL PositionManager contract +
 * tokenId — never a truncated-contract aggregate. A V4 new-mint via {@code modifyLiquidities} (whose
 * tokenId is assigned on-chain) must defer to receipt clarification rather than land in an aggregate
 * pool. Contracts/tokenIds are regression anchors only.
 */
class LpPositionCorrelationSupportV4IdentityTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String V4_POSITION_MANAGER = "0x4529a01c7a0410167c5740c487a8de60232617bf";
    private static final String MODIFY_LIQUIDITIES = "0xdd46508f";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final long TOKEN_ID = 44472;

    @Test
    @DisplayName("V4 entry (mint log) and V4 exit (modifyLiquidities decrease) share the full-PM+tokenId identity")
    void entryAndExitShareFullPositionManagerIdentity() {
        OnChainRawTransactionView entry = mintLogEntryView();
        OnChainRawTransactionView exit = modifyLiquiditiesDecreaseExitView();

        // self-validate the hand-built V4 decrease calldata resolves the tokenId
        assertThat(LpPositionCorrelationSupport.positionTokenId(exit)).isEqualTo(String.valueOf(TOKEN_ID));

        String entryContract = LpPositionCorrelationSupport.resolvePositionManagerContract(entry);
        String exitContract = LpPositionCorrelationSupport.resolvePositionManagerContract(exit);

        String entryId = LpPositionCorrelationSupport.contractKeyedLifecycleCorrelationId(
                entry, NormalizedTransactionType.LP_ENTRY, entryContract);
        String exitId = LpPositionCorrelationSupport.contractKeyedLifecycleCorrelationId(
                exit, NormalizedTransactionType.LP_EXIT, exitContract);

        assertThat(entryId)
                .isEqualTo(exitId)
                .isEqualTo("lp-position:unichain:" + V4_POSITION_MANAGER + ":" + TOKEN_ID);
    }

    @Test
    @DisplayName("V4 identity is the full lowercase PM contract — never the truncated-aggregate slug form")
    void identityIsNotTruncatedAggregate() {
        OnChainRawTransactionView exit = modifyLiquiditiesDecreaseExitView();
        String contract = LpPositionCorrelationSupport.resolvePositionManagerContract(exit);

        String id = LpPositionCorrelationSupport.contractKeyedLifecycleCorrelationId(
                exit, NormalizedTransactionType.LP_EXIT, contract);

        assertThat(id).contains(V4_POSITION_MANAGER);
        assertThat(id).doesNotContain("uniswap");
        // the eliminated aggregate keyed by the first 16 hex chars of the contract (no tokenId)
        assertThat(id).doesNotContain("uniswap:4529a01c7a041016");
    }

    @Test
    @DisplayName("V4 new-mint modifyLiquidities (tokenId assigned on-chain) defers to receipt clarification — no aggregate")
    void v4NewMintDefersToReceiptClarification() {
        OnChainRawTransactionView mint = modifyLiquiditiesMintView();

        assertThat(LpPositionCorrelationSupport.requiresReceiptClarification(mint, NormalizedTransactionType.LP_ENTRY))
                .isTrue();
        assertThat(LpPositionCorrelationSupport.positionTokenId(mint)).isNull();
        // no tokenId yet → no fabricated identity (stays PENDING_CLARIFICATION until the log resolves it)
        assertThat(LpPositionCorrelationSupport.contractKeyedLifecycleCorrelationId(
                mint, NormalizedTransactionType.LP_ENTRY, V4_POSITION_MANAGER)).isNull();
    }

    private static OnChainRawTransactionView mintLogEntryView() {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("to", V4_POSITION_MANAGER);
        raw.getRawData().put("methodId", MODIFY_LIQUIDITIES);
        raw.getRawData().put("logs", List.of(
                new Document("address", V4_POSITION_MANAGER)
                        .append("topics", List.of(
                                ERC721_TRANSFER_TOPIC,
                                zeroTopic(),
                                addressTopic(WALLET),
                                uintTopic(TOKEN_ID)
                        ))
                        .append("data", "0x")
        ));
        return OnChainRawTransactionView.wrap(raw);
    }

    private static OnChainRawTransactionView modifyLiquiditiesDecreaseExitView() {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("to", V4_POSITION_MANAGER);
        raw.getRawData().put("methodId", MODIFY_LIQUIDITIES);
        raw.getRawData().put("input", modifyLiquiditiesDecreaseInput(TOKEN_ID));
        return OnChainRawTransactionView.wrap(raw);
    }

    private static OnChainRawTransactionView modifyLiquiditiesMintView() {
        RawTransaction raw = baseRaw();
        raw.getRawData().put("to", V4_POSITION_MANAGER);
        raw.getRawData().put("methodId", MODIFY_LIQUIDITIES);
        raw.getRawData().put("input", modifyLiquiditiesMintInput());
        return OnChainRawTransactionView.wrap(raw);
    }

    /**
     * modifyLiquidities(bytes unlockData, uint256 deadline) where unlockData = abi.encode(bytes
     * actions, bytes[] params); actions = single DECREASE_LIQUIDITY (0x01); params[0] = abi-encoded
     * tokenId. Mirrors the on-chain shape so {@code decodeModifyLiquiditiesTokenId} resolves it.
     */
    private static String modifyLiquiditiesDecreaseInput(long tokenId) {
        return "0xdd46508f"
                + word(0x40)            // offset to unlockData
                + word(0)               // deadline
                + word(0x100)           // unlockData length = 256 bytes
                // ---- unlockData content ----
                + word(0x40)            // actions offset
                + word(0x80)            // params offset
                + word(0x01)            // actions length = 1
                + actionByte(0x01)      // actions data: DECREASE_LIQUIDITY
                + word(0x01)            // params array length = 1
                + word(0x20)            // params[0] offset (relative to array data start)
                + word(0x20)            // params[0] bytes length = 32
                + word(tokenId);        // params[0] data = tokenId
    }

    /**
     * V4 new-mint: actions = single MINT_POSITION (0x02), empty params. The tokenId is assigned
     * on-chain (absent from calldata) so this must route to receipt clarification.
     */
    private static String modifyLiquiditiesMintInput() {
        return "0xdd46508f"
                + word(0x40)            // offset to unlockData
                + word(0)               // deadline
                + word(0xa0)            // unlockData length = 160 bytes
                // ---- unlockData content ----
                + word(0x40)            // actions offset
                + word(0x80)            // params offset
                + word(0x01)            // actions length = 1
                + actionByte(0x02)      // actions data: MINT_POSITION
                + word(0x00);           // params array length = 0
    }

    private static RawTransaction baseRaw() {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.UNICHAIN.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("value", "0"));
        return raw;
    }

    private static String word(long value) {
        String hex = Long.toHexString(value);
        return "0".repeat(64 - hex.length()) + hex;
    }

    private static String actionByte(int action) {
        String hex = Integer.toHexString(action);
        if (hex.length() == 1) {
            hex = "0" + hex;
        }
        return hex + "0".repeat(64 - hex.length());
    }

    private static String zeroTopic() {
        return "0x" + "0".repeat(64);
    }

    private static String addressTopic(String address) {
        return "0x" + "0".repeat(24) + address.substring(2).toLowerCase();
    }

    private static String uintTopic(long value) {
        return "0x" + word(value);
    }
}
