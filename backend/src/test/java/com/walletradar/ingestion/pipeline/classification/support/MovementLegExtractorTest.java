package com.walletradar.ingestion.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression: {@code Set.of(...).contains(null)} throws NPE on JDK 21 (ImmutableCollections).
 */
class MovementLegExtractorTest {

    private static final String WALLET = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
    private static final String OTHER = "0x2222222222222222222222222222222222222222";

    private final MovementLegExtractor extractor = new MovementLegExtractor(new NativeAssetSymbolResolver());

    @Test
    void eulerBatchTransferRowWithoutTopLevelToDoesNotThrowOnLegExtraction() {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.ARBITRUM.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("hash", "0x5efe9502badef40a00fbe4d0e6214cebd2b64d0abf34121bc09b2f7954c9d8a9")
                .append("blockNumber", "430698325")
                .append("timeStamp", "1770746177")
                .append("transactionIndex", "12")
                .append("methodId", "0xc16ae7a4")
                .append("functionName", "batch(tuple[] items)")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xaf88d065e77c8cc2239327c5edb3a432268e5831")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", OTHER)
                                .append("to", "0xe4783824593a50bfe9dc873204cec171ebc62de0")
                                .append("value", "50003098")
                )).append("internalTransfers", List.of())));

        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);

        assertThatCode(() -> extractor.extract(view)).doesNotThrowAnyException();
    }
}
