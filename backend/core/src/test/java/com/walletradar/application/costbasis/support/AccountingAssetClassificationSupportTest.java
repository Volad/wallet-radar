package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingAssetClassificationSupportTest {

    @Test
    void c2TokensHaveOwnContinuityFamilies() {
        assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity("WSTETH", null))
                .isEqualTo("FAMILY:WSTETH");
        assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity("EWETH-1", null))
                .isEqualTo("FAMILY:EWETH");
    }

    @Test
    void cmethIsC1SharingMethFamily() {
        // CMETH is a 1:1 Bybit receipt for METH staking — treated as C1 sharing FAMILY:METH.
        assertThat(AccountingAssetClassificationSupport.continuityFamilyIdentity("CMETH", null))
                .isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("CMETH", null))
                .isEqualTo("FAMILY:METH");
        assertThat(AccountingAssetClassificationSupport.sharesCanonicalTokenIdentity(
                "METH", null, "CMETH", null
        )).isTrue();
    }

    @Test
    void methToCmethIsNotCrossCanonical() {
        // METH→CMETH staking deposit should route through LiquidStakingReplayHandler (REALLOCATE), not DISPOSE+ACQUIRE.
        NormalizedTransaction tx = stakingDeposit(
                flow("METH", "-1.0"),
                flow("CMETH", "1.0")
        );
        assertThat(AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(tx)).isFalse();
    }

    @Test
    void c1WrappersShareUnderlyingCanonicalIdentity() {
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("ETH", null))
                .isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("WETH", null))
                .isEqualTo("FAMILY:ETH");
        assertThat(AccountingAssetClassificationSupport.canonicalTokenIdentity("AMANWETH", null))
                .isEqualTo("FAMILY:ETH");
    }

    @Test
    void ethToCmethIsCrossCanonicalIdentityPair() {
        NormalizedTransaction tx = stakingDeposit(
                flow("ETH", "-1.0"),
                flow("CMETH", "0.97")
        );
        assertThat(AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(tx)).isTrue();
    }

    @Test
    void wethToAwethSharesCanonicalIdentity() {
        NormalizedTransaction tx = lendingDeposit(
                flow("WETH", "-1.0"),
                flow("AMANWETH", "1.0")
        );
        assertThat(AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(tx)).isFalse();
        assertThat(AccountingAssetClassificationSupport.sharesCanonicalTokenIdentity(
                "WETH", null, "AMANWETH", null
        )).isTrue();
    }

    @Test
    void cmethCorridorSameTokenSharesCanonicalIdentity() {
        assertThat(AccountingAssetClassificationSupport.sharesCanonicalTokenIdentity(
                "CMETH", null, "CMETH", null
        )).isTrue();
    }

    private static NormalizedTransaction stakingDeposit(NormalizedTransaction.Flow... flows) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.STAKING_DEPOSIT);
        tx.setFlows(List.of(flows));
        return tx;
    }

    private static NormalizedTransaction lendingDeposit(NormalizedTransaction.Flow... flows) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setType(NormalizedTransactionType.LENDING_DEPOSIT);
        tx.setFlows(List.of(flows));
        return tx;
    }

    private static NormalizedTransaction.Flow flow(String symbol, String qty) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(symbol);
        flow.setQuantityDelta(new BigDecimal(qty));
        return flow;
    }
}
