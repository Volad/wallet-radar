package com.walletradar.accounting.support;

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
    void positionWalletAddress_alwaysStripsCollapsedFundSuffix() {
        assertThat(AccountingAssetIdentitySupport.positionWalletAddress("BYBIT:" + UID + ":FUND"))
                .isEqualTo("BYBIT:" + UID);
        assertThat(AccountingAssetIdentitySupport.positionWalletAddress("BYBIT:" + UID + ":UTA"))
                .isEqualTo("BYBIT:" + UID);
    }

    private static NormalizedTransaction bybitInternalTransfer(String walletAddress, String correlationId) {
        return bybitInternalTransferWithCounterparty(walletAddress, null, correlationId);
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
