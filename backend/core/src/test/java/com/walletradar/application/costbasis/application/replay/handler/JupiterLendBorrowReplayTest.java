package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.application.costbasis.domain.BorrowLiability;
import com.walletradar.application.costbasis.domain.BorrowLiabilityRepository;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.BorrowLiabilityReplayContext;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.normalization.pipeline.solana.SolanaNormalizedTransactionBuilder;
import com.walletradar.application.normalization.pipeline.solana.SolanaProgramIds;
import com.walletradar.application.normalization.pipeline.solana.SolanaTransactionClassifier;
import com.walletradar.application.session.application.AccountingUniverseService;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.transaction.raw.RawTransaction;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE 1 — a Jupiter Lend BORROW on SOLANA creates a SOLANA {@code borrow_liabilities} row (asset
 * USDT, OPEN) via the shared EVM borrow-liability machinery, and a later REPAY nets against it and
 * closes it. Network is encoded in the deterministic order id ({@code solana:jupiter-lend:<mint>:<wallet>})
 * since {@link BorrowLiability} has no explicit network column.
 */
class JupiterLendBorrowReplayTest {

    private static final String WALLET = "9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG";
    private static final String RESERVE = "7s1da8DduuBFqGra5bJBjpnvL5E9mGzCuMk1Qkh4or2Z";
    private static final String UNIVERSE = "df5e69cc-a0c0-4910-8b7d-74488fa266e2";
    private static final BigDecimal BORROW_QTY = new BigDecimal("210");

    private final SolanaNormalizedTransactionBuilder builder = new SolanaNormalizedTransactionBuilder(
            new SolanaTransactionClassifier(),
            Mockito.mock(AccountingUniverseService.class)
    );

    @Test
    @DisplayName("Jupiter Lend borrow creates a SOLANA USDT OPEN liability; matching repay closes it")
    void borrowCreatesSolanaLiabilityAndRepayClosesIt() {
        BorrowLiabilityRepository repository = Mockito.mock(BorrowLiabilityRepository.class);
        BorrowLiabilityTracker tracker = new BorrowLiabilityTracker(repository);
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(new GenericFlowReplayEngine(null));

        BorrowReplayHandler borrowHandler = new BorrowReplayHandler(tracker, assetSupport, flowSupport, null);
        RepayReplayHandler repayHandler = new RepayReplayHandler(tracker, assetSupport, flowSupport);

        Map<String, BorrowLiability> book = new LinkedHashMap<>();
        BorrowLiabilityReplayContext borrowContext =
                new BorrowLiabilityReplayContext(UNIVERSE, book, new HashSet<>());
        ReplayExecutionState state = new ReplayExecutionState(
                null,
                new LedgerPointCollector(UNIVERSE, new ArrayList<>(), Instant.now()),
                null,
                borrowContext
        );

        // --- BORROW: +210 USDT from the Jupiter Lend liquidity account ---
        NormalizedTransaction borrow = builder.build(
                raw("borrowSig", borrowParsed()), Instant.parse("2025-09-22T07:08:09Z"));
        assertThat(borrow.getType()).isEqualTo(NormalizedTransactionType.BORROW);
        String expectedOrderId = "solana:jupiter-lend:" + SolanaProgramIds.USDT_MINT + ":" + WALLET;
        assertThat(borrow.getCorrelationId()).isEqualTo(expectedOrderId);

        NormalizedTransaction.Flow borrowFlow = borrow.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.BUY).findFirst().orElseThrow();
        borrowFlow.setUnitPriceUsd(BigDecimal.ONE);
        borrowFlow.setPriceSource(PriceSource.STABLECOIN);

        borrowHandler.apply(borrow, borrowFlow, 0, state);

        assertThat(book).hasSize(1);
        BorrowLiability liability = book.get(BorrowLiability.compositeId(UNIVERSE, expectedOrderId));
        assertThat(liability).isNotNull();
        assertThat(liability.getAsset()).isEqualTo("USDT");
        assertThat(liability.getAccountRef()).isEqualTo(WALLET);
        assertThat(liability.getQtyOpen()).isEqualByComparingTo(BORROW_QTY);
        assertThat(liability.getStatus()).isEqualTo("OPEN");

        // --- REPAY: -210 USDT back to the Jupiter Lend liquidity account closes the liability ---
        NormalizedTransaction repay = builder.build(
                raw("repaySig", repayParsed()), Instant.parse("2025-10-01T00:00:00Z"));
        assertThat(repay.getType()).isEqualTo(NormalizedTransactionType.REPAY);
        assertThat(repay.getCorrelationId()).isEqualTo(expectedOrderId);

        NormalizedTransaction.Flow repayFlow = repay.getFlows().stream()
                .filter(f -> f.getRole() == NormalizedLegRole.SELL).findFirst().orElseThrow();
        repayFlow.setUnitPriceUsd(BigDecimal.ONE);
        repayFlow.setPriceSource(PriceSource.STABLECOIN);

        repayHandler.apply(repay, repayFlow, 0, state);

        assertThat(liability.getQtyOpen()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(liability.getStatus()).isEqualTo("CLOSED");
    }

    private static Document borrowParsed() {
        return new Document("type", "UNKNOWN")
                .append("fee", 5_000L)
                .append("timestamp", 1_758_524_889L)
                .append("instructions", List.of(new Document("programId", SolanaProgramIds.JUPITER_LEND)))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", RESERVE)
                        .append("toUserAccount", WALLET)
                        .append("mint", SolanaProgramIds.USDT_MINT)
                        .append("symbol", "USDT")
                        .append("tokenAmount", 210.0)));
    }

    private static Document repayParsed() {
        return new Document("type", "UNKNOWN")
                .append("fee", 5_000L)
                .append("timestamp", 1_759_276_800L)
                .append("instructions", List.of(new Document("programId", SolanaProgramIds.JUPITER_LEND)))
                .append("tokenTransfers", List.of(new Document("fromUserAccount", WALLET)
                        .append("toUserAccount", RESERVE)
                        .append("mint", SolanaProgramIds.USDT_MINT)
                        .append("symbol", "USDT")
                        .append("tokenAmount", 210.0)));
    }

    private static RawTransaction raw(String signature, Document heliusParsed) {
        RawTransaction r = new RawTransaction();
        r.setId(signature + ":SOLANA:" + WALLET);
        r.setTxHash(signature);
        r.setWalletAddress(WALLET);
        r.setNetworkId("SOLANA");
        r.setRawData(new Document("source", "HELIUS_ENHANCED").append("heliusParsed", heliusParsed));
        return r;
    }
}
