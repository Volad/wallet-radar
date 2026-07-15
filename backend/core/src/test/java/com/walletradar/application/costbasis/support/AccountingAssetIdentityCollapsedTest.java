package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies replay position wallet resolution for {@code bybit-collapsed-v1:} pairs.
 * RC-2: UTA↔FUND collapsed legs both strip to the umbrella so round-trips net on one key.
 */
class AccountingAssetIdentityCollapsedTest {

    private static final String UID = "33625378";
    private static final String CORR_ID = "bybit-collapsed-v1:abcdef1234567890";
    private static final String CORRIDOR_CORR_ID = "BYBIT-CORRIDOR:cmeth-2025-04-17";

    @Test
    void isDebtIdentity_flagsAaveDebtReceiptsAcrossChainsAndExemptsHeldAssets() {
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("VARIABLEDEBTMANUSDE")).isTrue();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("variableDebtBasUSDC")).isTrue();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("STABLEDEBTETHDAI")).isTrue();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("USDC")).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("AMANWETH")).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("ETH")).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity(null)).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("")).isFalse();
    }

    @Test
    void replayPositionWalletAddress_stripsFundSuffix_forCollapsedFundSide() {
        NormalizedTransaction tx = bybitInternalTransferWithCounterparty(
                "BYBIT:" + UID + ":FUND", "BYBIT:" + UID + ":UTA", CORR_ID);

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, null);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_stripsUTASuffix_forCollapsedUTASide() {
        NormalizedTransaction tx = bybitInternalTransfer("BYBIT:" + UID + ":UTA", CORR_ID);

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, null);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_stripsFundSuffix_forNonCollapsedFundTransaction() {
        NormalizedTransaction tx = bybitInternalTransfer("BYBIT:" + UID + ":FUND", "bybit-econ-v1:xyz");

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, null);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_stripsFundSuffix_forCollapsedFundToEarnTransfer() {
        NormalizedTransaction tx = bybitInternalTransferWithCounterparty(
                "BYBIT:" + UID + ":FUND", "BYBIT:" + UID + ":EARN", CORR_ID);

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, null);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_stripsFundSuffix_forFundTransactionWithoutCorrId() {
        NormalizedTransaction tx = bybitInternalTransfer("BYBIT:" + UID + ":FUND", null);

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, null);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_stripsFundSuffix_forCollapsedFundOutbound() {
        NormalizedTransaction tx = bybitInternalTransferWithCounterparty(
                "BYBIT:" + UID + ":FUND", "BYBIT:" + UID + ":UTA", CORR_ID);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new java.math.BigDecimal("-249.8845"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_stripsFundSuffix_forCollapsedFundInbound() {
        NormalizedTransaction tx = bybitInternalTransferWithCounterparty(
                "BYBIT:" + UID + ":FUND", "BYBIT:" + UID + ":UTA", CORR_ID);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new java.math.BigDecimal("249.8845"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_keepsFundSuffix_forEarnOnchainFundRepair() {
        // bybit-earn-onchain-fund-v1: repairs place basis directly on :FUND, so the carry-out must
        // drain that sub-account. They must stay on :FUND (not stripped to the umbrella).
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress("BYBIT:" + UID + ":FUND");
        tx.setCorrelationId("bybit-earn-onchain-fund-v1:deadbeef");
        tx.setContinuityCandidate(true);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new java.math.BigDecimal("-1.0"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID + ":FUND");
    }

    @Test
    void replayPositionWalletAddress_stripsEarnSuffix_forBybitRewardClaim() {
        // XPL double-count: a REWARD_CLAIM on :EARN must route to the umbrella so a later collapsed
        // FUND->UTA move drains a real umbrella lot instead of materialising a fresh phantom there.
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.REWARD_CLAIM);
        tx.setWalletAddress("BYBIT:" + UID + ":EARN");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new java.math.BigDecimal("3.70372886"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_collapsesFundToUmbrella_forCorridorInboundDeposit() {
        // BLOCKER-4: an inbound corridor CARRY_IN (+qty) credits :FUND, but all other spot activity
        // consumes the umbrella-collapsed key. Route the deposit to the umbrella so it is reachable.
        NormalizedTransaction tx = bybitCorridorLeg("BYBIT:" + UID + ":FUND");
        NormalizedTransaction.Flow flow = transferFlow(new java.math.BigDecimal("0.10000000"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_keepsFundSuffix_forCorridorOutboundDrain() {
        // BLOCKER-4: an outbound corridor CARRY_OUT (-qty) must drain the :FUND-funded pool, so the
        // full :FUND wallet is preserved (regression guard for corridor withdrawals).
        NormalizedTransaction tx = bybitCorridorLeg("BYBIT:" + UID + ":FUND");
        NormalizedTransaction.Flow flow = transferFlow(new java.math.BigDecimal("-16.04000000"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID + ":FUND");
    }

    @Test
    void replayPositionWalletAddress_collapsesUTAToUmbrella_forCorridorInboundDeposit() {
        // :UTA already collapses today; corridor inbound on :UTA remains on the umbrella (no-op).
        NormalizedTransaction tx = bybitCorridorLeg("BYBIT:" + UID + ":UTA");
        NormalizedTransaction.Flow flow = transferFlow(new java.math.BigDecimal("0.10000000"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_collapsesFundToUmbrella_forCorridorFeeFlowOnDeposit() {
        // Fee-role guard (E5): a FEE flow on a corridor row must not preserve :FUND even if its
        // delta is negative. Corridor legs have no FEE legs, so this must fall through to umbrella.
        NormalizedTransaction tx = bybitCorridorLeg("BYBIT:" + UID + ":FUND");
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(com.walletradar.domain.transaction.normalized.NormalizedLegRole.FEE);
        flow.setQuantityDelta(new java.math.BigDecimal("-0.00010000"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void replayPositionWalletAddress_collapsesFundToUmbrella_forCorridorZeroDeltaFlow() {
        // Zero-delta guard (E5): a non-negative delta is not an outbound drain, so :FUND collapses.
        NormalizedTransaction tx = bybitCorridorLeg("BYBIT:" + UID + ":FUND");
        NormalizedTransaction.Flow flow = transferFlow(java.math.BigDecimal.ZERO);

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID);
    }

    @Test
    void positionWalletAddress_alwaysStripsCollapsedFundSuffix() {
        assertThat(AccountingAssetIdentitySupport.positionWalletAddress("BYBIT:" + UID + ":FUND"))
                .isEqualTo("BYBIT:" + UID);
        assertThat(AccountingAssetIdentitySupport.positionWalletAddress("BYBIT:" + UID + ":UTA"))
                .isEqualTo("BYBIT:" + UID);
    }

    private static NormalizedTransaction bybitInternalTransfer(String walletAddress, String correlationId) {
        return bybitInternalTransferWithCounterparty(walletAddress, null, correlationId);
    }

    private static NormalizedTransaction bybitCorridorLeg(String walletAddress) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(walletAddress);
        tx.setCorrelationId(CORRIDOR_CORR_ID);
        tx.setContinuityCandidate(true);
        return tx;
    }

    private static NormalizedTransaction.Flow transferFlow(java.math.BigDecimal quantityDelta) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(com.walletradar.domain.transaction.normalized.NormalizedLegRole.TRANSFER);
        flow.setQuantityDelta(quantityDelta);
        return flow;
    }

    private static NormalizedTransaction bybitInternalTransferWithCounterparty(
            String walletAddress, String counterparty, String correlationId) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setWalletAddress(walletAddress);
        tx.setMatchedCounterparty(counterparty);
        tx.setCorrelationId(correlationId);
        tx.setContinuityCandidate(true);
        return tx;
    }
}
