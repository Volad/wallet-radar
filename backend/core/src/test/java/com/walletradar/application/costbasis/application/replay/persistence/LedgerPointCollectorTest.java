package com.walletradar.application.costbasis.application.replay.persistence;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap 1 (ADR-081 C1): the durable LP-receipt flag stamps the persisted ledger-point family so a
 * confusable fungible receipt symbol (Meteora DAMM {@code MLP}, three pool mints share the symbol)
 * carries {@code FAMILY:LP_RECEIPT} rather than its raw pool-mint contract family.
 */
class LedgerPointCollectorTest {

    private static final String MLP_MINT = "6fymg7doag2taxdmp7nhnvhbkqxsorodzmdxnrmzwftf";

    @Test
    void stampsLpReceiptFamilyForFlaggedMlpReceiptLegDespiteConfusablePoolMintSymbol() {
        List<AssetLedgerPoint> points = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("universe-1", points, Instant.now());

        AssetKey mlpKey = new AssetKey("9GrpWallet", NetworkId.SOLANA, MLP_MINT, "MLP", MLP_MINT);
        collector.record(
                dammEntry(flaggedReceiptFlow()),
                flaggedReceiptFlow(),
                0,
                mlpKey,
                zeroSnapshot(),
                positionWithQuantity(mlpKey, new BigDecimal("0.3096")),
                AssetLedgerPoint.BasisEffect.ACQUIRE
        );

        assertThat(points).hasSize(1);
        assertThat(points.getFirst().getAccountingFamilyIdentity()).isEqualTo("FAMILY:LP_RECEIPT");
        // The confusable symbol is preserved on the point; only the family is driven by the flag.
        assertThat(points.getFirst().getAssetSymbol()).isEqualTo("MLP");
    }

    @Test
    void keepsRawMintFamilyForUnflaggedConfusableSplHoldingSuchAsSse() {
        List<AssetLedgerPoint> points = new ArrayList<>();
        LedgerPointCollector collector = new LedgerPointCollector("universe-1", points, Instant.now());

        String sseMint = "H4phNbsqjV5rqk8u6FUACTLB6rNZRTAPGnBb8KXJpump";
        AssetKey sseKey = new AssetKey("9GrpWallet", NetworkId.SOLANA, sseMint, "SSE", sseMint);
        NormalizedTransaction.Flow spotFlow = new NormalizedTransaction.Flow();
        spotFlow.setRole(NormalizedLegRole.BUY);
        spotFlow.setAssetSymbol("SSE");
        spotFlow.setAssetContract(sseMint);
        spotFlow.setQuantityDelta(new BigDecimal("100"));

        collector.record(
                dammEntry(spotFlow),
                spotFlow,
                0,
                sseKey,
                zeroSnapshot(),
                positionWithQuantity(sseKey, new BigDecimal("100")),
                AssetLedgerPoint.BasisEffect.ACQUIRE
        );

        assertThat(points).hasSize(1);
        // A genuine (unflagged) SPL holding keeps its own contract-keyed family — untouched
        // (continuity normalizes the contract to lower case), never FAMILY:LP_RECEIPT.
        assertThat(points.getFirst().getAccountingFamilyIdentity())
                .isEqualTo(sseMint.toLowerCase(java.util.Locale.ROOT));
    }

    private static NormalizedTransaction.Flow flaggedReceiptFlow() {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("MLP");
        flow.setAssetContract(MLP_MINT);
        flow.setQuantityDelta(new BigDecimal("0.3096"));
        flow.setLpReceipt(Boolean.TRUE);
        return flow;
    }

    private static NormalizedTransaction dammEntry(NormalizedTransaction.Flow flow) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-1");
        tx.setTxHash("sig-1");
        tx.setNetworkId(NetworkId.SOLANA);
        tx.setType(NormalizedTransactionType.LP_ENTRY);
        tx.setCorrelationId("lp-position:solana:meteora-damm:pool:9GrpWallet");
        tx.setBlockTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        tx.getFlows().add(flow);
        return tx;
    }

    private static PositionSnapshot zeroSnapshot() {
        return PositionSnapshot.mirrorTax(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0);
    }

    private static PositionState positionWithQuantity(AssetKey key, BigDecimal quantity) {
        PositionState state = new PositionState(key);
        state.setQuantity(quantity);
        return state;
    }
}
