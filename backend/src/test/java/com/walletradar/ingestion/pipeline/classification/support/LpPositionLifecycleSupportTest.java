package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LpPositionLifecycleSupport#extractErc721TokenIdForWallet}.
 */
class LpPositionLifecycleSupportTest {

    private static final String WALLET = "0xaabbccdd00000000000000000000000000000001";
    private static final String OTHER = "0x1234000000000000000000000000000000000001";
    private static final String CONTRACT = "0x03a520b32c04bf3beef7beb72e919cf822ed34f1";
    private static final String ERC721_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

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
}
