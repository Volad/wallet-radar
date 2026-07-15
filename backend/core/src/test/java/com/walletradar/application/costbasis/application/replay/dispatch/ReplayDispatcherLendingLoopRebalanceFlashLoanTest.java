package com.walletradar.application.costbasis.application.replay.dispatch;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BLOCKER-9 / ADR-057 Part B: Verifies that within-transaction flash-loan inflows
 * inside {@code LENDING_LOOP_REBALANCE} transactions are skipped during replay.
 *
 * <p>Flash-loan legs are identified by the sentinel counterparty pattern
 * {@code UNKNOWN:<txHash>:NETWORK:WALLET:TRANSFER:ASSET:INDEX}.</p>
 */
class ReplayDispatcherLendingLoopRebalanceFlashLoanTest {

    private static final String TX_HASH = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    private static final String SENTINEL =
            "UNKNOWN:" + TX_HASH + ":AVALANCHE:0xwallet:TRANSFER:eUSDC-2:0";

    @Test
    void sentinelCounterpartyInflowInLendingLoopRebalanceIsFlashLoan() {
        NormalizedTransaction tx = lending_loop_rebalance(TX_HASH);
        NormalizedTransaction.Flow inflow = inboundFlow("eUSDC-2", "2454", SENTINEL);
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, inflow)).isTrue();
    }

    @Test
    void sentinelCaseInsensitive() {
        NormalizedTransaction tx = lending_loop_rebalance(TX_HASH);
        NormalizedTransaction.Flow inflow = inboundFlow("eUSDC-2", "2454", SENTINEL.toLowerCase());
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, inflow)).isTrue();
    }

    @Test
    void nonSentinelCounterpartyNotSkipped() {
        NormalizedTransaction tx = lending_loop_rebalance(TX_HASH);
        NormalizedTransaction.Flow inflow = inboundFlow("eUSDC-2", "2454", "0xeulerprotocol");
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, inflow)).isFalse();
    }

    @Test
    void outboundFlowNotSkipped() {
        NormalizedTransaction tx = lending_loop_rebalance(TX_HASH);
        // Negative quantityDelta = outbound, should not be skipped
        NormalizedTransaction.Flow outflow = new NormalizedTransaction.Flow();
        outflow.setAssetSymbol("eUSDC-2");
        outflow.setQuantityDelta(new BigDecimal("-2454"));
        outflow.setCounterpartyAddress(SENTINEL);
        outflow.setRole(NormalizedLegRole.TRANSFER);
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, outflow)).isFalse();
    }

    @Test
    void wrongTransactionTypeNotSkipped() {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash(TX_HASH);
        tx.setType(NormalizedTransactionType.BORROW);
        NormalizedTransaction.Flow inflow = inboundFlow("eUSDC-2", "2454", SENTINEL);
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, inflow)).isFalse();
    }

    @Test
    void unknownPrefixWithoutTxHashNotSkipped() {
        NormalizedTransaction tx = lending_loop_rebalance(TX_HASH);
        // Starts with UNKNOWN: but doesn't contain the txHash
        NormalizedTransaction.Flow inflow = inboundFlow("eUSDC-2", "2454",
                "UNKNOWN:0xdeadbeef:AVALANCHE:0xwallet:TRANSFER:eUSDC-2:0");
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, inflow)).isFalse();
    }

    @Test
    void nullFlowNotSkipped() {
        NormalizedTransaction tx = lending_loop_rebalance(TX_HASH);
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, null)).isFalse();
    }

    @Test
    void nullCounterpartyNotSkipped() {
        NormalizedTransaction tx = lending_loop_rebalance(TX_HASH);
        NormalizedTransaction.Flow inflow = inboundFlow("eUSDC-2", "2454", null);
        assertThat(ReplayDispatcher.isLendingLoopRebalanceFlashLoanInflow(tx, inflow)).isFalse();
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static NormalizedTransaction lending_loop_rebalance(String txHash) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setTxHash(txHash);
        tx.setType(NormalizedTransactionType.LENDING_LOOP_REBALANCE);
        return tx;
    }

    private static NormalizedTransaction.Flow inboundFlow(String symbol, String qty, String counterparty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        flow.setCounterpartyAddress(counterparty);
        flow.setRole(NormalizedLegRole.TRANSFER);
        return flow;
    }
}
