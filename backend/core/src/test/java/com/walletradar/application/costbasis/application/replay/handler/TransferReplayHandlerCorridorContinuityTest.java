package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
import com.walletradar.application.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.application.costbasis.application.replay.support.CorridorBasisConservationGuard;
import com.walletradar.application.costbasis.application.replay.support.CorridorBasisConservationResult;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RC-9 — deterministic on-chain↔CEX corridor basis continuity at the replay unit level.
 * Covers T-1 (deposit credit inherits carry-out basis), T-3 (withdrawal inherits → MANTLE avco
 * restored, not collapsed to $540), T-5 (genuine orphan withdrawal IN → ACQUIRE, guard silent).
 */
class TransferReplayHandlerCorridorContinuityTest {

    private static final String CORR = "BYBIT-CORRIDOR:MANTLE:0xa5e755a68349";
    private static final String WALLET = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String BYBIT = "BYBIT:33625378:FUND";

    private TransferReplayHandler handler;
    private ReplayExecutionState replayState;
    private ReplayMarketAuthority marketAuthority;
    private final CorridorBasisConservationGuard guard = new CorridorBasisConservationGuard();

    @BeforeEach
    void setUp() {
        var assetSupport = new ReplayAssetSupport();
        var engine = new GenericFlowReplayEngine(null);
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        var keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        marketAuthority = mock(ReplayMarketAuthority.class);
        lenient().when(marketAuthority.resolve(any(), any())).thenReturn(Optional.empty());
        handler = TransferReplayHandlerFixtures.handler(flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now()));
    }

    @Test
    @DisplayName("T-1/T-2: deposit credit inherits the on-chain carry-out basis; guard finds no orphan")
    void depositCreditInheritsCarryOutBasis() {
        // on-chain wallet holds 3.06 ETH @ avco ~$3,030 (basis $9,272.11)
        AssetKey walletEth = new AssetKey(WALLET, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState walletPos = replayState.position(walletEth);
        walletPos.setQuantity(new BigDecimal("3.06"));
        walletPos.setTotalCostBasisUsd(new BigDecimal("9272.11"));
        walletPos.setUncoveredQuantity(BigDecimal.ZERO);
        walletPos.setPerWalletAvco(new BigDecimal("3030.10"));

        // wallet → CEX deposit: on-chain OUT leg releases the CARRY_OUT.
        NormalizedTransaction outLeg = corridorTx(NormalizedTransactionSource.ON_CHAIN, WALLET, BYBIT, "-3.06");
        handler.applyTransfer(outLeg, outLeg.getFlows().getFirst(), 0, walletPos, replayState);

        // Bybit deposit IN leg must inherit the released basis, not a residual/spot.
        AssetKey bybitEth = new AssetKey(BYBIT, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState bybitPos = replayState.position(bybitEth);
        NormalizedTransaction inLeg = corridorTx(NormalizedTransactionSource.BYBIT, BYBIT, WALLET, "3.06");
        AssetLedgerPoint.BasisEffect effect =
                handler.applyTransfer(inLeg, inLeg.getFlows().getFirst(), 0, bybitPos, replayState);

        assertThat(effect).isEqualTo(AssetLedgerPoint.BasisEffect.CARRY_IN);
        assertThat(bybitPos.totalCostBasisUsd()).isCloseTo(new BigDecimal("9272.11"), within(new BigDecimal("0.01")));
        assertThat(bybitPos.uncoveredQuantity()).isEqualByComparingTo("0");

        // T-2: no orphaned released covered basis remains.
        CorridorBasisConservationResult result = guard.evaluate(replayState);
        assertThat(result.conserved()).isTrue();
    }

    @Test
    @DisplayName("T-3: CEX→wallet withdrawal credit inherits basis so MANTLE avco is ~$3,030, not $540")
    void mantleReturnUsesInheritedBasis() {
        // Bybit FUND holds 3.06 ETH @ avco ~$3,030.
        AssetKey bybitEth = new AssetKey(BYBIT, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState bybitPos = replayState.position(bybitEth);
        bybitPos.setQuantity(new BigDecimal("3.06"));
        bybitPos.setTotalCostBasisUsd(new BigDecimal("9272.11"));
        bybitPos.setUncoveredQuantity(BigDecimal.ZERO);
        bybitPos.setPerWalletAvco(new BigDecimal("3030.10"));

        // CEX → wallet withdrawal: Bybit OUT leg releases the CARRY_OUT.
        NormalizedTransaction outLeg = corridorTx(NormalizedTransactionSource.BYBIT, BYBIT, WALLET, "-3.06");
        handler.applyTransfer(outLeg, outLeg.getFlows().getFirst(), 0, bybitPos, replayState);

        // MANTLE on-chain IN leg inherits the released basis.
        AssetKey mantleEth = new AssetKey(WALLET, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState mantlePos = replayState.position(mantleEth);
        NormalizedTransaction inLeg = corridorTx(NormalizedTransactionSource.ON_CHAIN, WALLET, BYBIT, "3.06");
        handler.applyTransfer(inLeg, inLeg.getFlows().getFirst(), 0, mantlePos, replayState);

        assertThat(mantlePos.totalCostBasisUsd()).isCloseTo(new BigDecimal("9272.11"), within(new BigDecimal("0.01")));
        BigDecimal avco = mantlePos.totalCostBasisUsd().divide(mantlePos.quantity(), java.math.MathContext.DECIMAL64);
        assertThat(avco).isGreaterThan(new BigDecimal("2900"));
        assertThat(guard.evaluate(replayState).conserved()).isTrue();
    }

    @Test
    @DisplayName("T-5: a genuine orphan withdrawal IN (no carry-out) takes a spot ACQUIRE and the guard stays silent")
    void genuineOrphanWithdrawalInboundAcquiresAtSpot() {
        when(marketAuthority.resolve(any(), any())).thenReturn(Optional.of(
                new ReplayMarketAuthority.ResolvedMarketPrice(
                        new BigDecimal("3000"), null, ReplayMarketAuthority.ResolvedMarketPrice.Authority.FLOW)));

        AssetKey mantleEth = new AssetKey(WALLET, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState mantlePos = replayState.position(mantleEth);

        // CEX→wallet withdrawal IN with no matching on-chain carry-out (basis lived on the CEX spot).
        NormalizedTransaction inLeg = corridorTx(NormalizedTransactionSource.ON_CHAIN, WALLET, BYBIT, "1.00");
        AssetLedgerPoint.BasisEffect effect =
                handler.applyTransfer(inLeg, inLeg.getFlows().getFirst(), 0, mantlePos, replayState);

        assertThat(effect).isEqualTo(AssetLedgerPoint.BasisEffect.ACQUIRE);
        assertThat(mantlePos.totalCostBasisUsd()).isEqualByComparingTo("3000");
        // No released covered carry-out exists, so the guard must not fire.
        assertThat(guard.evaluate(replayState).conserved()).isTrue();
    }

    @Test
    @DisplayName("T-6 (RC-7): a bridge credit inherits the bridge carry-out basis; guard conserves")
    void bridgeCreditInheritsCarryOutBasis() {
        String outHash = "0xbridgeout0000000000000000000000000000000000000000000000000000aa";
        AssetKey srcEth = new AssetKey(WALLET, NetworkId.ARBITRUM, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState srcPos = replayState.position(srcEth);
        srcPos.setQuantity(new BigDecimal("2.0"));
        srcPos.setTotalCostBasisUsd(new BigDecimal("6000"));
        srcPos.setUncoveredQuantity(BigDecimal.ZERO);
        srcPos.setPerWalletAvco(new BigDecimal("3000"));

        NormalizedTransaction bridgeOut = bridgeTx(
                NormalizedTransactionType.BRIDGE_OUT, NetworkId.ARBITRUM,
                "bridge:lifi:" + outHash, "-2.0", null);
        handler.applyTransfer(bridgeOut, bridgeOut.getFlows().getFirst(), 0, srcPos, replayState);

        AssetKey dstEth = new AssetKey(WALLET, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState dstPos = replayState.position(dstEth);
        NormalizedTransaction bridgeIn = bridgeTx(
                NormalizedTransactionType.BRIDGE_IN, NetworkId.MANTLE,
                "bridge:lifi:" + outHash, "2.0", "LINKED:" + outHash);
        handler.applyTransfer(bridgeIn, bridgeIn.getFlows().getFirst(), 0, dstPos, replayState);

        assertThat(dstPos.totalCostBasisUsd()).isCloseTo(new BigDecimal("6000"), within(new BigDecimal("0.01")));
        assertThat(dstPos.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(guard.evaluate(replayState).conserved()).isTrue();
    }

    @Test
    @DisplayName("T-7 (RC-7): a bridge credit with an empty source routes to PENDING (uncovered), not a fabricated $0")
    void bridgeCreditWithEmptySourceRoutesToPending() {
        String outHash = "0xbridgeout0000000000000000000000000000000000000000000000000000bb";
        AssetKey dstEth = new AssetKey(WALLET, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState dstPos = replayState.position(dstEth);

        // BRIDGE_IN arrives with no paired BRIDGE_OUT carry on the source (out of backfill window).
        NormalizedTransaction bridgeIn = bridgeTx(
                NormalizedTransactionType.BRIDGE_IN, NetworkId.MANTLE,
                "bridge:lifi:" + outHash, "2.0", "LINKED:" + outHash);
        handler.applyTransfer(bridgeIn, bridgeIn.getFlows().getFirst(), 0, dstPos, replayState);

        // No fabricated covered $0 basis: the credit stays uncovered/PENDING for review.
        assertThat(dstPos.totalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(dstPos.uncoveredQuantity()).isEqualByComparingTo("2.0");
        assertThat(dstPos.hasIncompleteHistory() || dstPos.hasUnresolvedFlags()).isTrue();
    }

    @Test
    @DisplayName("ADR-042/RC-9: a self-funded :FUND corridor-out still posts a single CARRY_OUT to :FUND; the accountRef rule does not fan out onto a sibling umbrella lot")
    void selfFundedFundCorridorOutStaysSingleFundDrain() {
        // RC-9 shape: an EXTERNAL_INBOUND deposit funded :FUND with exactly 3.06 (self-funded), and
        // the corridor-out spends that same 3.06 off :FUND. A distinct umbrella lot exists — the
        // coverage-and-existence-gated accountRef rule must NOT fan the drain onto it: :FUND covers
        // the leg, so a single CARRY_OUT is released from :FUND and the umbrella lot is untouched.
        AssetKey bybitEth = new AssetKey(BYBIT, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState bybitPos = replayState.position(bybitEth);
        bybitPos.setQuantity(new BigDecimal("3.06"));
        bybitPos.setTotalCostBasisUsd(new BigDecimal("9272.11"));
        bybitPos.setUncoveredQuantity(BigDecimal.ZERO);
        bybitPos.setPerWalletAvco(new BigDecimal("3030.10"));

        // A distinct umbrella sibling lot that MUST NOT be drained.
        AssetKey umbrellaEth = new AssetKey("BYBIT:33625378", NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState umbrellaPos = replayState.position(umbrellaEth);
        umbrellaPos.setQuantity(new BigDecimal("5.0"));
        umbrellaPos.setTotalCostBasisUsd(new BigDecimal("15000"));
        umbrellaPos.setUncoveredQuantity(BigDecimal.ZERO);
        umbrellaPos.setPerWalletAvco(new BigDecimal("3000"));

        // CEX → wallet corridor-out on :FUND, with the flow explicitly naming its :FUND account.
        NormalizedTransaction outLeg = corridorTx(NormalizedTransactionSource.BYBIT, BYBIT, WALLET, "-3.06");
        outLeg.getFlows().getFirst().setAccountRef(BYBIT);
        handler.applyTransfer(outLeg, outLeg.getFlows().getFirst(), 0, bybitPos, replayState);

        assertThat(bybitPos.quantity()).as(":FUND fully drained by the single corridor-out").isEqualByComparingTo("0");
        assertThat(umbrellaPos.quantity())
                .as("sibling umbrella lot untouched — the accountRef rule did not fan out")
                .isEqualByComparingTo("5.0");
        assertThat(umbrellaPos.totalCostBasisUsd()).isEqualByComparingTo("15000");

        // The on-chain credit inherits the full released :FUND basis (a single coherent CARRY_OUT).
        AssetKey mantleEth = new AssetKey(WALLET, NetworkId.MANTLE, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState mantlePos = replayState.position(mantleEth);
        NormalizedTransaction inLeg = corridorTx(NormalizedTransactionSource.ON_CHAIN, WALLET, BYBIT, "3.06");
        inLeg.getFlows().getFirst().setAccountRef(BYBIT);
        handler.applyTransfer(inLeg, inLeg.getFlows().getFirst(), 0, mantlePos, replayState);

        assertThat(mantlePos.totalCostBasisUsd()).isCloseTo(new BigDecimal("9272.11"), within(new BigDecimal("0.01")));
        assertThat(guard.evaluate(replayState).conserved()).isTrue();
    }

    private static NormalizedTransaction bridgeTx(
            NormalizedTransactionType type,
            NetworkId networkId,
            String correlationId,
            String signedQty,
            String counterpartyAddress
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(type + ":" + networkId + ":" + signedQty);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setType(type);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(networkId);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        tx.setBlockTimestamp(Instant.parse("2026-03-12T10:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal(signedQty));
        flow.setCounterpartyAddress(counterpartyAddress);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }

    private static NormalizedTransaction corridorTx(
            NormalizedTransactionSource source,
            String wallet,
            String matchedCounterparty,
            String signedQty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(source + ":" + wallet + ":" + signedQty);
        tx.setSource(source);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(wallet);
        tx.setNetworkId(NetworkId.MANTLE);
        tx.setCorrelationId(CORR);
        tx.setContinuityCandidate(true);
        tx.setMatchedCounterparty(matchedCounterparty);
        tx.setBlockTimestamp(Instant.parse("2026-03-12T10:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal(signedQty));
        flow.setCounterpartyAddress(matchedCounterparty);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        return tx;
    }
}
