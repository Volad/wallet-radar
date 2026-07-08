package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.BorrowLiabilityTracker;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.persistence.LedgerPointCollector;
import com.walletradar.application.costbasis.application.replay.state.ReplayExecutionState;
import com.walletradar.application.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayFlowSupport;
import com.walletradar.application.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Verifies Bug D fix: borrowed assets enter the portfolio at market Tax AVCO but $0 Net AVCO.
 * The liability exists on the counterpart side; the borrowed unit is not a net economic acquisition.
 */
@ExtendWith(MockitoExtension.class)
class BorrowReplayHandlerZeroNetCostTest {

    private static final String WALLET = "0xaabbccddeeff0011223344556677889900112233";
    private static final String USDC_CONTRACT = "0xaaaa0000000000000000000000000000000000aa";

    @Mock
    private BorrowLiabilityTracker borrowLiabilityTracker;
    @Mock
    private ReplayAssetSupport assetSupport;

    private BorrowReplayHandler handler;
    private ReplayExecutionState replayState;
    private AssetKey usdcKey;

    @BeforeEach
    void setUp() {
        GenericFlowReplayEngine engine = new GenericFlowReplayEngine(null);
        ReplayFlowSupport flowSupport = new ReplayFlowSupport(engine);
        handler = new BorrowReplayHandler(borrowLiabilityTracker, assetSupport, flowSupport, null);

        usdcKey = new AssetKey(WALLET, NetworkId.MANTLE, USDC_CONTRACT, "USDC", "SYMBOL:USDC");

        LedgerPointCollector collector = new LedgerPointCollector("test-universe", new ArrayList<>(), Instant.now());
        replayState = new ReplayExecutionState(null, collector);

        lenient().when(assetSupport.assetKey(any(), any())).thenReturn(usdcKey);
    }

    @Test
    void borrowedUsdcEntersAtMarketTaxCostButZeroNetCost() {
        NormalizedTransaction tx = buildBorrowTx("corr-abc-123");
        NormalizedTransaction.Flow usdcBorrowFlow = buildBuyFlow("USDC", USDC_CONTRACT, new BigDecimal("900"),
                new BigDecimal("1.0"));

        handler.apply(tx, usdcBorrowFlow, 0, replayState);

        PositionState position = replayState.position(usdcKey);
        // Qty acquired
        assertThat(position.quantity()).isEqualByComparingTo(new BigDecimal("900"));
        // Tax basis = 900 × $1.00 = $900
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo(new BigDecimal("900"));
        // Net basis = $0 (borrowed asset is a liability, not a net acquisition)
        assertThat(position.netTotalCostBasisUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void borrowedMntEntersAtMarketTaxCostButZeroNetCost() {
        AssetKey mntKey = new AssetKey(WALLET, NetworkId.MANTLE, null, "MNT", "SYMBOL:MNT");
        lenient().when(assetSupport.assetKey(any(), any())).thenReturn(mntKey);

        NormalizedTransaction tx = buildBorrowTx("corr-mnt-456");
        NormalizedTransaction.Flow mntFlow = buildBuyFlow("MNT", null, new BigDecimal("3532"), new BigDecimal("0.72"));

        handler.apply(tx, mntFlow, 0, replayState);

        PositionState position = replayState.position(mntKey);
        assertThat(position.quantity()).isEqualByComparingTo(new BigDecimal("3532"));
        // Tax basis = 3532 × $0.72 ≈ $2543.04
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo(
                new BigDecimal("3532").multiply(new BigDecimal("0.72")));
        // Net basis = $0
        assertThat(position.netTotalCostBasisUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void debtTokenFlowIsSkipped() {
        NormalizedTransaction tx = buildBorrowTx("corr-debt-789");
        NormalizedTransaction.Flow debtFlow = buildBuyFlow("variableDebtUSDC", "0xdebt000", new BigDecimal("900"),
                new BigDecimal("1.0"));

        handler.apply(tx, debtFlow, 0, replayState);

        // Debt token is skipped — position not touched
        PositionState position = replayState.position(usdcKey);
        assertThat(position.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void additionalBorrow_borrowedUsdcAgainEntersAtZeroNetCost() {
        // Simulates a second borrow (Pattern B - pure additional borrow)
        NormalizedTransaction tx = buildBorrowTx("corr-additional-999");
        NormalizedTransaction.Flow usdcBorrowFlow = buildBuyFlow("USDC", USDC_CONTRACT, new BigDecimal("100"),
                new BigDecimal("1.0"));

        handler.apply(tx, usdcBorrowFlow, 0, replayState);

        PositionState position = replayState.position(usdcKey);
        assertThat(position.quantity()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(position.netTotalCostBasisUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private NormalizedTransaction buildBorrowTx(String correlationId) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.BORROW);
        tx.setWalletAddress(WALLET);
        tx.setNetworkId(NetworkId.MANTLE);
        tx.setCorrelationId(correlationId);
        tx.setBlockTimestamp(Instant.parse("2025-06-17T12:00:00Z"));
        return tx;
    }

    private NormalizedTransaction.Flow buildBuyFlow(String symbol, String contract, BigDecimal qty, BigDecimal price) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol(symbol);
        flow.setAssetContract(contract);
        flow.setQuantityDelta(qty);
        flow.setUnitPriceUsd(price);
        return flow;
    }
}
