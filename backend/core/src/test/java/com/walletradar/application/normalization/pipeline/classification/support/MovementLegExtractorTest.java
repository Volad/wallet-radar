package com.walletradar.application.normalization.pipeline.classification.support;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.testsupport.NetworkTestFixtures;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.onchain.OnChainRawTransactionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression: {@code Set.of(...).contains(null)} throws NPE on JDK 21 (ImmutableCollections).
 * Also covers Euler V2 sub-account phantom double-tracking (B3 EUSDC-2 shortfall fix).
 */
class MovementLegExtractorTest {

    private static final String WALLET = "0xf03b52e8686b962e051a6075a06b96cb8a663021";
    // Euler sub-account: same first 40 hex chars, last byte = 0xe (XOR with 0x0f ^ 0x01 = 0x0e)
    private static final String SUBACCOUNT = "0xf03b52e8686b962e051a6075a06b96cb8a66302e";
    private static final String OTHER = "0x2222222222222222222222222222222222222222";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";
    private static final String EULER_EVC_AVALANCHE = "0xddcbe30a761edd2e19bba930a977475265f36fa1";

    private final MovementLegExtractor extractor = new MovementLegExtractor(new NativeAssetSymbolResolver(NetworkTestFixtures.registry()));

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

    /**
     * Euler V2 sub-account → main wallet eUSDC-2 transfer must produce a single CREDIT leg only.
     * The sub-account is an Euler-controlled account (same address prefix, last byte differs).
     * Previously both CREDIT and DEBIT were generated (phantom double-track), causing a 1341.8
     * eUSDC-2 shortfall on the subsequent LENDING_LOOP_DECREASE.
     */
    @Test
    void eulerSubaccountToWalletTransferGeneratesCreditOnly() {
        RawTransaction raw = new RawTransaction();
        raw.setNetworkId(NetworkId.AVALANCHE.name());
        raw.setWalletAddress(WALLET);
        raw.setRawData(new Document()
                .append("hash", "0x08e6af7e")
                .append("blockNumber", "100")
                .append("timeStamp", "1724209956")
                .append("transactionIndex", "1")
                .append("methodId", "0xc16ae7a4")
                .append("functionName", "batch(tuple[] items)")
                .append("from", WALLET)
                .append("to", EULER_EVC_AVALANCHE)
                .append("explorer", new Document("tokenTransfers", List.of(
                        // sub-account → main wallet residual collateral release
                        new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("tokenSymbol", "eUSDC-2")
                                .append("tokenDecimal", "6")
                                .append("from", SUBACCOUNT)
                                .append("to", WALLET)
                                .append("value", "1341798065"),
                        // sub-account → address(0) vault burn (loop unwind, sub-account level only)
                        new Document("contractAddress", "0x39de0f00189306062d79edec6dca5bb6bfd108f9")
                                .append("tokenSymbol", "eUSDC-2")
                                .append("tokenDecimal", "6")
                                .append("from", SUBACCOUNT)
                                .append("to", ZERO)
                                .append("value", "4478990127")
                )).append("internalTransfers", List.of())));

        OnChainRawTransactionView view = OnChainRawTransactionView.wrap(raw);
        List<RawLeg> legs = extractor.extract(view);

        // Should have exactly ONE positive eUSDC-2 leg (+1341.798065) and ZERO negative legs.
        // The sub-account burn (-4478.990127) must be excluded: it's a sub-account-level event
        // that does not represent a main-wallet disposal.
        List<RawLeg> eusdcLegs = legs.stream()
                .filter(l -> "eUSDC-2".equalsIgnoreCase(l.assetSymbol()))
                .toList();
        assertThat(eusdcLegs).hasSize(1);
        assertThat(eusdcLegs.getFirst().quantityDelta()).isPositive();
    }
}
