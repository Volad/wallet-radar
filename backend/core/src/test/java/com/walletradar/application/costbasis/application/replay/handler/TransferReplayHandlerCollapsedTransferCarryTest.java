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
 * RC-2 — Bybit {@code bybit-collapsed-v1} UTA↔FUND legs net on the umbrella position key.
 */
class TransferReplayHandlerCollapsedTransferCarryTest {

    private static final String CORR = "bybit-collapsed-v1:205c1f65b8509fbeaa7e8e03a2c363b51e574bcaaa0087bbe941219eefb7923e";

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
    void collapsedFundToUtaTransferDrainsUmbrellaInventory() {
        AssetKey umbrellaKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:CMETH", "CMETH", "FAMILY:ETH");
        PositionState umbrella = replayState.position(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("0.95209"));
        umbrella.setTotalCostBasisUsd(new BigDecimal("1700.05"));
        umbrella.setUncoveredQuantity(BigDecimal.ZERO);
        umbrella.setPerWalletAvco(new BigDecimal("1785.6"));

        NormalizedTransaction outbound = collapsedLeg(
                "BYBIT-33625378:FUNDING_HISTORY:bea683",
                "BYBIT:33625378:FUND",
                "BYBIT:33625378:UTA",
                new BigDecimal("-0.66955681"));
        handler.applyTransfer(outbound, outbound.getFlows().get(0), 0, umbrella, replayState);

        assertThat(umbrella.quantity()).isEqualByComparingTo("0.28253319");

        NormalizedTransaction inbound = collapsedLeg(
                "BYBIT-33625378:INTERNAL_TRANSFER:selfTransfer_4320",
                "BYBIT:33625378:UTA",
                "BYBIT:33625378:FUND",
                new BigDecimal("0.66955681"));
        handler.applyTransfer(inbound, inbound.getFlows().get(0), 0, umbrella, replayState);

        assertThat(umbrella.quantity()).isEqualByComparingTo("0.95209");
        assertThat(umbrella.perWalletAvco()).isGreaterThan(new BigDecimal("1700"));
        assertThat(umbrella.totalCostBasisUsd()).isGreaterThan(new BigDecimal("1190"));
        assertThat(umbrella.uncoveredQuantity()).isZero();
    }

    private static NormalizedTransaction collapsedLeg(
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
        tx.setCorrelationId(CORR);
        tx.setContinuityCandidate(Boolean.TRUE);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setAccountRef(wallet);
        flow.setQuantityDelta(quantityDelta);
        tx.setFlows(List.of(flow));
        return tx;
    }
}
