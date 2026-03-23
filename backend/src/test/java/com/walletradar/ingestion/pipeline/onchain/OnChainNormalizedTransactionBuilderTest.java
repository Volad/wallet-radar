package com.walletradar.ingestion.pipeline.onchain;

import com.walletradar.domain.common.ConfidenceLevel;
import com.walletradar.domain.transaction.normalized.ClassificationSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import com.walletradar.ingestion.pipeline.classification.OnChainClassificationResult;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnChainNormalizedTransactionBuilderTest {

    private static final String WALLET = "0x1111111111111111111111111111111111111111";

    private final OnChainNormalizedTransactionBuilder builder = new OnChainNormalizedTransactionBuilder();

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
                NormalizedTransactionType.EXTERNAL_INBOUND,
                NormalizedTransactionStatus.PENDING_CLARIFICATION,
                ClassificationSource.HEURISTIC,
                ConfidenceLevel.MEDIUM,
                List.of(),
                List.of(),
                null,
                null
        );

        NormalizedTransaction normalized = builder.build(rawTransaction, classificationResult, Instant.parse("2026-03-22T12:00:00Z"));

        assertThat(normalized.getMissingDataReasons()).containsExactly(
                "MISSING_EXECUTION_STATUS",
                "MISSING_EFFECTIVE_GAS_PRICE"
        );
    }
}
