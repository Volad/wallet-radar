package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import com.walletradar.application.costbasis.application.replay.model.ContinuityKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * RC-2 — LENDING_DEPOSIT corridor basis dispatch uses USD value weighting instead of raw quantity.
 *
 * <p>When two inbound legs share the FAMILY:USDC continuity bucket (because both symbols resolve
 * to the same family), an unpriced leg with a massive raw quantity must NOT absorb the outgoing
 * basis by claiming a disproportionate raw-quantity slice. Only legs with a non-zero USD weight
 * receive carry.
 *
 * <p>Bucket sharing mechanism: both inbound legs have symbols that map to FAMILY:USDC
 * ({@code eUSDC-2} via Euler indexed-receipt rule; {@code USDC} via SYMBOL_FAMILIES).
 * Their {@code continuityKey} is therefore {@code (wallet, network, FAMILY:USDC)}, giving them
 * access to the same bucket that was seeded by the USDC outbound leg.
 *
 * <p>Test scenarios:
 * <ul>
 *   <li>Priced eUSDC-2 + unpriced USDC-large-qty → full basis flows to eUSDC-2; unpriced gets $0</li>
 *   <li>Both unpriced (all-unpriced fallback) → no carry distributed; bucket remains intact</li>
 * </ul>
 */
class TransferReplayHandlerLendingDepositUsdWeightedTest {

    /**
     * Avalanche eUSDC-2 EVK vault contract — maps to FAMILY:USDC via the Euler indexed-receipt rule.
     * The USDC outbound and this inbound therefore share the same FAMILY:USDC continuity bucket.
     */
    private static final String EUSDC2_CONTRACT = "0x39de0f00189306062d79edec6dca5bb6bfd108f9";

    /** Canonical USDC contract on Avalanche — maps to FAMILY:USDC via SYMBOL_FAMILIES. */
    private static final String USDC_CONTRACT = "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e";

    /**
     * A second USDC contract address (different position key but same FAMILY:USDC identity).
     * Simulates an "unpriced large-qty USDC-family token" arriving in the same corridor.
     */
    private static final String NOISY_USDC_CONTRACT = "0x0000000000000000000000000000000000009999";

    private static final String WALLET = "0x1111111111111111111111111111111111111111";

    private TransferReplayHandler handler;
    private ReplayExecutionState replayState;
    private ReplayPendingTransferKeyFactory keyFactory;

    @BeforeEach
    void setUp() {
        var assetSupport = new ReplayAssetSupport();
        var engine = new GenericFlowReplayEngine(null);
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        var marketAuthority = mock(ReplayMarketAuthority.class);
        handler = TransferReplayHandlerFixtures.handler(flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now()));
    }

    /**
     * Two inbound LENDING_DEPOSIT legs sharing the FAMILY:USDC bucket:
     * <ul>
     *   <li>eUSDC-2: 1,000 qty @ $1.00 → USD weight = $1,000 → gets full carry</li>
     *   <li>noisyUSDC: 4,500,000,000 qty @ null (unpriced) → USD weight = $0 → gets $0</li>
     * </ul>
     * Without T-02 the raw-qty slice would give noisyUSDC min(4.5B, 1000) = 1000 units from the
     * bucket, draining the entire carry and leaving eUSDC-2 with $0.
     */
    @Test
    @DisplayName("priced eUSDC-2 leg gets full carry; unpriced large-qty leg gets $0")
    void pricedLegReceivesFullBasisUnpricedLegGetsZero() {
        // Seed USDC position with 1,000 qty and $1,249 basis.
        AssetKey usdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState usdcPos = replayState.position(usdcKey);
        usdcPos.setQuantity(new BigDecimal("1000"));
        usdcPos.setTotalCostBasisUsd(new BigDecimal("1249"));
        usdcPos.setUncoveredQuantity(BigDecimal.ZERO);
        usdcPos.setPerWalletAvco(new BigDecimal("1.249"));

        NormalizedTransaction tx = lendingDepositTx(
                /* outbound */ "1000", USDC_CONTRACT,
                /* priced eUSDC-2 */ "1000", EUSDC2_CONTRACT, "1.00",
                /* unpriced noisy-USDC */ "4500000000", NOISY_USDC_CONTRACT, null
        );

        // Flow 0: USDC outbound → deposits carry into FAMILY:USDC bucket.
        handler.applyTransfer(tx, tx.getFlows().get(0), 0, usdcPos, replayState);

        // Flow 1: eUSDC-2 inbound (priced @ $1.00) → T-02 USD-weighted carry.
        AssetKey eusdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, EUSDC2_CONTRACT, "eUSDC-2", EUSDC2_CONTRACT);
        PositionState eusdcPos = replayState.position(eusdcKey);
        handler.applyTransfer(tx, tx.getFlows().get(1), 1, eusdcPos, replayState);

        // Flow 2: noisyUSDC inbound (unpriced, huge qty) → receives no carry.
        AssetKey noisyKey = new AssetKey(WALLET, NetworkId.AVALANCHE, NOISY_USDC_CONTRACT, "USDC", NOISY_USDC_CONTRACT);
        PositionState noisyPos = replayState.position(noisyKey);
        handler.applyTransfer(tx, tx.getFlows().get(2), 2, noisyPos, replayState);

        // eUSDC-2 gets the full carry (within a small tolerance for single-leg weighting).
        assertThat(eusdcPos.totalCostBasisUsd())
                .isCloseTo(new BigDecimal("1249"), within(new BigDecimal("1.00")));
        assertThat(eusdcPos.uncoveredQuantity()).isEqualByComparingTo("0");

        // noisyUSDC receives no carry — it is unpriced so its USD weight is $0.
        assertThat(noisyPos.totalCostBasisUsd()).isEqualByComparingTo("0");
    }

    /**
     * All-unpriced fallback: both inbound legs have null price → no carry must be distributed.
     * The basis must remain in the source bucket (not silently destroyed).
     */
    @Test
    @DisplayName("all-unpriced legs → no carry distributed; basis remains in source bucket")
    void allUnpricedLegsLeaveBasisInSourceBucket() {
        AssetKey usdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, USDC_CONTRACT, "USDC", USDC_CONTRACT);
        PositionState usdcPos = replayState.position(usdcKey);
        usdcPos.setQuantity(new BigDecimal("1000"));
        usdcPos.setTotalCostBasisUsd(new BigDecimal("1249"));
        usdcPos.setUncoveredQuantity(BigDecimal.ZERO);
        usdcPos.setPerWalletAvco(new BigDecimal("1.249"));

        NormalizedTransaction tx = lendingDepositTx(
                "1000", USDC_CONTRACT,
                "1000", EUSDC2_CONTRACT, null,          // eUSDC-2 unpriced
                "4500000000", NOISY_USDC_CONTRACT, null  // noisy also unpriced
        );

        handler.applyTransfer(tx, tx.getFlows().get(0), 0, usdcPos, replayState);

        AssetKey eusdcKey = new AssetKey(WALLET, NetworkId.AVALANCHE, EUSDC2_CONTRACT, "eUSDC-2", EUSDC2_CONTRACT);
        PositionState eusdcPos = replayState.position(eusdcKey);
        handler.applyTransfer(tx, tx.getFlows().get(1), 1, eusdcPos, replayState);

        AssetKey noisyKey = new AssetKey(WALLET, NetworkId.AVALANCHE, NOISY_USDC_CONTRACT, "USDC", NOISY_USDC_CONTRACT);
        PositionState noisyPos = replayState.position(noisyKey);
        handler.applyTransfer(tx, tx.getFlows().get(2), 2, noisyPos, replayState);

        // Neither leg received any carry — both applied as unknown transfers.
        assertThat(eusdcPos.totalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(noisyPos.totalCostBasisUsd()).isEqualByComparingTo("0");

        // Basis remains in the FAMILY:USDC bucket (source not drained).
        ContinuityKey bucketKey = keyFactory.continuityKey(tx, tx.getFlows().get(1));
        assertThat(replayState.continuity().bucket(bucketKey).totalQuantity())
                .isGreaterThan(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs a LENDING_DEPOSIT transaction with three flows:
     * <ul>
     *   <li>Flow 0: outbound principal (negative qty).</li>
     *   <li>Flow 1: eUSDC-2 inbound (positive qty, optionally priced).</li>
     *   <li>Flow 2: noisy USDC-family inbound (positive qty, typically unpriced, large qty).</li>
     * </ul>
     *
     * <p>Both inbound flows use "USDC"-family symbols (eUSDC-2 → FAMILY:USDC via Euler indexed-
     * receipt rule; USDC symbol → FAMILY:USDC via SYMBOL_FAMILIES) but different contract
     * addresses, ensuring they share the same FAMILY:USDC continuity bucket while maintaining
     * separate position states.
     */
    private NormalizedTransaction lendingDepositTx(
            String outQty, String outContract,
            String eusdcQty, String eusdcContract, String eusdcPriceUsd,
            String noisyQty, String noisyContract, String noisyPriceUsd
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("lending-deposit-t02");
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(NormalizedTransactionType.LENDING_DEPOSIT);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setBlockTimestamp(Instant.parse("2026-03-15T10:00:00Z"));
        tx.setContinuityCandidate(false);

        NormalizedTransaction.Flow out = new NormalizedTransaction.Flow();
        out.setRole(NormalizedLegRole.TRANSFER);
        out.setAssetSymbol("USDC");
        out.setAssetContract(outContract);
        out.setQuantityDelta(new BigDecimal(outQty).negate());

        NormalizedTransaction.Flow eusdcIn = new NormalizedTransaction.Flow();
        eusdcIn.setRole(NormalizedLegRole.TRANSFER);
        eusdcIn.setAssetSymbol("eUSDC-2");
        eusdcIn.setAssetContract(eusdcContract);
        eusdcIn.setQuantityDelta(new BigDecimal(eusdcQty));
        if (eusdcPriceUsd != null) {
            eusdcIn.setUnitPriceUsd(new BigDecimal(eusdcPriceUsd));
        }

        NormalizedTransaction.Flow noisyIn = new NormalizedTransaction.Flow();
        noisyIn.setRole(NormalizedLegRole.TRANSFER);
        noisyIn.setAssetSymbol("USDC");      // maps to FAMILY:USDC → shares the same bucket
        noisyIn.setAssetContract(noisyContract);
        noisyIn.setQuantityDelta(new BigDecimal(noisyQty));
        if (noisyPriceUsd != null) {
            noisyIn.setUnitPriceUsd(new BigDecimal(noisyPriceUsd));
        }

        tx.setFlows(new ArrayList<>(List.of(out, eusdcIn, noisyIn)));
        return tx;
    }
}
