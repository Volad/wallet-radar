package com.walletradar.application.costbasis.application.replay.handler;

import com.walletradar.application.costbasis.application.replay.support.ReplayAssetSupport;
import com.walletradar.application.costbasis.application.replay.support.ReplayPendingTransferKeyFactory;
import com.walletradar.application.costbasis.application.replay.support.ReplayTransferClassifier;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransferReplayHandlerStakingCarryTest {

    private final ReplayTransferClassifier classifier = new ReplayTransferClassifier(
            new ReplayPendingTransferKeyFactory(new ReplayAssetSupport())
    );

    @Test
    void ethToMethStakingDepositIsNotFamilyEquivalentCustodyTransfer() {
        NormalizedTransaction stakingDeposit = new NormalizedTransaction();
        stakingDeposit.setSource(NormalizedTransactionSource.BYBIT);
        stakingDeposit.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        stakingDeposit.setWalletAddress("BYBIT:33625378:FUND");

        NormalizedTransaction.Flow ethOut = new NormalizedTransaction.Flow();
        ethOut.setRole(NormalizedLegRole.TRANSFER);
        ethOut.setAssetSymbol("ETH");
        ethOut.setQuantityDelta(new BigDecimal("-0.709"));

        NormalizedTransaction.Flow methIn = new NormalizedTransaction.Flow();
        methIn.setRole(NormalizedLegRole.TRANSFER);
        methIn.setAssetSymbol("METH");
        methIn.setQuantityDelta(new BigDecimal("0.66865026"));
        stakingDeposit.setFlows(List.of(ethOut, methIn));

        assertThat(classifier.isFamilyEquivalentCustodyTransfer(stakingDeposit, ethOut)).isFalse();
        assertThat(classifier.isFamilyEquivalentCustodyTransfer(stakingDeposit, methIn)).isFalse();
    }
}
