package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.application.costbasis.application.replay.handler.AsyncSpotOrderReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BorrowReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.BybitVenueInternalReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.FamilyEquivalentCustodyReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GenericAsyncLifecycleReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.GmxLpEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LiquidStakingReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.LpReceiptEntryReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.PositionScopedLpExitReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.RepayReplayHandler;
import com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.planning.ReplayRoutingDecision;
import com.walletradar.application.costbasis.application.replay.planning.ReplayTransactionRouter;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.CounterpartyBasisPoolReplayHook;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplaySettlementAllocator;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.application.costbasis.support.AcquisitionFeeCapitalizationPolicy;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R6a (M1) regression: full Aura {@code LP_POSITION_STAKE} → {@code LP_POSITION_UNSTAKE} wrap round-trip.
 *
 * <p>Staking a Balancer V3 BPT (canonicalized to an {@code LP-RECEIPT} marker) into the Aura
 * BaseRewardPool mints the {@code auraBPT} deposit-vault wrapper — a basis-neutral CARRY, not a
 * disposal. The symmetric {@code withdrawAndUnwrap} unstake burns the wrapper and returns the BPT
 * plus BAL/AURA/WAVAX reward accrual. This test proves that once the unstake is correctly typed
 * {@link NormalizedTransactionType#LP_POSITION_UNSTAKE} (see
 * {@code AuraUnstakeFullPipelineClassificationTest}):</p>
 * <ul>
 *   <li>the deposit-vault {@code CARRY_IN} booked at stake gets a matching {@code CARRY_OUT} at
 *       unstake (no phantom held position; asset-only net across the round-trip is $0);</li>
 *   <li>the returned BPT LP-RECEIPT position is restored with the carried basis; and</li>
 *   <li>the BAL/AURA/WAVAX reward legs are zero-cost income (net lane $0).</li>
 * </ul>
 *
 * <p>Anchor tx (evidence only): unstake
 * {@code 0x2447dc7f857603fa5fcaa309f7f63e1c6b1e51d8da44e01e5ac7f09d8d82f11e} on Avalanche.</p>
 */
class ReplayDispatcherAuraUnstakeWrapTest {

    private static final String CORR =
            "lp-position:avalanche:balancerv3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String LP_RECEIPT =
            "LP-RECEIPT:AVALANCHE:BALANCERV3:0xfcec3c8d86329defb548202fe1b86ff2188603a8";
    private static final String VAULT_SYM = "auraAave GHO/USDT/USDC-vault";
    private static final String VAULT_CONTRACT = "0x7037358a6f2c1d9e5cc9b4a29e7415a7060dadcc";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final BigDecimal QTY = new BigDecimal("42.898493206184378445");
    private static final BigDecimal BASIS = new BigDecimal("44.29");
    // Basis is carried by proportional (qty-ratio) multiplication, which leaves a sub-cent
    // high-precision remainder (e.g. 44.2899…998). Compare within a tiny epsilon, not exactly.
    private static final BigDecimal EPS = new BigDecimal("0.0000001");

    @Test
    @DisplayName("Aura stake→unstake round-trip: deposit-vault CARRY_OUT closes phantom, LP-RECEIPT basis restored, rewards net-$0")
    void auraStakeUnstakeRoundTripCarriesBasisAndClosesWrapper() {
        ReplayAssetSupport assetSupport = new ReplayAssetSupport();
        ReplayMarketAuthority marketAuthority = mock(ReplayMarketAuthority.class);
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        ReplayDispatcher dispatcher = buildDispatcher(assetSupport, marketAuthority);

        List<AssetLedgerPoint> ledgerPoints = new ArrayList<>();
        ReplayExecutionState replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", ledgerPoints, Instant.now()),
                null, null, null);

        // Seed the LP-RECEIPT (BPT) position as if created by the LP_ENTRY: 42.898 units, $44.29 basis.
        NormalizedTransaction stake = stakeTx();
        AssetKey receiptKey = assetSupport.assetKey(stake, receiptFlow(QTY.negate()));
        PositionState receiptPos = replayState.position(receiptKey);
        receiptPos.setQuantity(QTY);
        receiptPos.setTotalCostBasisUsd(BASIS);
        receiptPos.setNetTotalCostBasisUsd(BASIS);

        // ---- STAKE: LP-RECEIPT out → deposit-vault in (basis carried into the wrapper) ----
        dispatcher.dispatch(stake, replayState);

        AssetKey vaultKey = assetSupport.assetKey(stake, vaultFlow(QTY));
        PositionState vaultPos = replayState.position(vaultKey);
        assertThat(vaultPos.quantity()).isEqualByComparingTo(QTY);
        assertThat(vaultPos.totalCostBasisUsd()).isCloseTo(BASIS, within(EPS));
        assertThat(receiptPos.quantity()).isEqualByComparingTo("0");
        assertThat(pointsFor(ledgerPoints, VAULT_SYM))
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.CARRY_IN);

        // ---- UNSTAKE: deposit-vault out (CARRY_OUT) + LP-RECEIPT in (restore) + zero-cost rewards ----
        dispatcher.dispatch(unstakeTx(), replayState);

        // Deposit-vault wrapper fully closed: no phantom held position remains.
        assertThat(vaultPos.quantity()).isEqualByComparingTo("0");
        assertThat(vaultPos.totalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(vaultPos.netTotalCostBasisUsd()).isEqualByComparingTo("0");

        // The deposit-vault CARRY_IN (stake) now has a matching CARRY_OUT (unstake) → asset-only net $0.
        List<AssetLedgerPoint> vaultPoints = pointsFor(ledgerPoints, VAULT_SYM);
        assertThat(vaultPoints)
                .extracting(AssetLedgerPoint::getBasisEffect)
                .containsExactly(AssetLedgerPoint.BasisEffect.CARRY_IN, AssetLedgerPoint.BasisEffect.CARRY_OUT);
        assertThat(vaultPoints.stream()
                .map(AssetLedgerPoint::getNetCostBasisDeltaUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0");

        // Returned BPT LP-RECEIPT position restored with the carried basis.
        assertThat(receiptPos.quantity()).isEqualByComparingTo(QTY);
        assertThat(receiptPos.totalCostBasisUsd()).isCloseTo(BASIS, within(EPS));
        assertThat(receiptPos.netTotalCostBasisUsd()).isCloseTo(BASIS, within(EPS));

        // BAL / AURA / WAVAX reward legs are zero-cost income: ACQUIRE with $0 net (and $0 tax here,
        // since the market authority resolves no spot price in this harness).
        for (String reward : List.of("BAL", "AURA", "WAVAX")) {
            assertThat(pointsFor(ledgerPoints, reward))
                    .as("reward %s must be zero-cost ACQUIRE", reward)
                    .isNotEmpty()
                    .allSatisfy(point -> {
                        assertThat(point.getBasisEffect()).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
                        assertThat(point.getNetCostBasisDeltaUsd()).isEqualByComparingTo("0");
                    });
        }

        // No disposal/realized P&L anywhere in the round-trip.
        assertThat(ledgerPoints)
                .noneMatch(point -> point.getBasisEffect() == AssetLedgerPoint.BasisEffect.DISPOSE);
    }

    private static List<AssetLedgerPoint> pointsFor(List<AssetLedgerPoint> points, String symbol) {
        return points.stream()
                .filter(point -> symbol.equalsIgnoreCase(point.getAssetSymbol()))
                .toList();
    }

    private NormalizedTransaction stakeTx() {
        NormalizedTransaction tx = base("aura-stake", NormalizedTransactionType.LP_POSITION_STAKE);
        tx.setFlows(List.of(receiptFlow(QTY.negate()), vaultFlow(QTY)));
        return tx;
    }

    private NormalizedTransaction unstakeTx() {
        NormalizedTransaction tx = base("aura-unstake", NormalizedTransactionType.LP_POSITION_UNSTAKE);
        tx.setFlows(List.of(
                vaultFlow(QTY.negate()),
                reward("BAL", "0xe15bcb9e0ea69e6ab9fa080c4c4a5632896298c3", new BigDecimal("0.083920744258171159")),
                reward("AURA", "0x1509706a6c66ca549ff0cb464de88231ddbe213b", new BigDecimal("0.082588898588616934")),
                reward("AURA", "0x1509706a6c66ca549ff0cb464de88231ddbe213b", new BigDecimal("0.212195409207323942")),
                reward("WAVAX", "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", new BigDecimal("0.000779301159036005")),
                receiptFlow(QTY)));
        return tx;
    }

    private NormalizedTransaction base(String id, NormalizedTransactionType type) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.AVALANCHE);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setProtocolName("Aura");
        tx.setCorrelationId(CORR);
        tx.setBlockTimestamp(Instant.parse("2026-03-25T11:00:00Z"));
        return tx;
    }

    private NormalizedTransaction.Flow receiptFlow(BigDecimal qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(LP_RECEIPT);
        flow.setQuantityDelta(qty);
        return flow;
    }

    private NormalizedTransaction.Flow vaultFlow(BigDecimal qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(VAULT_SYM);
        flow.setAssetContract(VAULT_CONTRACT);
        flow.setQuantityDelta(qty);
        return flow;
    }

    private NormalizedTransaction.Flow reward(String symbol, String contract, BigDecimal qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(qty);
        flow.setUnitPriceUsd(new BigDecimal("1.00"));
        flow.setValueUsd(qty);
        return flow;
    }

    private ReplayDispatcher buildDispatcher(ReplayAssetSupport assetSupport, ReplayMarketAuthority marketAuthority) {
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(marketAuthority);
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
        ContinuityCarryService carryService = new ContinuityCarryService(engine, flowSupport);
        ReplayPendingTransferKeyFactory keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        ReplayTransferClassifier transferClassifier = new ReplayTransferClassifier(keyFactory);
        TransferReplayHandler transferReplayHandler = TransferReplayHandlerFixtures.handler(
                flowSupport, carryService, keyFactory, transferClassifier,
                new ReplayPendingTransferMatcher(), marketAuthority);
        BybitVenueInternalReplayHandler bybitVenueInternalReplayHandler =
                new BybitVenueInternalReplayHandler(transferClassifier, transferReplayHandler);
        LiquidStakingReplayHandler liquidStakingReplayHandler = new LiquidStakingReplayHandler(
                assetSupport, flowSupport, new ReplaySettlementAllocator(assetSupport, flowSupport));
        FamilyEquivalentCustodyReplayHandler familyEquivalentCustodyReplayHandler =
                new FamilyEquivalentCustodyReplayHandler(assetSupport, flowSupport, carryService, keyFactory);
        ReplayTransactionRouter replayTransactionRouter = mock(ReplayTransactionRouter.class);
        when(replayTransactionRouter.route(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReplayRoutingDecision.generic());
        ReplayRouteHandlerRegistry replayRouteHandlerRegistry = ReplayRouteHandlerRegistryFactory.create(
                mock(com.walletradar.application.costbasis.application.replay.handler.EulerLoopReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class),
                mock(LpReceiptEntryReplayHandler.class),
                mock(GenericAsyncLifecycleReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class),
                liquidStakingReplayHandler,
                familyEquivalentCustodyReplayHandler);
        return new ReplayDispatcher(
                replayTransactionRouter, assetSupport, flowSupport, transferClassifier, keyFactory,
                replayRouteHandlerRegistry, mock(AcquisitionFeeCapitalizationPolicy.class),
                transferReplayHandler, bybitVenueInternalReplayHandler, liquidStakingReplayHandler,
                familyEquivalentCustodyReplayHandler, mock(GenericAsyncLifecycleReplayHandler.class),
                mock(GmxLpEntryReplayHandler.class), mock(LpReceiptEntryReplayHandler.class),
                mock(PositionScopedLpExitReplayHandler.class), mock(AsyncSpotOrderReplayHandler.class),
                mock(CounterpartyBasisPoolReplayHook.class),
                mock(com.walletradar.application.costbasis.application.replay.support.LeverageBorrowReplayHook.class),
                mock(BorrowReplayHandler.class), mock(RepayReplayHandler.class), marketAuthority);
    }
}
