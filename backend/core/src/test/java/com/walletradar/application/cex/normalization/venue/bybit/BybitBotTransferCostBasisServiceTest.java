package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-058 / NEW-12 Phase 2 unit coverage for {@link BybitBotTransferCostBasisService}. Fixtures are
 * keyed to the two live bot members (evidence anchors only, never runtime keys): {@code 516601508}
 * (multi-asset ETH+BTC) and {@code 421325298} (single-asset DOGE regression).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BybitBotTransferCostBasisServiceTest {

    private static final String M_ETH_BTC = "516601508";
    private static final String M_DOGE = "421325298";

    private static final BigDecimal EPS = new BigDecimal("0.0000001");

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;

    private BybitBotTransferCostBasisService service() {
        return new BybitBotTransferCostBasisService(mongoOperations, normalizedTransactionRepository);
    }

    @Test
    void multiAsset_ethBtc_perAssetExecutionAnchoredBasis_noUpwardRedistribution() {
        // Net stablecoin consumed = 185 - 1.2649693 = 183.7350307. The ingested fills only partially
        // cover the returned qty, so Σ assetBasis (~159) is BELOW netConsumed. Revised D3 does NOT scale
        // up (NEW-12-R): each asset is anchored at its OWN avgExecPrice and the shortfall stays
        // unallocated compartment residual.
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-185", "2025-10-10T14:04:15Z");
        NormalizedTransaction dust = stableLeg(M_ETH_BTC, "dust", "1.2649693", "2025-10-18T14:04:24Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.01374624", "2025-10-21T11:59:00Z");
        NormalizedTransaction btcOct = cryptoReturn(M_ETH_BTC, "btc-oct", "BTC", "0.00048951", "2025-10-21T11:59:00Z");
        NormalizedTransaction btcDec = cryptoReturn(M_ETH_BTC, "btc-dec", "BTC", "0.000797202", "2025-12-12T10:14:00Z");

        stubBotDocs(List.of(toBot, dust, ethReturn, btcOct, btcDec));
        stubExecutions(List.of(
                fill(M_ETH_BTC, "ETH", "0.0055", "3630", "2025-11-03T15:29:32Z"),
                fill(M_ETH_BTC, "ETH", "0.00597", "3350", "2025-11-04T18:04:09Z"),
                fill(M_ETH_BTC, "ETH", "0.00664", "2985.63", "2025-11-17T19:43:26Z"),
                fill(M_ETH_BTC, "BTC", "0.000052", "95750", "2025-11-14T12:12:29Z"),
                fill(M_ETH_BTC, "BTC", "0.000079", "88684.7", "2025-11-19T20:13:27Z"),
                fill(M_ETH_BTC, "BTC", "0.000231", "86627.3", "2025-12-01T22:04:45Z")
        ));

        service().computeBotCostBasis();

        // Every returned crypto leg is CONFIRMED with the deterministic BOT_LEDGER basis, no FMV.
        for (NormalizedTransaction tx : List.of(ethReturn, btcOct, btcDec)) {
            assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
            assertThat(tx.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST");
            assertThat(principal(tx).getPriceSource()).isEqualTo(PriceSource.BOT_LEDGER);
            assertThat(principal(tx).getPriceSource()).isNotIn(PriceSource.BYBIT, PriceSource.BINANCE, PriceSource.COINGECKO);
            assertThat(principal(tx).getUnitPriceUsd()).isNotNull();
        }

        // Each asset is priced at its OWN execution average (no cross-asset dumping, scale = 1).
        BigDecimal ethAvg = weightedAvg(new String[][]{{"0.0055", "3630"}, {"0.00597", "3350"}, {"0.00664", "2985.63"}});
        BigDecimal btcAvg = weightedAvg(new String[][]{{"0.000052", "95750"}, {"0.000079", "88684.7"}, {"0.000231", "86627.3"}});
        assertThat(principal(ethReturn).getUnitPriceUsd().subtract(ethAvg).abs()).isLessThan(new BigDecimal("0.01"));
        assertThat(principal(btcOct).getUnitPriceUsd().subtract(btcAvg).abs()).isLessThan(new BigDecimal("0.01"));
        // Both BTC legs share the same compartment unit price.
        assertThat(principal(btcOct).getUnitPriceUsd()).isEqualByComparingTo(principal(btcDec).getUnitPriceUsd());

        // Total booked = execution-anchored basis and is strictly BELOW netConsumed (residual unallocated).
        BigDecimal total = value(ethReturn).add(value(btcOct)).add(value(btcDec));
        BigDecimal expected = new BigDecimal("0.01374624").multiply(ethAvg)
                .add(new BigDecimal("0.001286712").multiply(btcAvg));
        assertThat(total.subtract(expected).abs()).isLessThan(new BigDecimal("0.05"));
        assertThat(total).isLessThan(new BigDecimal("183.7350307"));

        // O2: bot session id stamped for traceability.
        assertThat(ethReturn.getMetadata().getString("botSessionId")).isEqualTo("BYBIT:516601508:BOT");
    }

    @Test
    void multiAsset_execBasisAboveNet_scaledDownToNet() {
        // ADR-058 D3 conservation cap: execution-derived basis (196.52) exceeds both netConsumed
        // (183.735) and gross (185). Revised D3 scales it DOWN proportionally by
        // netConsumed / Σ assetBasis; the cap uses NET, never gross.
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-185", "2025-10-10T14:04:15Z");
        NormalizedTransaction dust = stableLeg(M_ETH_BTC, "dust", "1.2649693", "2025-10-18T14:04:24Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.01374624", "2025-10-21T11:59:00Z");
        NormalizedTransaction btc = cryptoReturn(M_ETH_BTC, "btc", "BTC", "0.001286712", "2025-10-21T11:59:00Z");

        stubBotDocs(List.of(toBot, dust, ethReturn, btc));
        stubExecutions(List.of(
                fill(M_ETH_BTC, "ETH", "0.02", "4000", "2025-11-03T15:29:32Z"),
                fill(M_ETH_BTC, "BTC", "0.001", "110000", "2025-11-14T12:12:29Z")
        ));

        service().computeBotCostBasis();

        BigDecimal net = new BigDecimal("183.7350307");
        BigDecimal total = value(ethReturn).add(value(btc));
        // Capped to net (not gross 185): conservation of consideration.
        assertThat(total.subtract(net).abs()).isLessThan(EPS);
        assertThat(total.subtract(new BigDecimal("185")).abs()).isGreaterThan(new BigDecimal("1"));

        // Scaled DOWN proportionally: each asset is below its own avgExecPrice.
        BigDecimal rawTotal = new BigDecimal("0.01374624").multiply(new BigDecimal("4000"))
                .add(new BigDecimal("0.001286712").multiply(new BigDecimal("110000")));
        BigDecimal scale = net.divide(rawTotal, java.math.MathContext.DECIMAL64);
        assertThat(principal(ethReturn).getUnitPriceUsd()).isLessThan(new BigDecimal("4000"));
        assertThat(principal(btc).getUnitPriceUsd()).isLessThan(new BigDecimal("110000"));
        assertThat(principal(ethReturn).getUnitPriceUsd().subtract(new BigDecimal("4000").multiply(scale)).abs())
                .isLessThan(new BigDecimal("0.5"));
        assertThat(principal(btc).getUnitPriceUsd().subtract(new BigDecimal("110000").multiply(scale)).abs())
                .isLessThan(new BigDecimal("5"));
    }

    @Test
    void multiAsset_zeroExecutionCoverage_allEvidenceMissing_noInflation() {
        // NEW-12-R regression: the Oct-2025 session for 516601508 has NO EXECUTION_SPOT fills in-window.
        // Every returned asset must be bounded EVIDENCE_MISSING; the netConsumed must NOT collapse onto
        // 0.0137 ETH (the $8,673/ETH over-attribution defect).
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-120.5", "2025-10-10T14:04:15Z");
        NormalizedTransaction dust = stableLeg(M_ETH_BTC, "dust", "1.27", "2025-10-18T14:04:24Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.01374624", "2025-10-21T11:59:00Z");
        NormalizedTransaction btc = cryptoReturn(M_ETH_BTC, "btc", "BTC", "0.001286712", "2025-10-21T11:59:00Z");

        stubBotDocs(List.of(toBot, dust, ethReturn, btc));
        stubExecutions(List.of());

        service().computeBotCostBasis();

        for (NormalizedTransaction tx : List.of(ethReturn, btc)) {
            assertThat(tx.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
            assertThat(tx.getMissingDataReasons()).contains("BOT_TRANSFER_EVIDENCE_MISSING");
            assertThat(tx.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST");
            assertThat(principal(tx).getPriceSource()).isEqualTo(PriceSource.PRICING_SKIPPED);
            // No fabricated per-unit basis at all — no $8,673/ETH concentration.
            assertThat(principal(tx).getUnitPriceUsd()).isNull();
            assertThat(principal(tx).getValueUsd()).isNull();
        }
    }

    @Test
    void singleAsset_doge_withCoverage_legacyNetOverQtyBitIdentical() {
        // 421325298: to-bot 125, dust returns 65.85110362, net 59.14889638; single DOGE return WITH
        // execution coverage -> legacy net/qty BOT_LEDGER rule (bit-identical to pre-Phase-2). The DOGE
        // execution unit price is irrelevant here: the single-asset degenerate case always uses net/qty.
        NormalizedTransaction out1 = stableLeg(M_DOGE, "o1", "-60", "2025-01-23T18:15:54Z");
        NormalizedTransaction out2 = stableLeg(M_DOGE, "o2", "-30", "2025-01-30T10:28:33Z");
        NormalizedTransaction in1 = stableLeg(M_DOGE, "i1", "30", "2025-01-30T10:31:10Z");
        NormalizedTransaction out3 = stableLeg(M_DOGE, "o3", "-35", "2025-01-30T10:37:42Z");
        NormalizedTransaction in2 = stableLeg(M_DOGE, "i2", "8.16705533", "2025-01-31T21:39:32Z");
        NormalizedTransaction in3 = stableLeg(M_DOGE, "i3", "27.68404829", "2025-02-03T01:56:25Z");
        NormalizedTransaction doge = cryptoReturn(M_DOGE, "doge", "DOGE", "150.591", "2025-01-31T21:39:32Z");

        stubBotDocs(List.of(out1, out2, in1, out3, in2, in3, doge));
        // DOGE execution coverage in-window (2025-01-23 .. 2025-02-03).
        stubExecutions(List.of(fill(M_DOGE, "DOGE", "150", "0.4", "2025-02-01T07:39:43Z")));

        service().computeBotCostBasis();

        BigDecimal expectedUnit = new BigDecimal("59.14889638")
                .divide(new BigDecimal("150.591"), java.math.MathContext.DECIMAL64);
        assertThat(doge.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(principal(doge).getPriceSource()).isEqualTo(PriceSource.BOT_LEDGER);
        assertThat(principal(doge).getUnitPriceUsd().subtract(expectedUnit).abs()).isLessThan(EPS);
        assertThat(doge.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST", "BOT_TRANSFER_EVIDENCE_MISSING");
    }

    @Test
    void singleAsset_withoutCoverage_evidenceMissing() {
        // Revised D4: a single returned asset with NO execution coverage is bounded EVIDENCE_MISSING,
        // never a fabricated net/qty basis (the old single-asset no-coverage fallback is removed).
        NormalizedTransaction toBot = stableLeg(M_DOGE, "o1", "-125", "2025-01-23T18:15:54Z");
        NormalizedTransaction in = stableLeg(M_DOGE, "i1", "65.85110362", "2025-02-03T01:56:25Z");
        NormalizedTransaction doge = cryptoReturn(M_DOGE, "doge", "DOGE", "150.591", "2025-01-31T21:39:32Z");

        stubBotDocs(List.of(toBot, in, doge));
        stubExecutions(List.of());

        service().computeBotCostBasis();

        assertThat(doge.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(doge.getMissingDataReasons()).contains("BOT_TRANSFER_EVIDENCE_MISSING");
        assertThat(doge.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST");
        assertThat(principal(doge).getPriceSource()).isEqualTo(PriceSource.PRICING_SKIPPED);
        assertThat(principal(doge).getUnitPriceUsd()).isNull();
        assertThat(principal(doge).getValueUsd()).isNull();
    }

    @Test
    void multiAsset_oneUnpriced_pricedAssetKeepsOwnBasis_residualUnallocated() {
        // NEW-12-R (task test a): netConsumed = 100. ETH has execution coverage (0.02 @ 3000 -> basis 60);
        // PEPE has none. ETH is anchored to ITS OWN basis (60), NOT the whole 100. PEPE is bounded
        // EVIDENCE_MISSING and its 40 share stays unallocated (never dumped onto the priced sibling).
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-100", "2025-10-10T14:04:15Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.02", "2025-10-21T11:59:00Z");
        NormalizedTransaction pepe = cryptoReturn(M_ETH_BTC, "pepe", "PEPE", "1000000", "2025-10-21T11:59:00Z");

        stubBotDocs(List.of(toBot, ethReturn, pepe));
        // Only ETH has an execution price; PEPE has none.
        stubExecutions(List.of(fill(M_ETH_BTC, "ETH", "0.02", "3000", "2025-11-03T15:29:32Z")));

        service().computeBotCostBasis();

        // ETH keeps its own execution-anchored basis (0.02 * 3000 = 60), NOT the sibling's share.
        assertThat(principal(ethReturn).getPriceSource()).isEqualTo(PriceSource.BOT_LEDGER);
        assertThat(principal(ethReturn).getUnitPriceUsd().subtract(new BigDecimal("3000")).abs()).isLessThan(EPS);
        assertThat(value(ethReturn).subtract(new BigDecimal("60")).abs()).isLessThan(EPS);
        // Residual unallocated: ETH basis is strictly below netConsumed.
        assertThat(value(ethReturn)).isLessThan(new BigDecimal("100"));

        assertThat(pepe.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(pepe.getMissingDataReasons()).contains("BOT_TRANSFER_EVIDENCE_MISSING");
        assertThat(pepe.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST");
        assertThat(principal(pepe).getPriceSource()).isEqualTo(PriceSource.PRICING_SKIPPED);
        assertThat(principal(pepe).getUnitPriceUsd()).isNull();
        assertThat(principal(pepe).getValueUsd()).isNull();
    }

    @Test
    void invariant_noReturnedAssetAvcoExceedsOwnExecutionPrice() {
        // NEW-12-R invariant (task test e): multi-asset session where Σ assetBasis (120) is far below
        // netConsumed (1000) -> scale = 1. Each asset's AVCO equals its OWN avgExecPrice and never
        // exceeds it; the huge shortfall stays unallocated (no upward dumping).
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-1000", "2025-10-10T14:04:15Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.01", "2025-10-21T11:59:00Z");
        NormalizedTransaction btc = cryptoReturn(M_ETH_BTC, "btc", "BTC", "0.001", "2025-10-21T11:59:00Z");

        stubBotDocs(List.of(toBot, ethReturn, btc));
        stubExecutions(List.of(
                fill(M_ETH_BTC, "ETH", "0.01", "3000", "2025-11-03T15:29:32Z"),
                fill(M_ETH_BTC, "BTC", "0.001", "90000", "2025-11-14T12:12:29Z")
        ));

        service().computeBotCostBasis();

        assertThat(principal(ethReturn).getUnitPriceUsd()).isLessThanOrEqualTo(new BigDecimal("3000"));
        assertThat(principal(btc).getUnitPriceUsd()).isLessThanOrEqualTo(new BigDecimal("90000"));
        assertThat(principal(ethReturn).getUnitPriceUsd().subtract(new BigDecimal("3000")).abs()).isLessThan(EPS);
        assertThat(principal(btc).getUnitPriceUsd().subtract(new BigDecimal("90000")).abs()).isLessThan(EPS);
        BigDecimal total = value(ethReturn).add(value(btc));
        assertThat(total.subtract(new BigDecimal("120")).abs()).isLessThan(EPS);
        assertThat(total).isLessThan(new BigDecimal("1000"));
    }

    @Test
    void openSession_unreturnedUsdt_noError() {
        // More consumed than returned (bot still holds inventory). Single-asset ETH with coverage ->
        // legacy net/qty; no exception; priced return resolved to the full netConsumed.
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-185", "2025-10-10T14:04:15Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.02", "2025-10-21T11:59:00Z");

        stubBotDocs(List.of(toBot, ethReturn));
        stubExecutions(List.of(fill(M_ETH_BTC, "ETH", "0.02", "3000", "2025-11-03T15:29:32Z")));

        service().computeBotCostBasis();

        assertThat(ethReturn.getStatus()).isEqualTo(NormalizedTransactionStatus.CONFIRMED);
        assertThat(value(ethReturn).subtract(new BigDecimal("185")).abs()).isLessThan(EPS);
    }

    @Test
    void idempotent_rerun_resolvesZeroSecondTime() {
        NormalizedTransaction toBot = stableLeg(M_DOGE, "o1", "-125", "2025-01-23T18:15:54Z");
        NormalizedTransaction in = stableLeg(M_DOGE, "i1", "65.85110362", "2025-02-03T01:56:25Z");
        NormalizedTransaction doge = cryptoReturn(M_DOGE, "doge", "DOGE", "150.591", "2025-01-31T21:39:32Z");

        // First run: pending cost present.
        stubBotDocs(List.of(toBot, in, doge));
        stubExecutions(List.of());
        int firstDirty = service().computeBotCostBasis();
        assertThat(firstDirty).isEqualTo(1);
        assertThat(doge.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST");

        // Second run: same docs now resolved (no pending cost) -> nothing dirty.
        int secondDirty = service().computeBotCostBasis();
        assertThat(secondDirty).isEqualTo(0);
    }

    @Test
    void noBotDocs_returnsZeroAndNoWrite() {
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(List.of());
        int dirty = service().computeBotCostBasis();
        assertThat(dirty).isEqualTo(0);
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    void executionSwapLegs_readOnly_notMutated_noDoubleCount() {
        // ADR-058 A1 / risk row "double-count": executions are consumed for the SPLIT unit price only.
        // The resolver must never re-book the EXECUTION_SPOT SWAP staging rows (they already
        // dispose USDT / acquire crypto on the umbrella); it only prices the transfer RETURN legs.
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-185", "2025-10-10T14:04:15Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.02", "2025-10-21T11:59:00Z");

        stubBotDocs(List.of(toBot, ethReturn));
        stubExecutions(List.of(fill(M_ETH_BTC, "ETH", "0.02", "3000", "2025-11-03T15:29:32Z")));

        service().computeBotCostBasis();

        // No BybitExtractedEvent is ever written back (executions are a pure read-side pricing input).
        verify(mongoOperations, never()).save(any());
        // Only the return leg is persisted; nothing else double-books the crypto.
        assertThat(principal(ethReturn).getPriceSource()).isEqualTo(PriceSource.BOT_LEDGER);
    }

    @Test
    void resolver_doesNotTouchStableLegsOrNonPendingRows() {
        NormalizedTransaction toBot = stableLeg(M_ETH_BTC, "toBot", "-185", "2025-10-10T14:04:15Z");
        NormalizedTransaction dust = stableLeg(M_ETH_BTC, "dust", "1.2649693", "2025-10-18T14:04:24Z");
        NormalizedTransaction ethReturn = cryptoReturn(M_ETH_BTC, "eth", "ETH", "0.02", "2025-10-21T11:59:00Z");

        stubBotDocs(List.of(toBot, dust, ethReturn));
        stubExecutions(List.of(fill(M_ETH_BTC, "ETH", "0.02", "3000", "2025-11-03T15:29:32Z")));

        service().computeBotCostBasis();

        // Stable transfer legs keep their $1-peg pricing and are never stamped BOT_LEDGER or a botSessionId.
        for (NormalizedTransaction stable : List.of(toBot, dust)) {
            assertThat(principal(stable).getPriceSource()).isEqualTo(PriceSource.STABLECOIN);
            assertThat(stable.getMetadata()).isNull();
            assertThat(stable.getMissingDataReasons()).doesNotContain("BOT_TRANSFER_PENDING_COST", "BOT_TRANSFER_EVIDENCE_MISSING");
        }
    }

    // ---- helpers ----

    private void stubBotDocs(List<NormalizedTransaction> docs) {
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class))).thenReturn(new ArrayList<>(docs));
    }

    private void stubExecutions(List<BybitExtractedEvent> fills) {
        when(mongoOperations.find(any(Query.class), eq(BybitExtractedEvent.class))).thenReturn(new ArrayList<>(fills));
    }

    private NormalizedTransaction stableLeg(String uid, String suffix, String qty, String ts) {
        boolean outbound = qty.startsWith("-");
        NormalizedTransaction tx = base(uid, suffix,
                outbound ? NormalizedTransactionType.EXTERNAL_TRANSFER_OUT : NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                ts);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setMissingDataReasons(new ArrayList<>(List.of("BOT_TRANSFER")));
        tx.setFlows(new ArrayList<>(List.of(
                flow(outbound ? NormalizedLegRole.SELL : NormalizedLegRole.BUY, "USDT", qty, PriceSource.STABLECOIN))));
        return tx;
    }

    private NormalizedTransaction cryptoReturn(String uid, String suffix, String asset, String qty, String ts) {
        NormalizedTransaction tx = base(uid, suffix, NormalizedTransactionType.EXTERNAL_TRANSFER_IN, ts);
        tx.setStatus(NormalizedTransactionStatus.PENDING_PRICE);
        tx.setMissingDataReasons(new ArrayList<>(List.of("BOT_TRANSFER", "BOT_TRANSFER_PENDING_COST")));
        tx.setFlows(new ArrayList<>(List.of(flow(NormalizedLegRole.BUY, asset, qty, null))));
        return tx;
    }

    private NormalizedTransaction base(String uid, String suffix, NormalizedTransactionType type, String ts) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("BYBIT-" + uid + ":FUNDING_HISTORY:" + suffix);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setWalletAddress("BYBIT:" + uid + ":BOT");
        tx.setType(type);
        tx.setContinuityCandidate(false);
        tx.setBlockTimestamp(Instant.parse(ts));
        return tx;
    }

    private NormalizedTransaction.Flow flow(NormalizedLegRole role, String asset, String qty, PriceSource source) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol(asset);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setPriceSource(source);
        return flow;
    }

    private BybitExtractedEvent fill(String uid, String asset, String qty, String price, String ts) {
        BybitExtractedEvent event = new BybitExtractedEvent();
        event.setUid(uid);
        event.setSourceStream("EXECUTION_SPOT");
        event.setUtaDirection("BUY");
        event.setAssetSymbol(asset);
        event.setQuantityRaw(new BigDecimal(qty));
        event.setFilledPrice(new BigDecimal(price));
        event.setTimeUtc(Instant.parse(ts));
        return event;
    }

    private static NormalizedTransaction.Flow principal(NormalizedTransaction tx) {
        return tx.getFlows().get(0);
    }

    private static BigDecimal value(NormalizedTransaction tx) {
        return principal(tx).getValueUsd();
    }

    private static BigDecimal weightedAvg(String[][] qtyPrice) {
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        for (String[] row : qtyPrice) {
            BigDecimal q = new BigDecimal(row[0]);
            BigDecimal p = new BigDecimal(row[1]);
            value = value.add(q.multiply(p));
            qty = qty.add(q);
        }
        return value.divide(qty, java.math.MathContext.DECIMAL64);
    }
}
