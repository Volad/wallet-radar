package com.walletradar.application.pricing.application;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricingResultMapperTest {

    @Test
    void copyPreservesClarificationEvidenceDocuments() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("tx-1");
        transaction.setTxHash("0xabc");
        transaction.setNetworkId(NetworkId.PLASMA);
        transaction.setWalletAddress("0xwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.LENDING_DEPOSIT);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-04-22T10:00:00Z"));
        Document metadata = new Document("evidenceCompleteness", "FULL_LOGS_PRESENT")
                .append("vaultAddress", "0xvault");
        Document clarificationEvidence = new Document("source", "full-receipt")
                .append("fluidLogOperate", new Document("withdraw", "1"));
        transaction.setMetadata(metadata);
        transaction.setClarificationEvidence(clarificationEvidence);
        transaction.setMissingDataReasons(List.of());
        transaction.setFlows(List.of(flow()));

        NormalizedTransaction copy = new PricingResultMapper().copy(transaction);

        assertThat(copy.getMetadata()).isEqualTo(metadata);
        assertThat(copy.getMetadata()).isNotSameAs(metadata);
        assertThat(copy.getClarificationEvidence()).isEqualTo(clarificationEvidence);
        assertThat(copy.getClarificationEvidence()).isNotSameAs(clarificationEvidence);
    }

    @Test
    void copyPreservesWs8CapabilityFlags() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("sol-lp-1");
        transaction.setTxHash("sig");
        transaction.setNetworkId(NetworkId.SOLANA);
        transaction.setWalletAddress("SoLwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.LP_ENTRY);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-04-22T10:00:00Z"));
        transaction.setCorrelationId("lp-position:solana:raydium-clmm:pool");
        transaction.setMissingDataReasons(List.of());
        transaction.setFlows(List.of(flow()));
        transaction.setReceiptBearingCollateral(false);
        transaction.setLpConcentrated(true);

        NormalizedTransaction copy = new PricingResultMapper().copy(transaction);

        assertThat(copy.getReceiptBearingCollateral())
                .as("receiptBearingCollateral must survive the pricing copy-and-replace cycle")
                .isFalse();
        assertThat(copy.getLpConcentrated())
                .as("lpConcentrated must survive the pricing copy-and-replace cycle")
                .isTrue();
    }

    @Test
    void copyPreservesLpReceiptFlowFlag() {
        // ADR-081 (C1): the flow-level lpReceipt flag (Meteora DAMM MLP receipt) must survive the
        // per-flow pricing copy-and-replace, else the confusable MLP loses FAMILY:LP_RECEIPT on
        // priced rows.
        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setId("sol-damm-1");
        transaction.setTxHash("sig");
        transaction.setNetworkId(NetworkId.SOLANA);
        transaction.setWalletAddress("SoLwallet");
        transaction.setSource(NormalizedTransactionSource.ON_CHAIN);
        transaction.setType(NormalizedTransactionType.LP_ENTRY);
        transaction.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        transaction.setBlockTimestamp(Instant.parse("2026-04-22T10:00:00Z"));
        transaction.setCorrelationId("lp-position:solana:meteora-damm:pool:SoLwallet");
        transaction.setMissingDataReasons(List.of());
        NormalizedTransaction.Flow mlp = flow();
        mlp.setAssetSymbol("MLP");
        mlp.setAssetContract("6fymg7doag2taxdmp7nhnvhbkqxsorodzmdxnrmzwftf");
        mlp.setLpReceipt(Boolean.TRUE);
        transaction.setFlows(List.of(mlp));

        NormalizedTransaction copy = new PricingResultMapper().copy(transaction);

        assertThat(copy.getFlows().getFirst().getLpReceipt())
                .as("lpReceipt must survive the pricing copy-and-replace cycle")
                .isTrue();
    }

    private NormalizedTransaction.Flow flow() {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal("1"));
        return flow;
    }
}
