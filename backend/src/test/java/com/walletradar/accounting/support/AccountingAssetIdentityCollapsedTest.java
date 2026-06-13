package com.walletradar.accounting.support;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code bybit-collapsed-v1:} FUND-side transactions preserve the full
 * {@code :FUND} wallet address in {@code replayPositionWalletAddress()}, so CARRY_IN basis
 * lands on the correct sub-account position rather than the stripped root position.
 */
class AccountingAssetIdentityCollapsedTest {

    private static final String UID = "33625378";
    private static final String CORR_ID = "bybit-collapsed-v1:abcdef1234567890";

    @Test
    void isDebtIdentity_flagsAaveDebtReceiptsAcrossChainsAndExemptsHeldAssets() {
        // F-4: variableDebt* / stableDebt* are liability markers (any network prefix), never held.
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("VARIABLEDEBTMANUSDE")).isTrue();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("variableDebtBasUSDC")).isTrue();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("STABLEDEBTETHDAI")).isTrue();
        // Held assets and receipt aTokens are not debt identities.
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("USDC")).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("AMANWETH")).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("ETH")).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity(null)).isFalse();
        assertThat(AccountingAssetIdentitySupport.isDebtIdentity("")).isFalse();
    }

    @Test
    void replayPositionWalletAddress_preservesFullFundAddress_forCollapsedFundSide() {
        // FUND CARRY_IN with UTA counterparty: full :FUND address must be preserved.
        NormalizedTransaction tx = bybitInternalTransferWithCounterparty(
                "BYBIT:" + UID + ":FUND", "BYBIT:" + UID + ":UTA", CORR_ID);

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, null);

        assertThat(result).isEqualTo("BYBIT:" + UID + ":FUND");
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
        // FUND→EARN collapsed pair: EARN is the counterparty; acquisitions sit on the root
        // position so :FUND must be stripped, not preserved.
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
    void replayPositionWalletAddress_preservesFullFundAddress_forCollapsedFundInbound() {
        NormalizedTransaction tx = bybitInternalTransferWithCounterparty(
                "BYBIT:" + UID + ":FUND", "BYBIT:" + UID + ":UTA", CORR_ID);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new java.math.BigDecimal("249.8845"));

        String result = AccountingAssetIdentitySupport.replayPositionWalletAddress(tx, flow);

        assertThat(result).isEqualTo("BYBIT:" + UID + ":FUND");
    }

    @Test
    void positionWalletAddress_alwaysStripsCollapsedFundSuffix() {
        // positionWalletAddress() (no-arg version) always strips — only replayPositionWalletAddress
        // preserves :FUND for collapsed pairs.
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
