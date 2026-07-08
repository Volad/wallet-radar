package com.walletradar.application.normalization.pipeline.onchain;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.application.normalization.pipeline.classification.OnChainClassificationResult;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnChainNormalizedTransactionBuilderTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";
    private static final String ERC20_TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String FLUID_POSITION_NFT =
            "0x324c5dc1fc42c7a4d43d92df1eba58a54d13bf2d";

    private final OnChainNormalizedTransactionBuilder builder = new OnChainNormalizedTransactionBuilder();

    @Test
    @DisplayName("build adds repay with aTokens subtype metadata")
    void buildAddsRepayWithATokensSubtypeMetadata() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xceacd8a8229d74366b0dd23b0afa8dcc77dcd11897fc9d416b22b23a21e7ddd3");
        rawTransaction.setNetworkId("BASE");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", "0xa238dd80c259a72e81d7e4664a9801593f98d1c5")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("effectiveGasPrice", "5000000000")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("functionName", "repayWithATokens(address asset, uint256 amount, uint256 interestRateMode)")
                                .append("from", WALLET)
                                .append("to", "0x0000000000000000000000000000000000000000")
                                .append("tokenSymbol", "AWETH")
                                .append("tokenDecimal", "18")
                                .append("value", "100000000000000000")
                ))));

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.REPAY,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                "Aave",
                "V3"
        );

        NormalizedTransaction normalized = builder.build(
                rawTransaction,
                classificationResult,
                Instant.parse("2026-03-22T12:00:00Z")
        );

        assertThat(normalized.getType()).isEqualTo(NormalizedTransactionType.REPAY);
        assertThat(normalized.getEventSubtype()).isEqualTo("REPAY_WITH_ATOKENS");
    }

    @Test
    @DisplayName("build infers repay with aTokens subtype from debt and receipt burn flows")
    void buildInfersRepayWithATokensSubtypeFromFlowShape() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xceacd8a8229d74366b0dd23b0afa8dcc77dcd11897fc9d416b22b23a21e7ddd3");
        rawTransaction.setNetworkId("BASE");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", "0xa238dd80c259a72e81d7e4664a9801593f98d1c5")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("effectiveGasPrice", "5000000000"));
        NormalizedTransaction.Flow debtBurn = flow("variableDebtBasWETH", "-0.015000047618625538");
        NormalizedTransaction.Flow receiptBurn = flow("AWETH", "-0.014999716757892555");

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.REPAY,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                List.of(debtBurn, receiptBurn),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                "Aave",
                "V3"
        );

        NormalizedTransaction normalized = builder.build(
                rawTransaction,
                classificationResult,
                Instant.parse("2026-03-22T12:00:00Z")
        );

        assertThat(normalized.getEventSubtype()).isEqualTo("REPAY_WITH_ATOKENS");
    }

    @Test
    @DisplayName("build adds receipt-safe clarification reasons for pending clarification rows")
    void buildAddsReceiptSafeClarificationReasonsForPendingClarificationRows() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xabc");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "1")
                .append("from", WALLET)
                .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("value", "0")
                .append("methodId", "0x38ed1739")
                .append("input", "0x38ed1739000000000000000000000000"));

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.SWAP,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );

        NormalizedTransaction normalized = builder.build(rawTransaction, classificationResult, Instant.parse("2026-03-22T12:00:00Z"));

        assertThat(normalized.getMissingDataReasons()).containsExactly(
                "MISSING_EXECUTION_STATUS",
                "MISSING_EFFECTIVE_GAS_PRICE",
                "MISSING_GAS_USED"
        );
    }

    @Test
    @DisplayName("build adds missing contract address reason for contract creation clarification rows")
    void buildAddsMissingContractAddressReasonForContractCreationClarificationRows() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xdef");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "2")
                .append("from", WALLET)
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("effectiveGasPrice", "5000000000")
                .append("input", "0x6080604052")
                .append("creates", true));

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.ADMIN_CONFIG,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                ClassificationSource.FUNCTION_NAME,
                ConfidenceLevel.LOW,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );

        NormalizedTransaction normalized = builder.build(rawTransaction, classificationResult, Instant.parse("2026-03-22T12:00:00Z"));

        assertThat(normalized.getMissingDataReasons()).containsExactly("MISSING_CONTRACT_ADDRESS");
    }

    @Test
    @DisplayName("build keeps missing effective gas price reason when only legacy gasPrice exists")
    void buildKeepsMissingEffectiveGasPriceReasonWhenOnlyLegacyGasPriceExists() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xghi");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "3")
                .append("from", WALLET)
                .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("gasPrice", "5000000000")
                .append("input", "0x095ea7b3000000000000000000000000"));

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.APPROVE,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.LOW,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );

        NormalizedTransaction normalized = builder.build(rawTransaction, classificationResult, Instant.parse("2026-03-22T12:00:00Z"));

        assertThat(normalized.getMissingDataReasons()).containsExactly("MISSING_EFFECTIVE_GAS_PRICE");
    }

    @Test
    @DisplayName("build surfaces missing effective gas price for non fee payer clarification rows")
    void buildSurfacesMissingEffectiveGasPriceForNonFeePayerClarificationRows() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xjkl");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "4")
                .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("to", WALLET)
                .append("value", "0")
                .append("input", "0x")
                .append("explorer", new Document("tokenTransfers", List.of(
                        new Document("contractAddress", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                .append("tokenSymbol", "USDC")
                                .append("tokenDecimal", "6")
                                .append("from", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .append("to", WALLET)
                                .append("value", "1000000")
                ))));

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );

        NormalizedTransaction normalized = builder.build(rawTransaction, classificationResult, Instant.parse("2026-03-22T12:00:00Z"));

        assertThat(normalized.getMissingDataReasons()).containsExactly(
                "MISSING_EXECUTION_STATUS",
                "MISSING_EFFECTIVE_GAS_PRICE"
        );
    }

    @Test
    @DisplayName("build infers clarification counters from persisted raw evidence")
    void buildInfersClarificationCountersFromPersistedRawEvidence() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xmno");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setClarificationEvidence(new Document()
                .append("clarificationAttempts", 1)
                .append("fullReceiptClarificationAttempts", 1)
                .append("sourceFamily", "RPC")
                .append("receipt", new Document("txReceiptStatus", "1"))
                .append("fullReceipt", new Document("status", "0x1")));
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "5")
                .append("from", WALLET)
                .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("effectiveGasPrice", "5000000000"));

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.SWAP,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );

        NormalizedTransaction normalized = builder.build(rawTransaction, classificationResult, Instant.parse("2026-03-23T12:00:00Z"));

        assertThat(normalized.getClarificationAttempts()).isEqualTo(1);
        assertThat(normalized.getFullReceiptClarificationAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("build copies correlation metadata from classifier output")
    void buildCopiesCorrelationMetadataFromClassifierOutput() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xcorr");
        rawTransaction.setNetworkId("ARBITRUM");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "7")
                .append("from", WALLET)
                .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("effectiveGasPrice", "5000000000"));

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.LP_ENTRY_REQUEST,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                "gmx:request:1",
                false,
                null,
                true,
                "TEST_EXCLUSION",
                "Test Protocol",
                "v1"
        );

        NormalizedTransaction normalized = builder.build(rawTransaction, classificationResult, Instant.parse("2026-03-26T12:00:00Z"));

        assertThat(normalized.getCorrelationId()).isEqualTo("gmx:request:1");
        assertThat(normalized.getContinuityCandidate()).isFalse();
        assertThat(normalized.getMatchedCounterparty()).isNull();
        assertThat(normalized.getExcludedFromAccounting()).isTrue();
        assertThat(normalized.getAccountingExclusionReason()).isEqualTo("TEST_EXCLUSION");
        assertThat(normalized.getProtocolName()).isEqualTo("Test Protocol");
    }

    @Test
    @DisplayName("rebuild after reclassification preserves clarification counters from raw evidence")
    void rebuildAfterReclassificationPreservesClarificationCountersFromRawEvidence() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xpqr");
        rawTransaction.setNetworkId("ETHEREUM");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setClarificationEvidence(new Document()
                .append("clarificationAttempts", 1)
                .append("fullReceiptClarificationAttempts", 1)
                .append("sourceFamily", "ETHERSCAN")
                .append("receipt", new Document("txReceiptStatus", "1"))
                .append("fullReceipt", new Document("status", "0x1")));
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "6")
                .append("from", WALLET)
                .append("to", "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("effectiveGasPrice", "5000000000"));

        NormalizedTransaction existing = new NormalizedTransaction();
        existing.setId("0xpqr:ETHEREUM:" + WALLET);
        existing.setCreatedAt(Instant.parse("2026-03-23T10:00:00Z"));
        existing.setClarificationAttempts(0);
        existing.setFullReceiptClarificationAttempts(0);

        OnChainClassificationResult classificationResult = new OnChainClassificationResult(
                NormalizedTransactionType.SWAP,
                NormalizedTransactionStatus.PENDING_PRICE,
                ClassificationSource.METHOD_ID,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                false,
                null,
                false,
                null,
                null,
                null
        );

        NormalizedTransaction normalized = builder.rebuildAfterReclassification(
                existing,
                rawTransaction,
                classificationResult,
                Instant.parse("2026-03-23T12:00:00Z")
        );

        assertThat(normalized.getClarificationAttempts()).isEqualTo(1);
        assertThat(normalized.getFullReceiptClarificationAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("enrichFluidEvidence persists evidence after protocol enrichment")
    void enrichFluidEvidencePersistsEvidenceAfterProtocolEnrichment() {
        RawTransaction rawTransaction = new RawTransaction();
        rawTransaction.setTxHash("0xfluid");
        rawTransaction.setNetworkId("PLASMA");
        rawTransaction.setWalletAddress(WALLET);
        rawTransaction.setRawData(new Document()
                .append("timeStamp", "1700000000")
                .append("transactionIndex", "6")
                .append("from", WALLET)
                .append("to", "0x440cf1fe0b00d4d9f43cba534f9428b0facb795c")
                .append("value", "0")
                .append("txreceipt_status", "1")
                .append("gasUsed", "21000")
                .append("effectiveGasPrice", "5000000000"));
        rawTransaction.setClarificationEvidence(new Document()
                .append("clarificationAttempts", 1)
                .append("fullReceipt", new Document("logs", List.of(
                        new Document()
                                .append("address", "0xf2c8f54447cbd591c396b0dd7ac15faf552d0fa4")
                                .append("topics", List.of(
                                        "0x4d93b232a24e82b284ced7461bf4deacffe66759d5c24513e6f29e571ad78d15",
                                        "0x000000000000000000000000" + WALLET.substring(2),
                                        "0x000000000000000000000000b8ce59fc3717ada4c02eadf9682a9e934f625ebb"
                                ))
                                .append("data", "0x" + "0".repeat(384))
                                .append("blockNumber", "0x1")
                                .append("logIndex", "0x0"),
                        new Document()
                                .append("address", "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb")
                                .append("topics", List.of(
                                        ERC20_TRANSFER_TOPIC,
                                        "0x000000000000000000000000" + WALLET.substring(2),
                                        "0x0000000000000000000000002222222222222222222222222222222222222222"
                                ))
                                .append("data", "0x" + "0".repeat(56) + "0f4240")
                                .append("blockNumber", "0x1")
                                .append("logIndex", "0x1"),
                        new Document()
                                .append("address", FLUID_POSITION_NFT)
                                .append("topics", List.of(
                                        ERC20_TRANSFER_TOPIC,
                                        "0x0000000000000000000000000000000000000000000000000000000000000000",
                                        "0x000000000000000000000000" + WALLET.substring(2),
                                        "0x" + "0".repeat(63) + "1"
                                ))
                                .append("data", "0x")
                                .append("blockNumber", "0x1")
                                .append("logIndex", "0x2")
                ))));

        NormalizedTransaction normalized = new NormalizedTransaction();
        normalized.setType(NormalizedTransactionType.LENDING_LOOP_OPEN);
        normalized.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        normalized.setClassifiedBy(ClassificationSource.HEURISTIC);
        normalized.setConfidence(ConfidenceLevel.HIGH);
        normalized.setProtocolName("Fluid");
        normalized.setMatchedCounterparty("0xf2c8f54447cbd591c396b0dd7ac15faf552d0fa4");
        normalized.setFlows(List.of());

        builder.enrichFluidEvidence(normalized, rawTransaction);

        assertThat(normalized.getMetadata()).isNotNull();
        assertThat(normalized.getMetadata().getString("evidenceCompleteness")).isEqualTo("FULL_LOGS_PRESENT");
        assertThat(normalized.getMetadata().getString("vaultAddress"))
                .isEqualTo("0xf2c8f54447cbd591c396b0dd7ac15faf552d0fa4");
        assertThat(normalized.getClarificationEvidence()).isNotNull();
        assertThat(normalized.getClarificationEvidence().getString("source")).isEqualTo("PLASMA_RPC_RECEIPT");
        assertThat(normalized.getClarificationEvidence().getList("erc20TransferLogReferences", Document.class))
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.getString("contract")).isEqualTo("0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb");
                    assertThat(reference.getString("symbol")).isEqualTo("USDT0");
                    assertThat(reference.getString("amountRaw")).isEqualTo("1000000");
                });
        assertThat(normalized.getClarificationEvidence().getList("nftTransfers", Document.class))
                .singleElement()
                .satisfies(reference -> assertThat(reference.getString("tokenId")).isEqualTo("1"));
    }

    private NormalizedTransaction.Flow flow(String symbol, String quantityDelta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        return flow;
    }
}
