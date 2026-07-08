package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.testsupport.TransferReplayHandlerFixtures;
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
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Fix B lever 1 — a Bybit {@code bybit-rekeyed-v1:} {@code :FUND → :UTA} internal move whose
 * inventory was deposited onto {@code :FUND} by a corridor {@code CARRY_IN} must drain the
 * {@code :FUND} sub-account, not the (stale) umbrella. This keeps the moved principal on a single
 * coherent position so the destination sale disposes it fully (the Sep-10 cmETH case). The RC-2
 * net-on-umbrella behaviour is preserved for a plain rekeyed round-trip whose inventory sits on the
 * umbrella (the umbrella already covers, so no redirect fires).
 */
class TransferReplayHandlerRekeyedFundDrainTest {

    private static final String REKEYED_CORR =
            "bybit-rekeyed-v1:ede2bccf4d085403c052b0d2ba310c52fb63016023b63a65621a61d79e041582";
    private static final String UMBRELLA = "BYBIT:33625378";
    private static final AssetKey UMBRELLA_KEY =
            new AssetKey(UMBRELLA, null, "SYMBOL:CMETH", "CMETH", "FAMILY:ETH");
    private static final AssetKey FUND_KEY =
            new AssetKey(UMBRELLA + ":FUND", null, "SYMBOL:CMETH", "CMETH", "FAMILY:ETH");

    private TransferReplayHandler handler;
    private ReplayExecutionState replayState;

    @BeforeEach
    void setUp() {
        var assetSupport = new ReplayAssetSupport();
        var engine = new GenericFlowReplayEngine(null);
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        var keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        var marketAuthority = mock(ReplayMarketAuthority.class);
        handler = TransferReplayHandlerFixtures.handler(flowSupport, carryService, keyFactory, classifier, matcher, marketAuthority);
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now())
        );
    }

    @Test
    void rekeyedFundDebitDrainsFundCorridorInventoryNotStaleUmbrella() {
        // Corridor inventory sits on :FUND (0.862092 cmETH); the umbrella holds only a stale April
        // tail (0.1) that cannot cover the move. The :FUND-side rekeyed debit must drain :FUND.
        PositionState umbrella = replayState.position(UMBRELLA_KEY);
        umbrella.setQuantity(new BigDecimal("0.1"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("178.56"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("1785.6"));

        PositionState fund = replayState.position(FUND_KEY);
        fund.setQuantity(new BigDecimal("0.862092260317885"));
        fund.setTotalCostBasisUsd(new BigDecimal("1539.29"));
        fund.setUncoveredQuantity(BigDecimal.ZERO);
        fund.setPerWalletAvco(new BigDecimal("1785.6"));

        NormalizedTransaction debit = rekeyedLeg(
                "BYBIT-33625378:FUNDING_HISTORY:rekeyed-fund-out",
                UMBRELLA + ":FUND",
                UMBRELLA + ":UTA",
                new BigDecimal("-0.862092260317885"));
        // The dispatcher keys the rekeyed :FUND leg onto the umbrella position (RC-2 strip).
        handler.applyTransfer(debit, debit.getFlows().get(0), 0, umbrella, replayState);

        assertThat(fund.quantity())
                .as(":FUND corridor inventory drained by the rekeyed debit")
                .isEqualByComparingTo("0");
        assertThat(umbrella.quantity())
                .as("stale umbrella tail untouched by the rekeyed debit")
                .isEqualByComparingTo("0.1");

        NormalizedTransaction credit = rekeyedLeg(
                "BYBIT-33625378:INTERNAL_TRANSFER:rekeyed-uta-in",
                UMBRELLA + ":UTA",
                UMBRELLA + ":FUND",
                new BigDecimal("0.86209226"));
        handler.applyTransfer(credit, credit.getFlows().get(0), 0, umbrella, replayState);

        // The moved principal lands on the umbrella (the :UTA credit strips to the umbrella) with its
        // carried basis, on top of the stale tail — a single coherent position for the later sale.
        assertThat(umbrella.quantity()).isEqualByComparingTo("0.96209226");
        assertThat(fund.quantity()).isEqualByComparingTo("0");
    }

    @Test
    void plainRekeyedRoundTripWithUmbrellaInventoryNetsOnUmbrella() {
        // RC-2 control: when the inventory sits on the umbrella (no corridor :FUND deposit), the
        // umbrella already covers the move, so the coverage gate keeps the drain on the umbrella and
        // the :FUND position is never credited/debited (no inbound-only phantom).
        PositionState umbrella = replayState.position(UMBRELLA_KEY);
        umbrella.setQuantity(new BigDecimal("0.5"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("892.8"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("1785.6"));

        NormalizedTransaction debit = rekeyedLeg(
                "BYBIT-33625378:FUNDING_HISTORY:rekeyed-fund-out-plain",
                UMBRELLA + ":FUND",
                UMBRELLA + ":UTA",
                new BigDecimal("-0.3"));
        handler.applyTransfer(debit, debit.getFlows().get(0), 0, umbrella, replayState);

        NormalizedTransaction credit = rekeyedLeg(
                "BYBIT-33625378:INTERNAL_TRANSFER:rekeyed-uta-in-plain",
                UMBRELLA + ":UTA",
                UMBRELLA + ":FUND",
                new BigDecimal("0.3"));
        handler.applyTransfer(credit, credit.getFlows().get(0), 0, umbrella, replayState);

        assertThat(umbrella.quantity()).isEqualByComparingTo("0.5");
        // No :FUND position was materialised as an inbound-only phantom.
        assertThat(replayState.positions().position(FUND_KEY).quantity()).isEqualByComparingTo("0");
    }

    private static NormalizedTransaction rekeyedLeg(
            String id,
            String wallet,
            String counterparty,
            BigDecimal quantityDelta
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(wallet);
        tx.setMatchedCounterparty(counterparty);
        tx.setCounterpartyAddress(counterparty);
        tx.setCorrelationId(REKEYED_CORR);
        tx.setContinuityCandidate(Boolean.TRUE);
        tx.setBlockTimestamp(Instant.parse("2025-09-10T18:00:00Z"));

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setAccountRef(wallet);
        flow.setQuantityDelta(quantityDelta);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
