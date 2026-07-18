package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.domain.transaction.externalledger.ExternalLedgerRaw;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-058 A2/D5/E — the anti-Phase-1 invariant. Bot transfer legs MUST stay on the non-guarded
 * standalone path so {@code CorridorBasisConservationGuard} can never fire on them. Phase 1 aborted
 * replay by routing to-bot USDT through a guarded continuity carry; these tests lock the guarantee
 * that Phase 2 never re-introduces that regression. Evidence anchor: member {@code 516601508}.
 */
class BybitBotTransferAntiPhase1Test {

    private static final String UID = "516601508";

    private final BybitCanonicalFlowCounterpartySupport support =
            new BybitCanonicalFlowCounterpartySupport(null);
    private final ReplayPendingTransferKeyFactory keyFactory =
            new ReplayPendingTransferKeyFactory(new ReplayAssetSupport());

    @Test
    void reclassifyBotTransfer_keepsLegsOnNonGuardedStandalonePath() {
        NormalizedTransaction tx = transferTransaction("ETH", "0.01374624");
        ExternalLedgerRaw row = botRow("ETH", "0.01374624");

        support.reclassifyBotTransfer(tx, row, Instant.parse("2025-10-21T11:59:00Z"));

        // Anti-Phase-1: never a correlationId / continuityCandidate on a bot leg.
        assertThat(tx.getCorrelationId()).isNull();
        assertThat(tx.getContinuityCandidate()).isFalse();
        assertThat(tx.getMatchedCounterparty()).isNull();

        // Re-typed to the standalone external-transfer path with the :BOT compartment accountRef.
        assertThat(tx.getType()).isEqualTo(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        assertThat(tx.getFlows().get(0).getRole()).isEqualTo(NormalizedLegRole.BUY);
        assertThat(tx.getFlows().get(0).getAccountRef()).isEqualTo("BYBIT:" + UID + ":BOT");

        // Markers: BOT_TRANSFER + a pending-cost flag for the non-stable return the resolver clears.
        assertThat(tx.getMissingDataReasons()).contains("BOT_TRANSFER", "BOT_TRANSFER_PENDING_COST");
    }

    @Test
    void reclassifyBotTransfer_stableReturn_hasNoPendingCost() {
        NormalizedTransaction tx = transferTransaction("USDT", "1.2649693");
        ExternalLedgerRaw row = botRow("USDT", "1.2649693");

        support.reclassifyBotTransfer(tx, row, Instant.parse("2025-10-18T14:04:24Z"));

        assertThat(tx.getMissingDataReasons()).contains("BOT_TRANSFER");
        assertThat(tx.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST");
        assertThat(tx.getContinuityCandidate()).isFalse();
        assertThat(tx.getCorrelationId()).isNull();
    }

    @Test
    void botLeg_neverProducesGuardedQueueKey() {
        for (NormalizedTransaction tx : List.of(
                normalizedBotLeg(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, NormalizedLegRole.BUY, "ETH", "0.01374624"),
                normalizedBotLeg(NormalizedTransactionType.EXTERNAL_TRANSFER_IN, NormalizedLegRole.BUY, "BTC", "0.001286712"),
                normalizedBotLeg(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT, NormalizedLegRole.SELL, "USDT", "-185")
        )) {
            NormalizedTransaction.Flow flow = tx.getFlows().get(0);
            assertThat(keyFactory.transferKey(tx, flow))
                    .as("bot leg must not enqueue on a guarded transfer queue")
                    .isNull();
            assertThat(keyFactory.bridgeTransferKey(tx, flow)).isNull();
            assertThat(keyFactory.bridgeSettlementKey(tx, flow)).isNull();
            assertThat(keyFactory.usesBybitVenueInternalCarryQueue(tx))
                    .as("bot leg must not route to the Bybit venue-internal carry queue")
                    .isFalse();
        }
    }

    // ---- helpers ----

    private NormalizedTransaction transferTransaction(String asset, String qty) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setWalletAddress("BYBIT:" + UID + ":UTA");
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setMissingDataReasons(new ArrayList<>());
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private NormalizedTransaction normalizedBotLeg(
            NormalizedTransactionType type,
            NormalizedLegRole role,
            String asset,
            String qty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("BYBIT-" + UID + ":FUNDING_HISTORY:" + asset);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setWalletAddress("BYBIT:" + UID + ":BOT");
        tx.setType(type);
        tx.setCorrelationId(null);
        tx.setContinuityCandidate(false);
        tx.setMissingDataReasons(new ArrayList<>(List.of("BOT_TRANSFER")));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setAccountRef("BYBIT:" + UID + ":BOT");
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private ExternalLedgerRaw botRow(String asset, String qty) {
        ExternalLedgerRaw row = new ExternalLedgerRaw();
        row.setUid(UID);
        row.setWalletRef("BYBIT:" + UID + ":UTA");
        row.setBybitType("Bot");
        row.setAssetSymbol(asset);
        row.setQuantityRaw(new BigDecimal(qty));
        return row;
    }
}
