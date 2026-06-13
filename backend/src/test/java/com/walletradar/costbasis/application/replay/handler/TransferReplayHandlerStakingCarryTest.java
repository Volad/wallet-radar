package com.walletradar.costbasis.application.replay.handler;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PassThroughCorridorPlan;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.costbasis.application.replay.support.ContinuityCarryService;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.costbasis.application.replay.support.ReplayMarketAuthority;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.costbasis.application.replay.support.ReplayPendingTransferMatcher;
import com.walletradar.costbasis.application.replay.support.ReplayTransferClassifier;
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
 * R-1 — Bybit staking-deposit receipt basis-carry.
 *
 * <p>A collapsed FUND-side {@code STAKING_DEPOSIT} (ETH→METH, both FAMILY:ETH) resolves its
 * position key to the UID umbrella, but the staked ETH principal sits on the {@code :FUND}
 * sub-account. Before the fix the family-equivalent-custody outbound drained the empty umbrella
 * and minted the staking receipt (METH) at $0, collapsing FAMILY:ETH AVCO. The carry source must
 * resolve to the {@code :FUND} sub-account that actually holds the inventory so the staked
 * principal AVCO carries into the receipt (conserved, realised = 0).
 */
class TransferReplayHandlerStakingCarryTest {

    private TransferReplayHandler handler;
    private ReplayExecutionState replayState;

    @BeforeEach
    void setUp() {
        var assetSupport = new ReplayAssetSupport();
        var engine = new GenericFlowReplayEngine();
        var flowSupport = new ReplayFlowSupport(engine);
        var carryService = new ContinuityCarryService(engine, flowSupport);
        var keyFactory = new ReplayPendingTransferKeyFactory(assetSupport);
        var classifier = new ReplayTransferClassifier(keyFactory);
        var matcher = new ReplayPendingTransferMatcher();
        var marketAuthority = mock(ReplayMarketAuthority.class);
        handler = new TransferReplayHandler(
                flowSupport,
                carryService,
                keyFactory,
                classifier,
                matcher,
                marketAuthority
        );
        replayState = new ReplayExecutionState(
                new PassThroughCorridorPlan(Map.of(), Map.of()),
                new LedgerPointCollector("u1", new ArrayList<>(), Instant.now())
        );
    }

    @Test
    void stakingDepositCarriesFundPrincipalBasisIntoReceiptInsteadOfMintingAtZero() {
        // ETH principal sits on the :FUND sub-account; the UID umbrella is empty (it is the
        // position key the collapsed FUND-side deposit resolved to).
        AssetKey fundEthKey = new AssetKey("BYBIT:33625378:FUND", null, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState fundEth = replayState.position(fundEthKey);
        fundEth.setQuantity(new BigDecimal("0.709"));
        fundEth.setTotalCostBasisUsd(new BigDecimal("1772.5"));
        fundEth.setUncoveredQuantity(BigDecimal.ZERO);
        fundEth.setPerWalletAvco(new BigDecimal("2500"));

        AssetKey umbrellaEthKey = new AssetKey("BYBIT:33625378", null, "SYMBOL:ETH", "ETH", "FAMILY:ETH");
        PositionState umbrellaEth = replayState.position(umbrellaEthKey);

        NormalizedTransaction stakingDeposit = new NormalizedTransaction();
        stakingDeposit.setId("staking-deposit");
        stakingDeposit.setSource(NormalizedTransactionSource.BYBIT);
        stakingDeposit.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        stakingDeposit.setWalletAddress("BYBIT:33625378:FUND");

        NormalizedTransaction.Flow ethOut = new NormalizedTransaction.Flow();
        ethOut.setRole(NormalizedLegRole.TRANSFER);
        ethOut.setAssetSymbol("ETH");
        ethOut.setAccountRef("BYBIT:33625378:FUND");
        ethOut.setQuantityDelta(new BigDecimal("-0.709"));

        NormalizedTransaction.Flow methIn = new NormalizedTransaction.Flow();
        methIn.setRole(NormalizedLegRole.TRANSFER);
        methIn.setAssetSymbol("METH");
        methIn.setAccountRef("BYBIT:33625378:FUND");
        methIn.setQuantityDelta(new BigDecimal("0.66865026"));
        stakingDeposit.setFlows(List.of(ethOut, methIn));

        // Outbound ETH leg — dispatcher passes the umbrella position (collapsed FUND-side key).
        handler.applyTransfer(stakingDeposit, ethOut, 0, umbrellaEth, replayState);

        // Inbound METH receipt leg.
        AssetKey methKey = new AssetKey("BYBIT:33625378:FUND", null, "SYMBOL:METH", "METH", "FAMILY:ETH");
        PositionState meth = replayState.position(methKey);
        handler.applyTransfer(stakingDeposit, methIn, 1, meth, replayState);

        // Receipt carries the staked principal basis (≈ 0.66865026 × $2500 ≈ $1671.6), not $0.
        assertThat(meth.totalCostBasisUsd()).isGreaterThan(new BigDecimal("1500"));
        assertThat(meth.uncoveredQuantity()).isZero();
        assertThat(meth.perWalletAvco()).isGreaterThan(new BigDecimal("2000"));

        // Conserved: the basis left the :FUND ETH source (drained), not minted from nothing.
        assertThat(fundEth.quantity()).isEqualByComparingTo("0");
        assertThat(fundEth.totalCostBasisUsd()).isEqualByComparingTo("0");
    }
}
