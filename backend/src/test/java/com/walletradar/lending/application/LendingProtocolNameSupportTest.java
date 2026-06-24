package com.walletradar.lending.application;

import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LendingProtocolNameSupportTest {

    @Test
    void infersFluidAndCompoundFromReceiptSymbolsWhenProtocolNameIsMissing() {
        assertThat(LendingProtocolNameSupport.displayProtocol(null, "fUSDC")).isEqualTo("Fluid");
        assertThat(LendingProtocolNameSupport.displayProtocol(null, "cUSDC")).isEqualTo("Compound");
    }

    @Test
    void infersAaveAndSiloFromReceiptSymbolsWhenProtocolNameIsMissing() {
        assertThat(LendingProtocolNameSupport.displayProtocol(null, "aLinWETH")).isEqualTo("Aave");
        assertThat(LendingProtocolNameSupport.displayProtocol(null, "soUSDC")).isEqualTo("Silo");
    }

    @Test
    void infersAaveFromVariableDebtReceiptSymbolsWhenProtocolNameIsMissing() {
        assertThat(LendingProtocolNameSupport.displayProtocol(null, "VARIABLEDEBTBASUSDC")).isEqualTo("Aave");
        assertThat(LendingProtocolNameSupport.displayProtocol(null, "STABLEDEBTETH")).isEqualTo("Aave");
    }

    @Test
    void infersAaveFromAwethReceiptSymbolWhenProtocolNameIsMissing() {
        assertThat(LendingProtocolNameSupport.displayProtocol(null, "AWETH")).isEqualTo("Aave");
    }

    @Test
    void treatsRewardClaimProtocolsAsKnownLendingProtocolsOnlyWhenSupported() {
        assertThat(LendingProtocolNameSupport.isKnownLendingProtocol("Compound V3")).isTrue();
        assertThat(LendingProtocolNameSupport.isKnownLendingProtocol("Fluid")).isTrue();
        assertThat(LendingProtocolNameSupport.isKnownLendingProtocol("Uniswap")).isFalse();
    }

    @Test
    void resolvesProtocolFromTransactionFlows() {
        NormalizedTransaction transaction = new NormalizedTransaction();
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol("fUSDT");
        transaction.setFlows(List.of(flow));

        assertThat(LendingProtocolNameSupport.resolveProtocol(transaction)).isEqualTo("Fluid");
    }
}
